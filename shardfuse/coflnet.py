"""Coflnet SkyCofl client - historical bazaar data, no API key required.

Endpoints used:
    /api/bazaar/{tag}/history?start=&end=   5-minute resolution, any date range
    /api/bazaar/{tag}/snapshot?timestamp=   full order book at a point in time

Documented limits are 30 requests / 10s and 100 requests / 60s per IP. The
limiter below is deliberately a little under that; a 429 storm costs far more
time than pacing does.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone

BASE = "https://sky.coflnet.com/api/bazaar"
USER_AGENT = "shardfuse/0.1 (personal profit tool)"


class RateLimiter:
    """Sliding-window limiter enforcing several windows at once."""

    def __init__(self, windows: tuple[tuple[int, float], ...] = ((25, 10.0), (90, 60.0))):
        self.windows = windows
        self.hits: deque[float] = deque()
        self.lock = threading.Lock()

    def acquire(self) -> None:
        while True:
            with self.lock:
                now = time.monotonic()
                longest = max(w for _, w in self.windows)
                while self.hits and now - self.hits[0] > longest:
                    self.hits.popleft()
                wait = 0.0
                for limit, window in self.windows:
                    recent = [h for h in self.hits if now - h <= window]
                    if len(recent) >= limit:
                        wait = max(wait, window - (now - recent[-limit]) + 0.05)
                if wait <= 0:
                    self.hits.append(now)
                    return
            time.sleep(wait)


_limiter = RateLimiter()


def _get(url: str, retries: int = 4, timeout: float = 45.0):
    last: Exception | None = None
    for attempt in range(retries):
        _limiter.acquire()
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 404:
                return None
            time.sleep(min(30.0, 2.0 * (2 ** attempt)))
        except Exception as e:  # network hiccup, timeout
            last = e
            time.sleep(min(15.0, 1.5 * (2 ** attempt)))
    raise RuntimeError(f"giving up on {url}: {last}")


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_ts(s: str) -> int:
    """Coflnet timestamps are naive UTC ISO strings, sometimes with millis."""
    s = s.rstrip("Z")
    if "." in s:
        s = s.split(".")[0]
    return int(datetime.strptime(s, "%Y-%m-%dT%H:%M:%S")
               .replace(tzinfo=timezone.utc).timestamp())


@dataclass(slots=True)
class HistPoint:
    ts: int
    buy: float          # insta-buy price (ask side)
    sell: float         # insta-sell price (bid side)
    buy_volume: int
    sell_volume: int
    buy_moving_week: int
    sell_moving_week: int


def history(tag: str, start: datetime, end: datetime) -> list[HistPoint]:
    url = f"{BASE}/{tag}/history?start={_iso(start)}&end={_iso(end)}"
    raw = _get(url)
    if not raw:
        return []
    pts: dict[int, HistPoint] = {}
    for r in raw:
        try:
            ts = parse_ts(r["timestamp"])
        except (KeyError, ValueError):
            continue
        # The feed occasionally emits two rows for the same 5-minute bucket;
        # last one wins.
        pts[ts] = HistPoint(
            ts=ts,
            buy=float(r.get("buy") or 0.0),
            sell=float(r.get("sell") or 0.0),
            buy_volume=int(r.get("buyVolume") or 0),
            sell_volume=int(r.get("sellVolume") or 0),
            buy_moving_week=int(r.get("buyMovingWeek") or 0),
            sell_moving_week=int(r.get("sellMovingWeek") or 0),
        )
    return sorted(pts.values(), key=lambda p: p.ts)


def snapshot(tag: str, when: datetime | None = None) -> dict | None:
    """Full order book at a point in time (or now)."""
    url = f"{BASE}/{tag}/snapshot"
    if when is not None:
        url += f"?timestamp={_iso(when)}"
    return _get(url)
