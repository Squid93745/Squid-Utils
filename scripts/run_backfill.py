"""Backfill all shard price history from the Aug 4 2026 update to now."""
import sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from shardfuse import backfill, store

if __name__ == "__main__":
    t0 = time.time()
    print("backfilling from", backfill.EPOCH.isoformat(), flush=True)
    stats = backfill.backfill(workers=4)
    print(f"\ndone in {time.time() - t0:.0f}s")
    print(f"  tags with data : {stats['tags']}")
    print(f"  rows inserted  : {stats['rows']:,}")
    print(f"  empty tags     : {len(stats['empty'])} {stats['empty'][:10]}")
    print(f"  failed tags    : {len(stats['failed'])} {stats['failed'][:5]}")
    with store.connect() as conn:
        print("  coverage:", store.coverage(conn))
