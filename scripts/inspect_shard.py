"""Dump the live book + recent traded range for named shards."""
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from shardfuse import bazaar, coflnet, recipes

book = recipes.load()
by_name = {s.name.lower(): s for s in book.shards.values()}
prods, _ = bazaar.fetch(set(book.tags))

for name in sys.argv[1:]:
    s = by_name.get(name.lower())
    if not s:
        print(f"?? unknown shard {name}")
        continue
    p = prods.get(s.tag)
    print(f"\n=== {s.name} ({s.id}, {s.tag}) rarity={s.rarity} fuse={s.fuse_amount}")
    if not p:
        print("   not on bazaar")
        continue
    print(f"   asks (you buy):  {[(l.price, l.amount, l.orders) for l in p.asks[:6]]}")
    print(f"   bids (you sell): {[(l.price, l.amount, l.orders) for l in p.bids[:6]]}")
    print(f"   buyVol={p.buy_volume:,} sellVol={p.sell_volume:,} "
          f"buyMW={p.buy_moving_week:,} sellMW={p.sell_moving_week:,}")
    print(f"   insta_buy={p.insta_buy} insta_sell={p.insta_sell} spread={p.spread}")
    end = datetime.now(timezone.utc)
    h = coflnet.history(s.tag, end - timedelta(hours=24), end)
    if h:
        buys = [x.buy for x in h if x.buy > 0]
        sells = [x.sell for x in h if x.sell > 0]
        print(f"   24h traded buy  min/med/max: {min(buys):,.0f} / "
              f"{sorted(buys)[len(buys)//2]:,.0f} / {max(buys):,.0f}  (n={len(buys)})")
        print(f"   24h traded sell min/med/max: {min(sells):,.0f} / "
              f"{sorted(sells)[len(sells)//2]:,.0f} / {max(sells):,.0f}")
