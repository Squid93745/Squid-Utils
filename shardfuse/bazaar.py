"""Live Hypixel Bazaar client.

One call to /v2/skyblock/bazaar returns every product, so pricing all 321 shards
costs exactly one request. No API key is required for this endpoint.

A note on Hypixel's naming, which is the single most common source of bugs in
bazaar tooling: the summaries are named for the action *you* would take, not for
the side of the book they sit on.

    buy_summary  -> the ASK side. Sell offers placed by other players.
                    You BUY from these. Best (lowest) price first.
    sell_summary -> the BID side. Buy orders placed by other players.
                    You SELL into these. Best (highest) price first.

``verify_order_book_convention`` re-checks this against live data rather than
trusting the comment, because a silent inversion here would flip the sign of
every profit number the engine produces.
"""

from __future__ import annotations

import json
import urllib.request
from dataclasses import dataclass, field

BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar"
ELECTION_URL = "https://api.hypixel.net/v2/resources/skyblock/election"
USER_AGENT = "shardfuse/0.1 (personal profit tool)"


def _get_json(url: str, timeout: float = 30.0) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


@dataclass(slots=True)
class Level:
    price: float
    amount: int
    orders: int


@dataclass(slots=True)
class Product:
    tag: str
    asks: list[Level] = field(default_factory=list)   # you buy from these
    bids: list[Level] = field(default_factory=list)   # you sell into these
    buy_volume: int = 0
    sell_volume: int = 0
    buy_moving_week: int = 0
    sell_moving_week: int = 0

    # --- top of book -----------------------------------------------------
    @property
    def insta_buy(self) -> float | None:
        """Price you pay per unit to buy right now."""
        return self.asks[0].price if self.asks else None

    @property
    def insta_sell(self) -> float | None:
        """Price you receive per unit selling right now (pre-tax)."""
        return self.bids[0].price if self.bids else None

    @property
    def spread(self) -> float | None:
        if self.insta_buy is None or self.insta_sell is None:
            return None
        return self.insta_buy - self.insta_sell

    def depth_cost(self, units: int) -> tuple[float, float] | None:
        """Total cost and worst price to sweep ``units`` off the ask side.

        Returns None if the book is too thin to fill the order, which is itself
        a useful signal: a fusion you cannot actually source is not a flip.
        """
        remaining, total, worst = units, 0.0, 0.0
        for lvl in self.asks:
            take = min(remaining, lvl.amount)
            total += take * lvl.price
            worst = lvl.price
            remaining -= take
            if remaining <= 0:
                return total, worst
        return None

    def depth_revenue(self, units: int) -> tuple[float, float] | None:
        """Gross proceeds and worst price from dumping ``units`` into bids."""
        remaining, total, worst = units, 0.0, 0.0
        for lvl in self.bids:
            take = min(remaining, lvl.amount)
            total += take * lvl.price
            worst = lvl.price
            remaining -= take
            if remaining <= 0:
                return total, worst
        return None


def fetch(tags: set[str] | None = None) -> tuple[dict[str, Product], int]:
    """Fetch the live bazaar. Returns (products, lastUpdated_ms)."""
    raw = _get_json(BAZAAR_URL)
    if not raw.get("success"):
        raise RuntimeError(f"bazaar request failed: {raw.get('cause')}")

    out: dict[str, Product] = {}
    for tag, p in raw["products"].items():
        if tags is not None and tag not in tags:
            continue
        qs = p.get("quick_status", {})
        out[tag] = Product(
            tag=tag,
            asks=[Level(l["pricePerUnit"], int(l["amount"]), int(l["orders"]))
                  for l in p.get("buy_summary", [])],
            bids=[Level(l["pricePerUnit"], int(l["amount"]), int(l["orders"]))
                  for l in p.get("sell_summary", [])],
            buy_volume=int(qs.get("buyVolume", 0)),
            sell_volume=int(qs.get("sellVolume", 0)),
            buy_moving_week=int(qs.get("buyMovingWeek", 0)),
            sell_moving_week=int(qs.get("sellMovingWeek", 0)),
        )
    return out, int(raw.get("lastUpdated", 0))


def current_mayor() -> tuple[str, list[str]]:
    """Return (mayor_name, perk_names). Aura raises bazaar tax by 1%."""
    raw = _get_json(ELECTION_URL)
    mayor = raw.get("mayor", {}) or {}
    perks = [p.get("name", "") for p in mayor.get("perks", []) or []]
    return mayor.get("name", "Unknown"), perks


def verify_order_book_convention(products: dict[str, Product]) -> dict:
    """Empirically confirm which summary is which side of the book.

    If ``buy_summary`` really is the ask side, then for essentially every liquid
    product its top price must exceed the top of ``sell_summary`` (you always
    pay more to insta-buy than you receive to insta-sell), and each list must be
    sorted best-price-first from the taker's perspective.
    """
    checked = inverted = ask_sorted = bid_sorted = 0
    examples = []
    for p in products.values():
        if not p.asks or not p.bids:
            continue
        checked += 1
        if p.asks[0].price < p.bids[0].price:
            inverted += 1
            if len(examples) < 5:
                examples.append((p.tag, p.asks[0].price, p.bids[0].price))
        if all(p.asks[i].price <= p.asks[i + 1].price for i in range(len(p.asks) - 1)):
            ask_sorted += 1
        if all(p.bids[i].price >= p.bids[i + 1].price for i in range(len(p.bids) - 1)):
            bid_sorted += 1
    return {
        "checked": checked,
        "inverted": inverted,
        "asks_ascending": ask_sorted,
        "bids_descending": bid_sorted,
        "examples_inverted": examples,
        "ok": checked > 0 and inverted / checked < 0.02,
    }
