"""Relabel rows the live collector wrote, then derive flow from every snapshot.

Needed after running a collector build that predates the precision tiers: those
rows landed with the default 'cofl' label and no flow was computed, so the
engine silently fell back to weekly averages despite having high-resolution
books sitting in the database.

Safe to re-run; both steps are idempotent.
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from shardfuse import backfill, demand, store

if __name__ == "__main__":
    t0 = time.time()

    with store.connect() as conn:
        before = conn.execute(
            "SELECT SUM(src='live'), SUM(src='cofl') FROM price_history").fetchone()
        print(f"before: {before[0]:,} live / {before[1]:,} coflnet")

        # A price row that shares a timestamp with an order-book snapshot can
        # only have come from the local collector.
        cur = conn.execute(
            "UPDATE price_history SET src = 'live'"
            " WHERE src != 'live'"
            "   AND ts IN (SELECT DISTINCT ts FROM orderbook)")
        print(f"relabelled {cur.rowcount:,} rows as live")

        after = conn.execute(
            "SELECT SUM(src='live'), SUM(src='cofl') FROM price_history").fetchone()
        print(f"after:  {after[0]:,} live / {after[1]:,} coflnet")

    print("\nderiving flow from all stored order books...")
    f = backfill.rebuild_flow()
    print(f"  {f['snapshots']} snapshots -> {f['rows']:,} flow rows")

    with store.connect() as conn:
        d = demand.coverage_summary(conn)
    print(f"  measured: {d['tags']} shards, "
          f"{d['per_shard_hours']:.1f}h observed per shard")
    print(f"\ndone in {time.time() - t0:.0f}s")
