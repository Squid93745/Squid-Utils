"""Can we measure real traded volume from consecutive order-book snapshots?

The weekly counts (buyMovingWeek) are genuine traded volume, but they are a
trailing 7-day sum, so differencing them gives

    dMW(t) = flow(t-d, t] - flow(t-7d-d, t-7d]

- current flow contaminated by the same window a week earlier. That is fine over
a week, useless for "what is trading right now".

Order-book deltas should give the instantaneous answer directly: units that
vanish off the ask side were bought. The catch is cancellations, which look
identical to fills. This script checks whether the book-derived number tracks
the movingWeek-derived one closely enough to trust.
"""

from __future__ import annotations

import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from shardfuse import store


def books_at(conn, ts):
    """{tag: {'ask': {price: amount}, 'bid': {price: amount}}} for one snapshot."""
    out = defaultdict(lambda: {"ask": {}, "bid": {}})
    for tag, side, price, amount in conn.execute(
            "SELECT tag, side, price, amount FROM orderbook WHERE ts = ?", (ts,)):
        out[tag][side][price] = amount
    return out


def consumed(prev: dict, cur: dict, side: str) -> int:
    """Units that left the book, treating a rising best-ask as a full sweep."""
    if not prev or not cur:
        return 0
    if side == "ask":
        best_now = min(cur) if cur else float("inf")
        gone = sum(a for p, a in prev.items()
                   if p < best_now and p not in cur)
    else:
        best_now = max(cur) if cur else 0.0
        gone = sum(a for p, a in prev.items()
                   if p > best_now and p not in cur)
    shrunk = sum(prev[p] - cur[p] for p in prev
                 if p in cur and cur[p] < prev[p])
    return int(gone + shrunk)


def main() -> None:
    with store.connect() as conn:
        snaps = [r[0] for r in conn.execute(
            "SELECT DISTINCT ts FROM orderbook ORDER BY ts").fetchall()]
        print(f"{len(snaps)} snapshots, {snaps[-1] - snaps[0]}s span")
        if len(snaps) < 3:
            print("need more collector data")
            return

        mw = {}
        for tag, ts, buy_mw, sell_mw in conn.execute(
                "SELECT tag, ts, buy_mw, sell_mw FROM price_history WHERE ts >= ?",
                (snaps[0],)):
            mw[(tag, ts)] = (buy_mw, sell_mw)

        book_buys = defaultdict(int)     # units taken off asks
        book_sells = defaultdict(int)    # units taken off bids
        mw_buys = defaultdict(int)
        mw_sells = defaultdict(int)
        intervals = 0

        prev_books = books_at(conn, snaps[0])
        for ts in snaps[1:]:
            cur_books = books_at(conn, ts)
            intervals += 1
            for tag, cur in cur_books.items():
                prev = prev_books.get(tag)
                if not prev:
                    continue
                book_buys[tag] += consumed(prev["ask"], cur["ask"], "ask")
                book_sells[tag] += consumed(prev["bid"], cur["bid"], "bid")
            prev_books = cur_books

        # movingWeek change across the whole observed window
        for tag in book_buys:
            a = mw.get((tag, snaps[0]))
            b = mw.get((tag, snaps[-1]))
            if a and b:
                mw_buys[tag] = b[0] - a[0]
                mw_sells[tag] = b[1] - a[1]

        span_h = (snaps[-1] - snaps[0]) / 3600.0
        print(f"\nobserved window: {span_h:.2f}h over {intervals} intervals\n")

        rows = sorted(book_buys, key=lambda t: -book_buys[t])[:12]
        print(f"{'TAG':<26} {'book buys':>10} {'dMW buy':>9} "
              f"{'MW/168':>9} {'book/hr':>9}")
        print("-" * 68)
        for tag in rows:
            latest = mw.get((tag, snaps[-1]))
            weekly_rate = (latest[0] / 168.0) if latest else 0
            print(f"{tag:<26} {book_buys[tag]:>10,} {mw_buys[tag]:>9,} "
                  f"{weekly_rate:>9,.0f} {book_buys[tag]/span_h:>9,.0f}")

        # How close is the book-derived hourly rate to the weekly average rate?
        ratios = []
        for tag in book_buys:
            latest = mw.get((tag, snaps[-1]))
            if not latest or latest[0] <= 0:
                continue
            weekly_rate = latest[0] / 168.0
            if weekly_rate < 5:
                continue
            ratios.append((book_buys[tag] / span_h) / weekly_rate)
        ratios.sort()
        if ratios:
            n = len(ratios)
            print(f"\nbook-derived hourly rate / weekly-average rate, {n} shards")
            print(f"   median {ratios[n // 2]:.2f}x   "
                  f"p10 {ratios[n // 10]:.2f}x   p90 {ratios[(9 * n) // 10]:.2f}x")
            print("   (1.0 would mean the two measures agree exactly)")


if __name__ == "__main__":
    main()
