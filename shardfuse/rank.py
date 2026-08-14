"""CLI: what should I fuse right now?"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime, timezone

from . import bazaar, demand, hourly, recipes, reference, score, store
from .config import Config


def _coins(n: float) -> str:
    a = abs(n)
    if a >= 1e9:
        return f"{n / 1e9:.2f}b"
    if a >= 1e6:
        return f"{n / 1e6:.2f}m"
    if a >= 1e3:
        return f"{n / 1e3:.1f}k"
    return f"{n:.0f}"


def _table(opps, cfg: Config, title: str) -> None:
    print(f"\n{title}")
    print("-" * 118)
    print(f"{'#':>2}  {'FUSE':<44} {'COST':>9} {'PROFIT':>9} {'ROI':>6} "
          f"{'/HOUR':>8} {'COINS/HR':>10} {'CAPTURE':>9} {'BOTTLENECK':<16}")
    print("-" * 118)
    for i, o in enumerate(opps, 1):
        fuse = o.label
        if len(fuse) > 44:
            fuse = fuse[:41] + "..."
        # A dot marks throughput backed by observed fills rather than the
        # week-average fallback.
        mark = "·" if o.measured else " "
        cap = f"{o.capture * 100:.0f}%{mark}"
        print(f"{i:>2}  {fuse:<44} {_coins(o.cost):>9} {_coins(o.profit):>9} "
              f"{o.roi * 100:>5.0f}% {o.fuses_per_hour:>8.1f} "
              f"{_coins(o.coins_per_hour):>10} {cap:>9} {o.limiter[:16]:<16}")
    if not opps:
        print("  (nothing passed the filters - try lowering min_profit_per_fuse"
              " or min_moving_week)")


def _explain(o, cfg: Config) -> None:
    """Full arithmetic for one opportunity, so the number can be checked."""
    print(f"\nbreakdown: {o.label}")
    print("-" * 62)
    print(f"  inputs cost            {_coins(o.cost):>12}   ({cfg.buy_mode})")
    print(f"  gross after {cfg.tax * 100:.3f}% tax  {_coins(o.revenue_net):>12}   "
          f"({cfg.sell_mode})")
    print(f"  profit per fuse        {_coins(o.profit):>12}   "
          f"ROI {o.roi * 100:.0f}%")
    print()
    print(f"  throughput             {o.fuses_per_hour:>12.1f}   fuses/hour")
    print(f"  limited by             {o.limiter:>12}")
    print(f"  capture assumption     {cfg.capture_share:>11.0%}   "
          f"of observed market flow")
    if o.minutes_in or o.minutes_out:
        print(f"  est. fill in / out     "
              f"{o.minutes_in:>5.1f}m /{o.minutes_out:>5.1f}m")
    print()
    print(f"  coins per hour         {_coins(o.coins_per_hour):>12}")
    print(f"  hour factor            {o.hour_factor:>12.2f}x  "
          f"applied as ^{cfg.hour_alpha}")
    print(f"  demand trend (24h)     {o.trend:>12.2f}x")
    print(f"  final score            {_coins(o.score):>12}")
    if o.plan:
        print("\n  acquisition plan:")
        for line in o.plan:
            print("    " + line)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="shardfuse",
                                 description="Rank Hypixel shard fusions by coins per hour")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VAL",
                    help="override a config value (repeatable)")
    ap.add_argument("--buy", choices=["INSTA_BUY", "BUY_ORDER"])
    ap.add_argument("--sell", choices=["SELL_OFFER", "INSTA_SELL"])
    ap.add_argument("--top", type=int)
    ap.add_argument("--per-result", type=int, default=1,
                    help="max recipes shown per output shard (default 1)")
    ap.add_argument("--hour", type=int, help="score as if it were this UTC hour")
    ap.add_argument("--no-recursive", action="store_true")
    ap.add_argument("--plan", action="store_true",
                    help="print the fusion tree for recursive results")
    ap.add_argument("--explain", type=int, metavar="N", default=0,
                    help="print the full arithmetic for the top N results")
    ap.add_argument("--save-config", action="store_true")
    args = ap.parse_args(argv)

    cfg = Config.load()
    cfg.apply_overrides(args.set)
    if args.buy:
        cfg.buy_mode = args.buy
    if args.sell:
        cfg.sell_mode = args.sell
    if args.top:
        cfg.top_n = args.top

    mayor, perks = bazaar.current_mayor()
    cfg.mayor_aura_active = mayor.lower() == "aura"
    if args.save_config:
        cfg.save()

    book = recipes.load()
    products, updated = bazaar.fetch(set(book.tags))

    check = bazaar.verify_order_book_convention(products)
    if not check["ok"]:
        print(f"WARNING: order book convention check failed ({check}). "
              f"Profit signs may be inverted - not trading on this.",
              file=sys.stderr)
        return 2

    with store.connect() as conn:
        cov = store.coverage(conn)
        profile = hourly.smooth(hourly.hour_profile(conn))
        trends = score.demand_trend(conn, book.tags)
        refs = reference.load(conn, cfg.reference_hours)
        demands = demand.load(conn)
        dcov = demand.coverage_summary(conn)

    now = datetime.now(timezone.utc)
    hour = args.hour if args.hour is not None else now.hour

    print(f"shardfuse  |  {now:%Y-%m-%d %H:%M} UTC  |  mayor {mayor}  |  "
          f"tax {cfg.tax * 100:.3f}%")
    print(f"  {len(book):,} recipes over {len(book.shards)} shards, "
          f"{len(products)} priced")
    print(f"  history: {cov['rows']:,} rows / {cov['tags']} shards"
          + (f", {cov['book_snapshots']} book snapshots" if cov['book_snapshots'] else ""))
    resting = cfg.sell_mode == "SELL_OFFER" or cfg.buy_mode == "BUY_ORDER"
    capture_note = ("measured per shard from queue competition" if resting
                    else f"assumed {cfg.capture_share:.0%} (crossing the spread "
                         f"on both legs - not observable)")
    print(f"  mode: buy {cfg.buy_mode}, sell {cfg.sell_mode}")
    print(f"  capture: {capture_note}")
    print(f"  hour  {hourly.describe(profile)}")
    print(f"  now is {hour:02d}:00 UTC -> factor {profile.get(hour, 1.0):.2f}x "
          f"(applied as ^{cfg.hour_alpha})")

    trusted = sum(1 for r in refs.values() if r.trusted)
    print(f"  reference prices: {trusted}/{len(book.shards)} shards trusted "
          f"(guard: quotes believed to +{cfg.max_premium_over_reference:.0%} "
          f"over {cfg.reference_hours}h median)")

    measured_tags = sum(1 for d in demands.values() if d.measured)
    print(f"  demand: {measured_tags}/{len(book.shards)} shards measured from "
          f"observed fills ({dcov['per_shard_hours']:.1f}h observed per shard), "
          f"rest on weekly average")
    print(f"  rows: {dcov['rows_live']:,} live / {dcov['rows_cofl']:,} coflnet")

    flat = score.evaluate(book, products, cfg, profile, trends, hour,
                          recursive=False, refs=refs, demands=demands)
    flat_top = score.dedupe_by_result(flat, args.per_result)[:cfg.top_n]
    _table(flat_top, cfg,
           f"ONE-STEP  (buy both inputs off bazaar)   {len(flat):,} viable")
    for o in flat_top[:args.explain]:
        _explain(o, cfg)

    if not args.no_recursive:
        deep = score.evaluate(book, products, cfg, profile, trends, hour,
                              recursive=True, refs=refs, demands=demands)
        top = score.dedupe_by_result(deep, args.per_result)[:cfg.top_n]
        _table(top, cfg,
               f"RECURSIVE  (fuse inputs when cheaper)   {len(deep):,} viable")
        if args.plan and top:
            print("\nfusion tree for #1:")
            for line in top[0].plan:
                print("   " + line)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
