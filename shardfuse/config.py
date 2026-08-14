"""Tunable configuration for the profit engine.

Everything the ranking algorithm depends on lives here so that daily tuning is a
matter of editing one file (or passing --set on the CLI) rather than hunting
through the scoring code.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
DB_PATH = DATA / "shardfuse.db"
FUSION_DATA = DATA / "fusion-data.json"
FUSION_PROPS = DATA / "fusion-properties.json"
CONFIG_PATH = DATA / "config.json"

# Bazaar tax, as a fraction of gross sale value.
#   1.25%  base
#   -0.125% per Bazaar Flipper level (max level 2, so -0.25%)
#   -0.125% community upgrade
#   +1.00% while Mayor Aura is in office
BASE_TAX = 0.0125
FLIPPER_STEP = 0.00125
COMMUNITY_STEP = 0.00125
AURA_SURCHARGE = 0.01

# A bazaar order must beat the current best by at least this much to take the
# top of the book.
TICK = 0.1

HOURS_PER_WEEK = 168.0


@dataclass
class Config:
    # --- fees -------------------------------------------------------------
    bazaar_flipper_level: int = 2       # 0-2, from the Community Shop
    community_tax_upgrade: bool = True
    mayor_aura_active: bool = False     # auto-detected by refresh_mayor()

    # --- how you trade ----------------------------------------------------
    buy_mode: str = "INSTA_BUY"         # INSTA_BUY | BUY_ORDER
    sell_mode: str = "SELL_OFFER"       # SELL_OFFER | INSTA_SELL

    # --- capital limits ---------------------------------------------------
    max_cost_per_fuse: float = 0.0      # 0 = unlimited
    max_total_capital: float = 0.0      # 0 = unlimited; caps units per cycle
    min_profit_per_fuse: float = 1000.0

    # --- market realism ---------------------------------------------------
    # Fraction of observed market flow you can realistically capture. You are
    # not the only person running a fusion flipper, and you cannot sit at the
    # bazaar 24/7. 0.20 means "I can take a fifth of the hourly flow".
    capture_share: float = 0.20
    # Ignore anything whose weekly flow is below this; thin markets look
    # spectacular on paper and cannot be executed.
    min_moving_week: int = 5000
    # Reject a fusion if executing it would move the price more than this
    # fraction through the order book.
    max_book_impact: float = 0.35

    # --- phantom-price defence -------------------------------------------
    # Top-of-book is not a price if it is one troll order. A quote is only
    # believed up to this much above (or below) the trailing median; beyond
    # that the median is used instead. Moray Eel's ask side was a lone order
    # at 1,000,000 against a real level near 242,000 - without this guard the
    # engine invents a 1.5m profit per fuse.
    max_premium_over_reference: float = 0.20
    # Distinct resting orders required before a side of the book is believed.
    min_book_orders: int = 3
    # Skip shards with no trustworthy price history at all.
    require_reference: bool = True
    reference_hours: int = 24

    # --- scoring weights --------------------------------------------------
    # score = coins_per_hour * (hour_factor ** hour_alpha)
    # hour_alpha < 1 deliberately keeps time-of-day less influential than raw
    # demand and profit, per design intent.
    hour_alpha: float = 0.5
    # Blend of instantaneous vs. weekly-average demand. 1.0 = trust only the
    # last hour, 0.0 = trust only the 7-day average.
    recency_blend: float = 0.35

    # --- buy-order modelling ---------------------------------------------
    # Reject buy-order plans whose estimated fill exceeds this many minutes.
    max_fill_minutes: float = 30.0
    # Assume this share of the opposing flow actually reaches your order
    # rather than being taken by orders placed after yours.
    queue_efficiency: float = 0.7

    # --- filters ----------------------------------------------------------
    input_blacklist: list[str] = field(default_factory=list)
    output_blacklist: list[str] = field(default_factory=list)
    rarity_filter: list[str] = field(default_factory=list)   # empty = all
    type_filter: list[str] = field(default_factory=list)     # empty = all

    # --- output -----------------------------------------------------------
    top_n: int = 10

    @property
    def tax(self) -> float:
        """Effective bazaar sales tax as a fraction."""
        t = BASE_TAX
        t -= FLIPPER_STEP * max(0, min(2, self.bazaar_flipper_level))
        if self.community_tax_upgrade:
            t -= COMMUNITY_STEP
        if self.mayor_aura_active:
            t += AURA_SURCHARGE
        return max(0.0, t)

    # --- persistence ------------------------------------------------------
    def save(self, path: Path = CONFIG_PATH) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")

    @classmethod
    def load(cls, path: Path = CONFIG_PATH) -> "Config":
        if not path.exists():
            return cls()
        raw = json.loads(path.read_text(encoding="utf-8"))
        known = {f for f in cls.__dataclass_fields__}
        return cls(**{k: v for k, v in raw.items() if k in known})

    def apply_overrides(self, pairs: list[str]) -> None:
        """Apply CLI overrides of the form key=value."""
        for pair in pairs:
            key, _, value = pair.partition("=")
            key = key.strip()
            if key not in self.__dataclass_fields__:
                raise SystemExit(f"unknown config key: {key}")
            current = getattr(self, key)
            if isinstance(current, bool):
                parsed = value.strip().lower() in ("1", "true", "yes", "on")
            elif isinstance(current, int) and not isinstance(current, bool):
                parsed = int(value)
            elif isinstance(current, float):
                parsed = float(value)
            elif isinstance(current, list):
                parsed = [v.strip() for v in value.split(",") if v.strip()]
            else:
                parsed = value
            setattr(self, key, parsed)
