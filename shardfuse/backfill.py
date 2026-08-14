"""Backfill price history from Coflnet, and poll live data forward.

The Aug 4 2026 shard update is the natural epoch: recipes and the shard roster
changed then, so older data describes a different game.
"""

from __future__ import annotations

import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

from . import bazaar, coflnet, recipes, store

EPOCH = datetime(2026, 8, 4, 0, 0, tzinfo=timezone.utc)
# Coflnet's resolution depends on the width of the range requested, not on a
# parameter: a 2-day window comes back at roughly 2-hour spacing, while a
# 6-hour window comes back at roughly 3 minutes. The wide chunk is fine for a
# first sweep of a week of history; gap filling uses the narrow one because the
# whole point there is to raise resolution.
CHUNK = timedelta(days=2)
GAP_CHUNK = timedelta(hours=6)


def backfill(since: datetime = EPOCH, until: datetime | None = None,
             workers: int = 4, only: list[str] | None = None) -> dict:
    until = until or datetime.now(timezone.utc)
    book = recipes.load()
    tags = only or [t for t in book.tags]

    stats = {"tags": 0, "rows": 0, "empty": [], "failed": []}
    lock_msgs: list[str] = []

    def work(tag: str):
        pts = []
        cur = since
        while cur < until:
            stop = min(cur + CHUNK, until)
            try:
                pts.extend(coflnet.history(tag, cur, stop))
            except Exception as e:
                return tag, None, str(e)
            cur = stop
        return tag, pts, None

    with store.connect() as conn, ThreadPoolExecutor(max_workers=workers) as pool:
        for i, (tag, pts, err) in enumerate(pool.map(work, tags), 1):
            if err:
                stats["failed"].append((tag, err))
            elif not pts:
                stats["empty"].append(tag)
            else:
                # Deduplicate across chunk boundaries.
                uniq = {p.ts: p for p in pts}
                stats["rows"] += store.insert_history(
                    conn, tag, uniq.values(), store.SRC_COFL)
                stats["tags"] += 1
            if i % 20 == 0:
                conn.commit()
                print(f"  {i}/{len(tags)} tags, {stats['rows']:,} rows",
                      file=sys.stderr, flush=True)
        store.set_meta(conn, "last_backfill", int(time.time()))
    return stats


# A resting order within this fraction of the best price is competing with you
# for the same flow.
NEAR_TOP = 0.005


def _levels(conn, ts: int):
    """{tag: {'ask': {price: amount}, 'bid': {...}}} for one snapshot."""
    from collections import defaultdict
    out = defaultdict(lambda: {"ask": {}, "bid": {}})
    for tag, side, price, amount in conn.execute(
            "SELECT tag, side, price, amount FROM orderbook WHERE ts = ?", (ts,)):
        out[tag][side][price] = amount
    return out


def _consumed(prev: dict, cur: dict, side: str) -> int:
    """Units that left one side of the book between two snapshots.

    A level that vanished from beyond the new best price was swept; a level
    still present but smaller was partly filled. Cancellations are
    indistinguishable from fills here, which is why this is validated against
    the independent weekly counts rather than trusted blindly - see
    scripts/probe_flow.py, which found them agreeing to a median of 0.96x.
    """
    if not prev or not cur:
        return 0
    if side == "ask":
        best_now = min(cur)
        gone = sum(a for p, a in prev.items() if p < best_now and p not in cur)
    else:
        best_now = max(cur)
        gone = sum(a for p, a in prev.items() if p > best_now and p not in cur)
    shrunk = sum(prev[p] - cur[p] for p in prev if p in cur and cur[p] < prev[p])
    return int(max(0, gone + shrunk))


def _near_top(product, side: str) -> tuple[int, int]:
    """(resting units, competing orders) within NEAR_TOP of the best price."""
    levels = product.asks if side == "ask" else product.bids
    if not levels:
        return 0, 0
    best = levels[0].price
    units = orders = 0
    for l in levels:
        if best <= 0 or abs(l.price - best) / best > NEAR_TOP:
            break
        units += l.amount
        orders += l.orders
    return units, orders


def poll_once() -> dict:
    """Snapshot live prices + order books, and derive observed trade flow."""
    book = recipes.load()
    tags = set(book.tags)
    prods, updated = bazaar.fetch(tags)
    ts = updated // 1000 if updated else int(time.time())

    n_books = 0
    n_flow = 0
    with store.connect() as conn:
        prev_ts_row = conn.execute(
            "SELECT MAX(ts) FROM orderbook WHERE ts < ?", (ts,)).fetchone()
        prev_ts = prev_ts_row[0] if prev_ts_row else None
        prev = _levels(conn, prev_ts) if prev_ts else {}

        rows = []
        flow_rows = []
        for tag, p in prods.items():
            if p.insta_buy is None and p.insta_sell is None:
                continue
            rows.append((tag, ts, p.insta_buy or 0.0, p.insta_sell or 0.0,
                         p.buy_volume, p.sell_volume,
                         p.buy_moving_week, p.sell_moving_week, store.SRC_LIVE))
            n_books += store.insert_book(conn, tag, ts, p.asks, p.bids)

            # Only measure flow across a plausible interval; a long gap means
            # the collector was stopped and the delta is meaningless.
            if prev_ts and 20 <= (ts - prev_ts) <= 600 and tag in prev:
                cur = {"ask": {l.price: l.amount for l in p.asks[:30]},
                       "bid": {l.price: l.amount for l in p.bids[:30]}}
                ask_units, ask_orders = _near_top(p, "ask")
                bid_units, bid_orders = _near_top(p, "bid")
                flow_rows.append((
                    tag, ts, ts - prev_ts,
                    _consumed(prev[tag]["ask"], cur["ask"], "ask"),
                    _consumed(prev[tag]["bid"], cur["bid"], "bid"),
                    ask_units, bid_units, ask_orders, bid_orders))

        conn.executemany(
            "INSERT INTO price_history"
            " (tag, ts, buy, sell, buy_vol, sell_vol, buy_mw, sell_mw, src)"
            " VALUES (?,?,?,?,?,?,?,?,?)"
            " ON CONFLICT(tag, ts) DO UPDATE SET"
            "   buy=excluded.buy, sell=excluded.sell,"
            "   buy_vol=excluded.buy_vol, sell_vol=excluded.sell_vol,"
            "   buy_mw=excluded.buy_mw, sell_mw=excluded.sell_mw,"
            "   src=excluded.src", rows)
        if flow_rows:
            n_flow = store.insert_flow(conn, flow_rows)
        store.set_meta(conn, "last_poll", ts)
    return {"ts": ts, "products": len(rows), "book_rows": n_books,
            "flow_rows": n_flow}


def rebuild_flow(min_secs: int = 20, max_secs: int = 600) -> dict:
    """Recompute observed flow from order-book snapshots already in the DB.

    Lets snapshots captured before the flow logic existed still count, and makes
    the derivation re-runnable if the estimator changes.
    """
    stats = {"snapshots": 0, "rows": 0}
    with store.connect() as conn:
        snaps = [r[0] for r in conn.execute(
            "SELECT DISTINCT ts FROM orderbook ORDER BY ts")]
        stats["snapshots"] = len(snaps)
        if len(snaps) < 2:
            return stats

        def full(ts):
            """{tag: {side: [(price, amount, orders), ...] in book order}}"""
            from collections import defaultdict
            out = defaultdict(lambda: {"ask": [], "bid": []})
            for tag, side, price, amount, orders in conn.execute(
                    "SELECT tag, side, price, amount, orders FROM orderbook"
                    " WHERE ts = ? ORDER BY tag, side, lvl", (ts,)):
                out[tag][side].append((price, amount, orders))
            return out

        prev = full(snaps[0])
        for ts in snaps[1:]:
            cur = full(ts)
            gap = ts - _prev_ts(snaps, ts)
            if not (min_secs <= gap <= max_secs):
                prev = cur
                continue
            rows = []
            for tag, sides in cur.items():
                p = prev.get(tag)
                if not p:
                    continue
                ask_prev = {x[0]: x[1] for x in p["ask"]}
                ask_cur = {x[0]: x[1] for x in sides["ask"]}
                bid_prev = {x[0]: x[1] for x in p["bid"]}
                bid_cur = {x[0]: x[1] for x in sides["bid"]}
                au, ao = _near_top_levels(sides["ask"])
                bu, bo = _near_top_levels(sides["bid"])
                rows.append((tag, ts, gap,
                             _consumed(ask_prev, ask_cur, "ask"),
                             _consumed(bid_prev, bid_cur, "bid"),
                             au, bu, ao, bo))
            if rows:
                stats["rows"] += store.insert_flow(conn, rows)
            prev = cur
    return stats


def _prev_ts(snaps: list[int], ts: int) -> int:
    i = snaps.index(ts)
    return snaps[i - 1] if i > 0 else ts


def _near_top_levels(levels) -> tuple[int, int]:
    """(units, orders) within NEAR_TOP of the best price, from stored rows."""
    if not levels:
        return 0, 0
    best = levels[0][0]
    units = orders = 0
    for price, amount, count in levels:
        if best <= 0 or abs(price - best) / best > NEAR_TOP:
            break
        units += amount
        orders += count
    return int(units), int(orders)


def find_gaps(conn, tag: str, since: datetime, until: datetime,
              min_gap: int = 3600) -> list[tuple[int, int]]:
    """Hours with no local data at all, merged into contiguous stretches.

    Deliberately bucketed by hour rather than measured between consecutive
    samples. Coflnet serves long ranges at roughly 20-minute spacing, so a
    pairwise test with any threshold under that flags ordinary spacing as a gap
    and re-requests the entire history - which is exactly what happened the
    first time this ran.
    """
    lo = int(since.timestamp()) // 3600
    hi = int(until.timestamp()) // 3600
    if hi <= lo:
        return []

    have = {r[0] for r in conn.execute(
        "SELECT DISTINCT ts / 3600 FROM price_history"
        " WHERE tag = ? AND ts >= ? AND ts <= ?",
        (tag, lo * 3600, hi * 3600 + 3599))}

    gaps: list[tuple[int, int]] = []
    run_start = None
    for h in range(lo, hi):
        if h in have:
            if run_start is not None:
                gaps.append((run_start * 3600, h * 3600))
                run_start = None
        elif run_start is None:
            run_start = h
    if run_start is not None:
        gaps.append((run_start * 3600, hi * 3600))

    return [(a, b) for a, b in gaps if b - a >= min_gap]


def fill_gaps(since: datetime = EPOCH, until: datetime | None = None,
              workers: int = 4, min_gap: int = 3600,
              dry_run: bool = False) -> dict:
    """Backfill only the stretches the local collector missed.

    Running this after the game has been closed for a while tops the database up
    from Coflnet without touching any of the higher-resolution rows the live
    collector wrote.
    """
    until = until or datetime.now(timezone.utc)
    book = recipes.load()

    with store.connect() as conn:
        plan = {tag: find_gaps(conn, tag, since, until, min_gap)
                for tag in book.tags}

    todo = {t: g for t, g in plan.items() if g}
    total_gaps = sum(len(g) for g in todo.values())
    missing_hours = sum((b - a) for g in todo.values() for a, b in g) / 3600.0
    stats = {"tags": len(todo), "gaps": total_gaps, "rows": 0,
             "missing_hours": missing_hours, "failed": []}
    if not todo or dry_run:
        return stats

    def work(item):
        tag, gaps = item
        pts = []
        for lo, hi in gaps:
            cur = datetime.fromtimestamp(lo, timezone.utc)
            stop = datetime.fromtimestamp(hi, timezone.utc)
            while cur < stop:
                nxt = min(cur + GAP_CHUNK, stop)
                try:
                    pts.extend(coflnet.history(tag, cur, nxt))
                except Exception as e:
                    return tag, None, str(e)
                cur = nxt
        return tag, pts, None

    with store.connect() as conn, ThreadPoolExecutor(max_workers=workers) as pool:
        for i, (tag, pts, err) in enumerate(pool.map(work, todo.items()), 1):
            if err:
                stats["failed"].append((tag, err))
            elif pts:
                uniq = {p.ts: p for p in pts}
                stats["rows"] += store.insert_history(
                    conn, tag, uniq.values(), store.SRC_COFL)
            if i % 25 == 0:
                conn.commit()
                print(f"  {i}/{len(todo)} tags, {stats['rows']:,} rows",
                      file=sys.stderr, flush=True)
        store.set_meta(conn, "last_gap_fill", int(time.time()))
    return stats


def collect(interval: float = 60.0, duration: float | None = None) -> None:
    """Run the live collector until interrupted.

    This is what gives us true order-book churn at a resolution Coflnet does not
    serve, which the buy-order fill model needs.
    """
    started = time.time()
    n = 0
    while True:
        try:
            r = poll_once()
            n += 1
            print(f"[{time.strftime('%H:%M:%S')}] poll #{n}: "
                  f"{r['products']} products, {r['book_rows']} book rows, "
                  f"{r['flow_rows']} flow", flush=True)
        except KeyboardInterrupt:
            raise
        except Exception as e:
            print(f"[{time.strftime('%H:%M:%S')}] poll failed: {e}", flush=True)
        if duration and time.time() - started >= duration:
            return
        time.sleep(interval)
