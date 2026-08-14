"""Top the database up after time offline, then re-derive everything.

Run this when you come back to the game (or on a schedule). It:

  1. rebuilds observed flow from any order-book snapshots not yet processed,
  2. finds stretches with no local data at all and fills only those from
     Coflnet, leaving every high-resolution row the live collector wrote alone,
  3. re-exports the tuned model so the mod picks it up on its next refresh.

The result is one series that is precise where you were online and merely
adequate where you were not.
"""

from __future__ import annotations

import argparse
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from shardfuse import backfill, demand, store

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--hours", type=float, default=72,
                    help="how far back to fill gaps (default 72)")
    ap.add_argument("--all", action="store_true",
                    help="fill back to the Aug 4 update; slow, one-off")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    since = (backfill.EPOCH if args.all
             else datetime.now(timezone.utc) - timedelta(hours=args.hours))
    t0 = time.time()

    print("1/3  deriving flow from stored order books...")
    f = backfill.rebuild_flow()
    print(f"     {f['snapshots']} snapshots -> {f['rows']:,} flow rows")

    print(f"2/3  filling gaps since {since:%Y-%m-%d %H:%M} UTC...")
    g = backfill.fill_gaps(since=since, dry_run=args.dry_run)
    print(f"     {g['gaps']} gaps across {g['tags']} shards, "
          f"{g['missing_hours']:.0f} shard-hours missing -> {g['rows']:,} rows")
    if g["failed"]:
        print(f"     {len(g['failed'])} failed: {g['failed'][:3]}")
    if args.dry_run:
        sys.exit(0)

    with store.connect() as conn:
        cov = store.coverage(conn)
        d = demand.coverage_summary(conn)
    print(f"\n     history : {cov['rows']:,} rows / {cov['tags']} shards")
    print(f"     precision: {d['rows_live']:,} live (60s) "
          f"/ {d['rows_cofl']:,} coflnet")
    print(f"     measured : {d['tags']} shards, "
          f"{d['per_shard_hours']:.1f}h observed per shard")
    print(f"     span    : "
          f"{datetime.fromtimestamp(cov['first_ts'], timezone.utc):%Y-%m-%d %H:%M} -> "
          f"{datetime.fromtimestamp(cov['last_ts'], timezone.utc):%Y-%m-%d %H:%M} UTC")

    print("\n3/3  exporting brain.json...")
    import subprocess
    subprocess.run([sys.executable,
                    str(Path(__file__).with_name("export_brain.py")), "--brain"],
                   check=False)
    print(f"\ndone in {time.time() - t0:.0f}s")
