"""The ranking engine.

Objective function is coins per hour, not raw profit per fuse. A 50k margin on
a shard that trades twice a day is worth less than a 3k margin on one that
clears hundreds of units an hour, and ranking by margin alone is the classic way
to end up holding inventory nobody wants.

    fuses_per_hour = capture_share * min(input A supply, input B supply,
                                         output absorption)
    coins_per_hour = profit_per_fuse * fuses_per_hour
    score          = coins_per_hour * hour_factor ** hour_alpha

``hour_alpha`` defaults to 0.5, which deliberately makes time-of-day a
tiebreaker rather than a driver: a 2x peak hour moves the score by 1.4x, while
demand and margin move it linearly.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from . import pricing
from .bazaar import Product
from .config import HOURS_PER_WEEK, Config  # noqa: F401  (HOURS_PER_WEEK used below)
from .recipes import Recipe, RecipeBook


@dataclass(slots=True)
class Opportunity:
    recipe: Recipe
    result_name: str
    a_name: str
    b_name: str
    units_a: int
    units_b: int
    out_qty: int

    cost: float
    revenue_net: float
    profit: float
    roi: float

    fuses_per_hour: float
    coins_per_hour: float
    score: float

    minutes_in: float
    minutes_out: float
    cycle_minutes: float

    limiter: str            # which of the three rates is the bottleneck
    hour_factor: float
    trend: float
    recursive: bool = False
    plan: list[str] = field(default_factory=list)
    # Provenance of the throughput number, so the UI can be honest about it.
    measured: bool = False        # observed fills rather than the weekly average
    capture: float = 0.0          # share of flow judged reachable
    rivals: float = 0.0           # competing orders at the front of the queue

    @property
    def capital(self) -> float:
        return self.cost

    @property
    def label(self) -> str:
        out = f" -> {self.out_qty}x {self.result_name}"
        if self.recipe.a == self.recipe.b:
            return f"{self.units_a + self.units_b}x {self.a_name}{out}"
        return (f"{self.units_a}x {self.a_name} + "
                f"{self.units_b}x {self.b_name}{out}")


def _match(shard, patterns: list[str]) -> bool:
    if not patterns:
        return False
    low = {p.lower() for p in patterns}
    return (shard.id.lower() in low or shard.name.lower() in low
            or shard.tag.lower() in low)


def demand_trend(conn, tags: list[str], hours: int = 24) -> dict[str, float]:
    """MovingWeek now vs ``hours`` ago - is demand rising or falling?

    Because movingWeek is a trailing 7-day sum, this ratio is a clean read on
    momentum that does not suffer the cancellation problem the hour-of-day
    estimator has to work around.
    """
    out: dict[str, float] = {}
    if conn is None:
        return out
    rows = conn.execute(
        "SELECT tag, MAX(ts) FROM price_history GROUP BY tag").fetchall()
    latest = {t: ts for t, ts in rows}
    for tag in tags:
        ts = latest.get(tag)
        if not ts:
            continue
        then = conn.execute(
            "SELECT buy_mw, sell_mw FROM price_history"
            " WHERE tag=? AND ts <= ? ORDER BY ts DESC LIMIT 1",
            (tag, ts - hours * 3600)).fetchone()
        now = conn.execute(
            "SELECT buy_mw, sell_mw FROM price_history WHERE tag=? AND ts=?",
            (tag, ts)).fetchone()
        if not then or not now:
            continue
        old = (then[0] or 0) + (then[1] or 0)
        new = (now[0] or 0) + (now[1] or 0)
        if old <= 0:
            continue
        out[tag] = max(0.25, min(4.0, new / old))
    return out


def solve_input_costs(book: RecipeBook, products: dict[str, Product],
                      cfg: Config, refs: dict | None = None,
                      rounds: int = 6) -> tuple[dict[str, float], dict[str, Recipe | None]]:
    """Cheapest way to obtain one unit of each shard: buy it, or fuse it.

    A Bellman-Ford style relaxation over the recipe graph. Costs only ever
    decrease, so the iteration converges and cyclic recipes cannot manufacture
    free shards.
    """
    refs = refs or {}
    cost: dict[str, float] = {}
    via: dict[str, Recipe | None] = {}

    for sid, shard in book.shards.items():
        p = products.get(shard.tag)
        f = pricing.buy(p, 1, cfg, refs.get(shard.tag)) if p else None
        if f and f.ok and f.unit_price > 0:
            cost[sid] = f.unit_price
            via[sid] = None
        else:
            cost[sid] = float("inf")
            via[sid] = None

    for _ in range(rounds):
        changed = False
        for r in book.recipes:
            ca, cb = cost.get(r.a, float("inf")), cost.get(r.b, float("inf"))
            if ca == float("inf") or cb == float("inf"):
                continue
            fa = book.shards[r.a].fuse_amount
            fb = book.shards[r.b].fuse_amount
            unit = (ca * fa + cb * fb) / r.qty
            if unit < cost.get(r.result, float("inf")) * 0.999:
                cost[r.result] = unit
                via[r.result] = r
                changed = True
        if not changed:
            break
    return cost, via


def _explain_plan(sid: str, book: RecipeBook, via: dict[str, Recipe | None],
                  depth: int = 0, seen: set[str] | None = None) -> list[str]:
    seen = seen or set()
    if sid in seen or depth > 4:
        return []
    seen.add(sid)
    r = via.get(sid)
    if r is None:
        return [f"{'  ' * depth}buy {book.shards[sid].name}"]
    fa = book.shards[r.a].fuse_amount
    fb = book.shards[r.b].fuse_amount
    lines = [f"{'  ' * depth}fuse {fa}x {book.shards[r.a].name} + "
             f"{fb}x {book.shards[r.b].name} -> {r.qty}x {book.shards[sid].name}"]
    lines += _explain_plan(r.a, book, via, depth + 1, set(seen))
    lines += _explain_plan(r.b, book, via, depth + 1, set(seen))
    return lines


def _flow(d, side: str, product: Product, cfg: Config) -> float:
    """Units per hour on the given side: measured if observed, else weekly."""
    mw = product.buy_moving_week if side == "ask" else product.sell_moving_week
    if d is None:
        return max(0.0, mw / HOURS_PER_WEEK)
    return d.flow(side, mw)


def _capture(d_out, d_in, out_side: str, in_side: str,
             cfg: Config) -> tuple[float, float]:
    """(share of flow reachable, rivals at the front of the queue)."""
    resting_out = cfg.sell_mode == "SELL_OFFER"
    resting_in = cfg.buy_mode == "BUY_ORDER"

    shares, rivals = [], 0.0
    if resting_out and d_out is not None and d_out.measured:
        shares.append(d_out.queue_share(out_side))
        rivals = max(rivals, d_out.ask_competitors if out_side == "ask"
                     else d_out.bid_competitors)
    if resting_in and d_in is not None and d_in.measured:
        shares.append(d_in.queue_share(in_side))
        rivals = max(rivals, d_in.ask_competitors if in_side == "ask"
                     else d_in.bid_competitors)

    if not shares:
        # Crossing the spread on both legs, or no measurement yet: the
        # configured assumption is all we have.
        return cfg.capture_share, rivals
    # Both legs must clear, so the tighter queue governs.
    return min(shares), rivals


def evaluate(book: RecipeBook, products: dict[str, Product], cfg: Config,
             hour_profile: dict[int, float] | None = None,
             trends: dict[str, float] | None = None,
             hour: int | None = None,
             recursive: bool = False,
             refs: dict | None = None,
             demands: dict | None = None) -> list[Opportunity]:
    """Score every recipe. Returns opportunities sorted best-first."""
    hour_profile = hour_profile or {h: 1.0 for h in range(24)}
    trends = trends or {}
    refs = refs or {}
    demands = demands or {}
    from datetime import datetime, timezone
    hour = hour if hour is not None else datetime.now(timezone.utc).hour
    hf = hour_profile.get(hour, 1.0)
    tax = cfg.tax

    rec_cost, rec_via = ({}, {})
    if recursive:
        rec_cost, rec_via = solve_input_costs(book, products, cfg, refs)

    rarity_ok = {r.lower() for r in cfg.rarity_filter}
    type_ok = {t.lower() for t in cfg.type_filter}

    out: list[Opportunity] = []
    for r in book.recipes:
        sa, sb, sr = book.shards[r.a], book.shards[r.b], book.shards[r.result]

        if _match(sa, cfg.input_blacklist) or _match(sb, cfg.input_blacklist):
            continue
        if _match(sr, cfg.output_blacklist):
            continue
        if rarity_ok and sr.rarity.lower() not in rarity_ok:
            continue
        if type_ok and sr.type.lower() not in type_ok:
            continue

        pa, pb, pr = (products.get(sa.tag), products.get(sb.tag),
                      products.get(sr.tag))
        if pa is None or pb is None or pr is None:
            continue

        ua, ub, oq = sa.fuse_amount, sb.fuse_amount, r.qty
        same_shard = r.a == r.b

        if pr.buy_moving_week < cfg.min_moving_week:
            continue

        # --- cost side ---
        if recursive:
            ca, cb = rec_cost.get(r.a, float("inf")), rec_cost.get(r.b, float("inf"))
            if ca == float("inf") or cb == float("inf"):
                continue
            cost = ca * ua + cb * ub
            min_in = 0.0
        elif same_shard:
            # Tier-up fusions (Cod + Cod -> Dumpster Diver) need ua+ub units of
            # one shard. Costing two independent sweeps would take the same top
            # of book twice and understate the price on a thin ask side.
            fa = pricing.buy(pa, ua + ub, cfg, refs.get(sa.tag))
            if not fa.ok:
                continue
            cost = fa.total
            min_in = fa.minutes
        else:
            fa = pricing.buy(pa, ua, cfg, refs.get(sa.tag))
            fb = pricing.buy(pb, ub, cfg, refs.get(sb.tag))
            if not (fa.ok and fb.ok):
                continue
            cost = fa.total + fb.total
            min_in = max(fa.minutes, fb.minutes)

        if cost <= 0:
            continue
        if cfg.max_cost_per_fuse and cost > cfg.max_cost_per_fuse:
            continue

        # --- revenue side ---
        fs = pricing.sell(pr, oq, cfg, refs.get(sr.tag))
        if not fs.ok:
            continue
        revenue_net = fs.total * (1.0 - tax)
        profit = revenue_net - cost
        if profit < cfg.min_profit_per_fuse:
            continue

        # --- throughput ---
        # Which side of the book each leg draws on, which decides both the flow
        # we measure and whether we are queuing or crossing the spread.
        in_side = "ask" if cfg.buy_mode == "INSTA_BUY" else "bid"
        out_side = "ask" if cfg.sell_mode == "SELL_OFFER" else "bid"

        da, db, dr = demands.get(sa.tag), demands.get(sb.tag), demands.get(sr.tag)

        src_a = _flow(da, in_side, pa, cfg) * trends.get(sa.tag, 1.0)
        src_b = _flow(db, in_side, pb, cfg) * trends.get(sb.tag, 1.0)
        absorb = _flow(dr, out_side, pr, cfg) * trends.get(sr.tag, 1.0)

        if same_shard:
            # One shard supplies both slots, so it must cover ua+ub per fuse.
            rate_a = rate_b = src_a / (ua + ub)
        else:
            rate_a, rate_b = src_a / ua, src_b / ub
        rate_r = absorb / oq
        bottleneck = min(rate_a, rate_b, rate_r)
        limiter = ("input " + sa.name if bottleneck == rate_a else
                   "input " + sb.name if bottleneck == rate_b else
                   "output " + sr.name)

        # The share of that flow you can realistically take. When you rest an
        # order this is measured - it is one over the number of orders sharing
        # the front of the queue with you. When you cross the spread there is
        # nothing in the feed that reveals how many other players are doing the
        # same, so it stays the configured assumption.
        capture, rivals = _capture(dr, da, out_side, in_side, cfg)
        fuses_per_hour = bottleneck * capture
        if fuses_per_hour <= 0:
            continue
        measured = bool(dr and dr.measured and da and da.measured)

        if cfg.max_total_capital:
            fuses_per_hour = min(fuses_per_hour, cfg.max_total_capital / cost)

        coins_per_hour = profit * fuses_per_hour
        score = coins_per_hour * (hf ** cfg.hour_alpha)

        cycle = min_in + fs.minutes
        out.append(Opportunity(
            recipe=r, result_name=sr.name, a_name=sa.name, b_name=sb.name,
            units_a=ua, units_b=ub, out_qty=oq,
            cost=cost, revenue_net=revenue_net, profit=profit,
            roi=profit / cost,
            fuses_per_hour=fuses_per_hour, coins_per_hour=coins_per_hour,
            score=score, minutes_in=min_in, minutes_out=fs.minutes,
            cycle_minutes=cycle, limiter=limiter, hour_factor=hf,
            trend=trends.get(sr.tag, 1.0), recursive=recursive,
            plan=(_explain_plan(r.a, book, rec_via) +
                  _explain_plan(r.b, book, rec_via)) if recursive else [],
            measured=measured, capture=capture, rivals=rivals,
        ))

    out.sort(key=lambda o: o.score, reverse=True)
    return out


def dedupe_by_result(opps: list[Opportunity], per_result: int = 1) -> list[Opportunity]:
    """Keep only the best few recipes per output shard.

    Without this the top 10 is nearly always ten near-identical ID-fusion
    variants of the same shard, which is useless as a shortlist.
    """
    seen: dict[str, int] = {}
    out = []
    for o in opps:
        n = seen.get(o.recipe.result, 0)
        if n >= per_result:
            continue
        seen[o.recipe.result] = n + 1
        out.append(o)
    return out
