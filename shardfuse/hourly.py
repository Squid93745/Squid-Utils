"""Time-of-day activity factor.

Why this is not simply "sum the volume in each hour": the only flow-like field
the historical feed exposes is ``buyMovingWeek``, a *trailing 7-day sum*. So

    dMW(day, h) = flow(day, h) - flow(day - 7, h)

and if you average that by hour-of-day you get zero, because the term being
dropped is the same hour a week earlier. Plenty of tools get this wrong and end
up with a flat or noise-shaped curve they then trust.

The way out is to assume the flow separates into a daily level and an hourly
shape, flow(day, h) = D(day) * G(h). Then

    dMW(day, h) = G(h) * (D(day) - D(day - 7))

and the second factor does not depend on h, so

    mean |dMW| over days at hour h  is proportional to  G(h)

which recovers the shape up to a scale we normalise away. The assumption is
that the shape of a day is stable week to week even as its overall level moves
- reasonable for "when are people online", which is what we are after.

This estimator is honest about what it is: a proxy. Once the live collector has
run for a while, ``validate_against_live`` compares it to directly measured
order-book churn.
"""

from __future__ import annotations

import math
from collections import defaultdict
from datetime import datetime, timezone

from . import store

MIN_SAMPLES_PER_HOUR = 8


def _bucket(ts: int) -> int:
    return datetime.fromtimestamp(ts, timezone.utc).hour


def hour_profile(conn, tags: list[str] | None = None,
                 min_gap: int = 120, max_gap: int = 3900) -> dict[int, float]:
    """Relative activity by UTC hour, normalised so the mean is 1.0.

    ``min_gap``/``max_gap`` bound the spacing between the two samples used for
    each difference, so that gaps in collection do not turn into fake spikes.
    The upper bound is generous because Coflnet downsamples long backfill ranges
    to roughly 20-minute spacing, while the live collector runs at 60s.
    """
    where, params = "", []
    if tags:
        where = f" WHERE tag IN ({','.join('?' * len(tags))})"
        params = list(tags)

    rows = conn.execute(
        f"SELECT tag, ts, buy_mw, sell_mw FROM price_history{where} ORDER BY tag, ts",
        params).fetchall()

    sums: dict[int, float] = defaultdict(float)
    counts: dict[int, int] = defaultdict(int)

    prev_tag = None
    prev_ts = prev_buy = prev_sell = None
    for tag, ts, buy_mw, sell_mw in rows:
        if tag != prev_tag:
            prev_tag, prev_ts, prev_buy, prev_sell = tag, ts, buy_mw, sell_mw
            continue
        gap = ts - prev_ts
        if min_gap <= gap <= max_gap and prev_buy is not None and buy_mw is not None:
            # Normalise the difference by the gap so uneven spacing does not
            # bias one hour over another.
            delta = (abs(buy_mw - prev_buy) + abs((sell_mw or 0) - (prev_sell or 0)))
            per_hour = delta / (gap / 3600.0)
            h = _bucket(ts)
            sums[h] += per_hour
            counts[h] += 1
        prev_ts, prev_buy, prev_sell = ts, buy_mw, sell_mw

    means = {h: sums[h] / counts[h] for h in range(24)
             if counts.get(h, 0) >= MIN_SAMPLES_PER_HOUR}
    if len(means) < 12:
        return {h: 1.0 for h in range(24)}

    avg = sum(means.values()) / len(means)
    if avg <= 0:
        return {h: 1.0 for h in range(24)}

    profile = {h: means.get(h, avg) / avg for h in range(24)}
    # Clamp: a 3x hour is almost certainly a data artefact, not player behaviour.
    return {h: min(3.0, max(0.33, v)) for h, v in profile.items()}


def smooth(profile: dict[int, float], window: int = 3) -> dict[int, float]:
    """Circular moving average - adjacent hours should not jump wildly."""
    half = window // 2
    out = {}
    for h in range(24):
        vals = [profile[(h + d) % 24] for d in range(-half, half + 1)]
        out[h] = sum(vals) / len(vals)
    return out


def current_factor(profile: dict[int, float], now: datetime | None = None) -> float:
    now = now or datetime.now(timezone.utc)
    return profile.get(now.hour, 1.0)


def describe(profile: dict[int, float]) -> str:
    """A one-line sparkline of the day, for the CLI."""
    blocks = "▁▂▃▄▅▆▇█"
    lo, hi = min(profile.values()), max(profile.values())
    span = (hi - lo) or 1.0
    bar = "".join(blocks[min(7, int((profile[h] - lo) / span * 7.99))]
                  for h in range(24))
    peak = max(profile, key=profile.get)
    trough = min(profile, key=profile.get)
    return (f"{bar}  peak {peak:02d}:00 UTC ({profile[peak]:.2f}x), "
            f"quiet {trough:02d}:00 UTC ({profile[trough]:.2f}x)")


def validate_against_live(conn, hours: int = 24) -> dict:
    """Cross-check the proxy against directly observed order-book churn.

    Only meaningful once the live collector has been running; until then it
    reports how much data is still needed.
    """
    rows = conn.execute(
        "SELECT COUNT(DISTINCT ts) FROM orderbook").fetchone()
    snapshots = rows[0] or 0
    if snapshots < 60:
        return {"ready": False, "snapshots": snapshots,
                "note": "need ~60 collector polls before this means anything"}

    proxy = smooth(hour_profile(conn))
    churn: dict[int, float] = defaultdict(float)
    counts: dict[int, int] = defaultdict(int)
    prev = {}
    for tag, ts, amount in conn.execute(
            "SELECT tag, ts, amount FROM orderbook WHERE side='ask' AND lvl=0"
            " ORDER BY tag, ts"):
        key = tag
        if key in prev:
            p_ts, p_amt = prev[key]
            gap = ts - p_ts
            if 30 <= gap <= 300:
                churn[_bucket(ts)] += abs(amount - p_amt) / (gap / 3600.0)
                counts[_bucket(ts)] += 1
        prev[key] = (ts, amount)

    observed = {h: churn[h] / counts[h] for h in churn if counts[h] >= 5}
    if len(observed) < 6:
        return {"ready": False, "snapshots": snapshots,
                "note": "collector has not covered enough distinct hours yet"}
    avg = sum(observed.values()) / len(observed)
    observed = {h: v / avg for h, v in observed.items()}

    pairs = [(proxy[h], observed[h]) for h in observed]
    n = len(pairs)
    mx = sum(p[0] for p in pairs) / n
    my = sum(p[1] for p in pairs) / n
    cov = sum((x - mx) * (y - my) for x, y in pairs)
    vx = math.sqrt(sum((x - mx) ** 2 for x, _ in pairs))
    vy = math.sqrt(sum((y - my) ** 2 for _, y in pairs))
    corr = cov / (vx * vy) if vx and vy else 0.0
    return {"ready": True, "snapshots": snapshots, "hours_compared": n,
            "correlation": corr}
