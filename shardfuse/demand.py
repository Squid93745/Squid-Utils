"""Demand from measurement rather than assumption.

Three numbers decide how many fusions an hour you can actually turn over, and
they differ in how solidly they are known:

1. **How much trades.** Directly measured. The live collector diffs consecutive
   order books, so units that left the ask side were bought and units that left
   the bid side were sold. Validated against the independent weekly counts at a
   median of 0.96x (scripts/probe_flow.py). Falls back to buyMovingWeek/168 for
   any shard the collector has not observed recently - that is a real count too,
   just averaged over a week and therefore blind to time of day.

2. **How much of it you can take when resting an order.** Also measured: count
   the competing orders sitting within half a percent of the best price. If nine
   other sell offers share the front of the queue with you, you get roughly a
   tenth of the insta-buys, not a fifth because a config slider said so.

3. **How much you can take when crossing the spread.** Not directly observable -
   nothing in the feed reveals how many other players are insta-buying the same
   shard. This one stays an assumption, and is labelled as such.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from .config import HOURS_PER_WEEK

# Below this many seconds of observation in the window, the measured rate is too
# noisy to prefer over the weekly average.
MIN_COVERAGE_SECONDS = 600


@dataclass(slots=True)
class Demand:
    tag: str
    bought_per_hour: float
    sold_per_hour: float
    coverage: float          # seconds of observation contributing
    ask_competitors: float   # resting orders near the best ask
    bid_competitors: float
    ask_depth: float
    bid_depth: float

    @property
    def measured(self) -> bool:
        return self.coverage >= MIN_COVERAGE_SECONDS

    def flow(self, side: str, moving_week: int) -> float:
        """Units per hour, measured when we can, weekly average otherwise."""
        if self.measured:
            return self.bought_per_hour if side == "ask" else self.sold_per_hour
        return max(0.0, moving_week / HOURS_PER_WEEK)

    def queue_share(self, side: str) -> float:
        """Your share of the flow when resting an order at the front.

        One over the number of orders you are queued alongside. Clamped at the
        top because being alone at the front does not mean you capture
        everything - somebody will undercut you within the minute.
        """
        rivals = self.ask_competitors if side == "ask" else self.bid_competitors
        return min(0.5, 1.0 / (1.0 + max(0.0, rivals)))


def load(conn, hours: float = 3.0) -> dict[str, Demand]:
    """Aggregate observed flow and competition over the trailing window."""
    cutoff = int(time.time()) - int(hours * 3600)
    rows = conn.execute(
        "SELECT tag,"
        "       SUM(bought), SUM(sold), SUM(secs),"
        "       AVG(ask_orders), AVG(bid_orders),"
        "       AVG(ask_depth), AVG(bid_depth)"
        " FROM flow WHERE ts >= ? GROUP BY tag", (cutoff,)).fetchall()

    out: dict[str, Demand] = {}
    for tag, bought, sold, secs, ao, bo, ad, bd in rows:
        secs = secs or 0
        if secs <= 0:
            continue
        per_hour = 3600.0 / secs
        out[tag] = Demand(
            tag=tag,
            bought_per_hour=(bought or 0) * per_hour,
            sold_per_hour=(sold or 0) * per_hour,
            coverage=float(secs),
            ask_competitors=float(ao or 0),
            bid_competitors=float(bo or 0),
            ask_depth=float(ad or 0),
            bid_depth=float(bd or 0),
        )
    return out


def coverage_summary(conn, hours: float = 3.0) -> dict:
    cutoff = int(time.time()) - int(hours * 3600)
    row = conn.execute(
        "SELECT COUNT(DISTINCT tag), COUNT(*), SUM(secs) FROM flow WHERE ts >= ?",
        (cutoff,)).fetchone()
    live, cofl = conn.execute(
        "SELECT SUM(src='live'), SUM(src='cofl') FROM price_history").fetchone()
    tags = row[0] or 0
    total_secs = row[2] or 0
    return {
        "tags": tags,
        "intervals": row[1] or 0,
        # Per shard, not summed across them - the summed figure reads like
        # days of history when it is really minutes times a few hundred shards.
        "per_shard_hours": (total_secs / tags / 3600.0) if tags else 0.0,
        "rows_live": live or 0,
        "rows_cofl": cofl or 0,
    }
