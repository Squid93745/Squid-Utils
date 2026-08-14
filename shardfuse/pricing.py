"""Execution model: what a trade actually costs and how long it takes.

The distinction that matters for ranking is between the price you *see* and the
price you *get*. Sweeping 5 units off a thin ask book can cost far more than the
top-of-book quote suggests, and a buy order priced one tick above the best bid
only fills as fast as people insta-sell into it.
"""

from __future__ import annotations

from dataclasses import dataclass

from .bazaar import Product
from .config import HOURS_PER_WEEK, TICK, Config


@dataclass(slots=True)
class Fill:
    """One side of a trade, fully costed."""
    ok: bool
    unit_price: float        # effective price per unit, pre-tax
    total: float             # total coins in (cost) or out (gross revenue)
    worst_price: float       # deepest level touched, 0 for resting orders
    minutes: float           # expected time to complete; 0 for instant
    impact: float            # fraction the price moved through the book
    reason: str = ""


def _flow_per_hour(moving_week: int) -> float:
    return max(0.0, moving_week / HOURS_PER_WEEK)


def _order_count(levels) -> int:
    return sum(l.orders for l in levels)


def buy(product: Product, units: int, cfg: Config, ref=None) -> Fill:
    """Cost to acquire ``units`` of a shard."""
    if units <= 0:
        return Fill(True, 0.0, 0.0, 0.0, 0.0, 0.0)

    if cfg.require_reference and (ref is None or not ref.trusted):
        return Fill(False, 0, 0, 0, 0, 0, "no trusted price history")

    if cfg.buy_mode == "INSTA_BUY":
        if not product.asks:
            return Fill(False, 0, 0, 0, 0, 0, "no sell offers to buy from")
        if _order_count(product.asks) < cfg.min_book_orders:
            return Fill(False, 0, 0, 0, 0, 0, "ask book too shallow to trust")
        swept = product.depth_cost(units)
        if swept is None:
            return Fill(False, 0, 0, 0, 0, 0, "ask book too thin")
        total, worst = swept
        top = product.asks[0].price
        impact = (worst - top) / top if top > 0 else 0.0
        if impact > cfg.max_book_impact:
            return Fill(False, total / units, total, worst, 0.0, impact,
                        f"buying moves price {impact:.0%}")
        return Fill(True, total / units, total, worst, 0.0, impact)

    # BUY_ORDER: sit one tick above the best bid and wait for insta-sellers.
    if not product.bids:
        return Fill(False, 0, 0, 0, 0, 0, "no buy orders to price against")
    price = product.bids[0].price + TICK
    # A best bid far below where the shard has actually been trading is not a
    # price you will get filled at. Goldolot's book showed a 56.9k best bid
    # against a 104.6k trailing median - budgeting at 56.9k would understate
    # cost by nearly half. Assume the realistic level, not the hopeful one.
    if ref is not None and ref.trusted:
        floor = ref.bid * (1.0 - cfg.max_premium_over_reference)
        price = max(price, floor)
    rate = _flow_per_hour(product.sell_moving_week) * cfg.queue_efficiency
    if rate <= 0:
        return Fill(False, price, price * units, 0.0, float("inf"), 0.0,
                    "nobody insta-sells this")
    minutes = units / rate * 60.0
    if minutes > cfg.max_fill_minutes:
        return Fill(False, price, price * units, 0.0, minutes, 0.0,
                    f"buy order needs {minutes:.0f}m to fill")
    return Fill(True, price, price * units, 0.0, minutes, 0.0)


def sell(product: Product, units: int, cfg: Config, ref=None) -> Fill:
    """Gross proceeds from disposing of ``units`` (tax applied by caller)."""
    if units <= 0:
        return Fill(True, 0.0, 0.0, 0.0, 0.0, 0.0)

    if cfg.require_reference and (ref is None or not ref.trusted):
        return Fill(False, 0, 0, 0, 0, 0, "no trusted price history")

    if cfg.sell_mode == "INSTA_SELL":
        # Bids are the trustworthy side of the book: a buy order escrows real
        # coins, so an absurd bid is expensive to fake, whereas an absurd sell
        # offer only requires owning the item.
        if not product.bids:
            return Fill(False, 0, 0, 0, 0, 0, "no buy orders to sell into")
        swept = product.depth_revenue(units)
        if swept is None:
            return Fill(False, 0, 0, 0, 0, 0, "bid book too thin")
        total, worst = swept
        top = product.bids[0].price
        impact = (top - worst) / top if top > 0 else 0.0
        if impact > cfg.max_book_impact:
            return Fill(False, total / units, total, worst, 0.0, impact,
                        f"selling moves price {impact:.0%}")
        return Fill(True, total / units, total, worst, 0.0, impact)

    # SELL_OFFER: undercut the best ask by a tick and wait for insta-buyers.
    if not product.asks:
        return Fill(False, 0, 0, 0, 0, 0, "no sell offers to price against")
    if _order_count(product.asks) < cfg.min_book_orders:
        return Fill(False, 0, 0, 0, 0, 0, "ask book too shallow to trust")
    price = max(TICK, product.asks[0].price - TICK)
    # The phantom-price guard. Undercutting a lone 1,000,000 coin order does
    # not mean anyone buys at 999,999.9.
    if ref is not None and ref.trusted:
        ceiling = ref.ask * (1.0 + cfg.max_premium_over_reference)
        price = min(price, ceiling)
    rate = _flow_per_hour(product.buy_moving_week) * cfg.queue_efficiency
    if rate <= 0:
        return Fill(False, price, price * units, 0.0, float("inf"), 0.0,
                    "nobody insta-buys this")
    minutes = units / rate * 60.0
    if minutes > cfg.max_fill_minutes:
        return Fill(False, price, price * units, 0.0, minutes, 0.0,
                    f"sell offer needs {minutes:.0f}m to fill")
    return Fill(True, price, price * units, 0.0, minutes, 0.0)


def book_turnover(product: Product, side: str) -> float:
    """How many times per hour the resting book on ``side`` gets consumed.

    A proxy for "how often does an order here actually fill". High turnover
    means your order is one of many that clears each hour; low turnover means
    you are joining a queue that barely moves.
    """
    if side == "bid":
        resting, flow = product.buy_volume, _flow_per_hour(product.sell_moving_week)
    else:
        resting, flow = product.sell_volume, _flow_per_hour(product.buy_moving_week)
    return flow / max(1.0, float(resting))


def sourcing_rate(product: Product, cfg: Config) -> float:
    """Units per hour of this shard you can realistically acquire."""
    mw = (product.buy_moving_week if cfg.buy_mode == "INSTA_BUY"
          else product.sell_moving_week)
    return _flow_per_hour(mw)


def absorption_rate(product: Product, cfg: Config) -> float:
    """Units per hour of this shard the market can take off you."""
    mw = (product.buy_moving_week if cfg.sell_mode == "SELL_OFFER"
          else product.sell_moving_week)
    return _flow_per_hour(mw)
