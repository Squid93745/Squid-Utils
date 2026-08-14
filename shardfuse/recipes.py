"""Load and normalise the SkyShards fusion dataset.

Source: https://github.com/Campionnn/SkyShards -> public/fusion-data.json

Shape of the raw file:
    {
      "shards":  {"C1": {name, family, type, rarity, fuse_amount, internal_id}},
      "recipes": {"<result_id>": {"<output_qty>": [["<inA>", "<inB>"], ...]}}
    }

A fusion consumes ``fuse_amount`` of each input shard (5 for most, 2 for the
Reptile/Amphibian/Elemental families, 1 for Chameleon) and yields ``output_qty``
of the result.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .config import FUSION_DATA


@dataclass(frozen=True, slots=True)
class Shard:
    id: str
    name: str
    family: str
    type: str
    rarity: str
    fuse_amount: int
    tag: str          # bazaar product id, e.g. SHARD_GROVE


@dataclass(frozen=True, slots=True)
class Recipe:
    result: str
    qty: int
    a: str
    b: str

    def key(self) -> str:
        lo, hi = sorted((self.a, self.b))
        return f"{self.result}x{self.qty}:{lo}+{hi}"


class RecipeBook:
    def __init__(self, shards: dict[str, Shard], recipes: list[Recipe]):
        self.shards = shards
        self.recipes = recipes
        self.by_tag = {s.tag: s for s in shards.values()}

    @property
    def tags(self) -> list[str]:
        return [s.tag for s in self.shards.values()]

    def __len__(self) -> int:
        return len(self.recipes)


def load(path: Path = FUSION_DATA) -> RecipeBook:
    raw = json.loads(path.read_text(encoding="utf-8"))

    shards: dict[str, Shard] = {}
    for sid, s in raw["shards"].items():
        shards[sid] = Shard(
            id=sid,
            name=s["name"],
            family=s.get("family", ""),
            type=s.get("type", ""),
            rarity=s.get("rarity", ""),
            fuse_amount=int(s.get("fuse_amount", 1)),
            tag=s["internal_id"],
        )

    recipes: list[Recipe] = []
    seen: set[str] = set()
    for result, by_qty in raw["recipes"].items():
        if result not in shards:
            continue
        for qty_s, pairs in by_qty.items():
            try:
                qty = int(qty_s)
            except ValueError:
                continue
            if qty <= 0:
                continue
            for pair in pairs:
                if len(pair) != 2:
                    continue
                a, b = pair
                if a not in shards or b not in shards:
                    continue
                r = Recipe(result=result, qty=qty, a=a, b=b)
                k = r.key()
                if k in seen:
                    continue
                seen.add(k)
                recipes.append(r)

    return RecipeBook(shards, recipes)
