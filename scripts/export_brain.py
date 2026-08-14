"""Export the tuned model for the mod to consume.

Two artefacts:

  fusion.json  the recipe book, stripped to what scoring needs. Bundled into
               the jar at build time; only changes when Hypixel changes recipes.

  brain.json   the tuned parameters: hour-of-day profile, reference prices,
               config defaults. Written straight into the game's config
               directory so the mod picks up a re-tune on its next refresh
               without rebuilding the jar. This is what makes daily tuning
               cheap.

Usage:
    python scripts/export_brain.py            # write both, into mod + game
    python scripts/export_brain.py --brain    # brain.json only
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from shardfuse import demand, hourly, recipes, reference, score, store
from shardfuse.config import Config

ROOT = Path(__file__).resolve().parent.parent
MOD_ASSETS = ROOT / "mod" / "src" / "main" / "resources" / "assets" / "squidutils"

GAME_CONFIG = (Path(os.environ["APPDATA"]) / "PrismLauncher" / "instances"
               / "Skyblock 1.26.1.2" / "minecraft" / "config" / "squidutils")


def export_fusion() -> Path:
    """Compact recipe book: parallel arrays beat 135k JSON objects."""
    book = recipes.load()
    ids = sorted(book.shards)
    index = {sid: i for i, sid in enumerate(ids)}

    shards = [{
        "i": book.shards[s].id,
        "n": book.shards[s].name,
        "t": book.shards[s].tag,
        "r": book.shards[s].rarity,
        "y": book.shards[s].type,
        "f": book.shards[s].fuse_amount,
    } for s in ids]

    # result, qty, inputA, inputB - flat ints, decoded against `shards` order.
    flat: list[int] = []
    for r in book.recipes:
        flat += [index[r.result], r.qty, index[r.a], index[r.b]]

    payload = {"version": 1, "shards": shards, "recipes": flat}
    MOD_ASSETS.mkdir(parents=True, exist_ok=True)
    out = MOD_ASSETS / "fusion.json"
    out.write_text(json.dumps(payload, separators=(",", ":")), encoding="utf-8")
    return out


def export_brain(targets: list[Path]) -> dict:
    cfg = Config.load()
    book = recipes.load()
    with store.connect() as conn:
        profile = hourly.smooth(hourly.hour_profile(conn))
        refs = reference.load(conn, cfg.reference_hours)
        cov = store.coverage(conn)
        # Momentum: movingWeek now vs 24h ago. The mod cannot derive this - it
        # has no history - so it ships in the brain alongside the references.
        trends = score.demand_trend(conn, book.tags)
        demands = demand.load(conn)
        dcov = demand.coverage_summary(conn)

    payload = {
        "version": 1,
        "generated": int(time.time()),
        "coverage": {"rows": cov["rows"], "shards": cov["tags"],
                     "first_ts": cov["first_ts"], "last_ts": cov["last_ts"]},
        "hourProfile": [round(profile.get(h, 1.0), 4) for h in range(24)],
        "reference": {tag: {"ask": round(r.ask, 2), "bid": round(r.bid, 2),
                            "n": r.samples}
                      for tag, r in refs.items() if r.trusted},
        # Every trend, not just the ones far from neutral. Dropping near-1.0
        # values saved a few hundred bytes and cost up to 1% of divergence
        # between the lab's ranking and the mod's, which defeats the point of
        # tuning in the lab and trusting the mod.
        "trend": {tag: round(v, 6) for tag, v in trends.items()},
        # Measured demand: units per hour actually observed leaving each side of
        # the book, plus the number of orders competing at the front of the
        # queue. The mod keeps no history, so without this it would be stuck
        # with the weekly average and a guessed capture share.
        "demand": {tag: {"b": round(d.bought_per_hour, 2),
                         "s": round(d.sold_per_hour, 2),
                         "ao": round(d.ask_competitors, 2),
                         "bo": round(d.bid_competitors, 2),
                         "cov": int(d.coverage)}
                   for tag, d in demands.items() if d.measured},
        "defaults": asdict(cfg),
    }
    blob = json.dumps(payload, separators=(",", ":"))
    for t in targets:
        t.mkdir(parents=True, exist_ok=True)
        (t / "brain.json").write_text(blob, encoding="utf-8")
    return payload


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--brain", action="store_true", help="skip fusion.json")
    args = ap.parse_args()

    targets = [MOD_ASSETS]
    if GAME_CONFIG.parent.exists():
        targets.append(GAME_CONFIG)
    else:
        print(f"note: game config dir not found at {GAME_CONFIG}, "
              f"writing to mod assets only")

    if not args.brain:
        p = export_fusion()
        print(f"fusion.json  {p.stat().st_size / 1e6:.2f} MB  -> {p}")

    payload = export_brain(targets)
    print(f"brain.json   {len(payload['reference'])} reference prices, "
          f"{len(payload['trend'])} demand trends, "
          f"{len(payload['demand'])} measured demand, "
          f"{payload['coverage']['rows']:,} history rows")
    print(f"  hour profile: {hourly.describe({h: v for h, v in enumerate(payload['hourProfile'])})}")
    for t in targets:
        print(f"  -> {t / 'brain.json'}")
