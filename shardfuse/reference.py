"""Reference prices - the defence against phantom profit.

The order book alone is not trustworthy. Moray Eel's entire ask side was a
single 32-unit order at exactly 1,000,000 coins while the shard actually
changed hands around 242k. Price a sell offer off that top-of-book quote and
the engine reports a 1.5m profit per fuse that no one will ever pay.

So every quote gets checked against a reference built from the sampled history:
the median top-of-book over the last day. Medians are used rather than means
because a single absurd order should not move the yardstick.
"""

from __future__ import annotations

import time
from dataclasses import dataclass


@dataclass(slots=True)
class Reference:
    ask: float          # median insta-buy quote over the window
    bid: float          # median insta-sell quote over the window
    samples: int

    @property
    def trusted(self) -> bool:
        return self.samples >= 6 and self.ask > 0 and self.bid > 0


def _median(xs: list[float]) -> float:
    if not xs:
        return 0.0
    xs = sorted(xs)
    n = len(xs)
    return xs[n // 2] if n % 2 else (xs[n // 2 - 1] + xs[n // 2]) / 2.0


def load(conn, hours: int = 24) -> dict[str, Reference]:
    """Median top-of-book per shard over the trailing window."""
    cutoff = int(time.time()) - hours * 3600
    rows = conn.execute(
        "SELECT tag, buy, sell FROM price_history WHERE ts >= ? AND buy > 0",
        (cutoff,)).fetchall()

    buckets: dict[str, tuple[list[float], list[float]]] = {}
    for tag, buy, sell in rows:
        b = buckets.setdefault(tag, ([], []))
        b[0].append(buy)
        if sell and sell > 0:
            b[1].append(sell)

    return {tag: Reference(ask=_median(a), bid=_median(b), samples=len(a))
            for tag, (a, b) in buckets.items()}
