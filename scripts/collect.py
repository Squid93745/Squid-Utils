"""Live collector: poll the bazaar every 60s into the local DB.

Gives order-book depth at a resolution Coflnet does not serve, which is what the
buy-order fill model and the hour-of-day validation need. Safe to leave running;
Ctrl-C to stop.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from shardfuse import backfill

if __name__ == "__main__":
    interval = float(sys.argv[1]) if len(sys.argv) > 1 else 60.0
    print(f"collecting every {interval:.0f}s - Ctrl-C to stop", flush=True)
    try:
        backfill.collect(interval=interval)
    except KeyboardInterrupt:
        print("\nstopped")
