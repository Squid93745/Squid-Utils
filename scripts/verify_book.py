import sys, json
sys.path.insert(0, r"C:\Users\thesh\Downloads\shardfuse")
from shardfuse import bazaar, recipes

book = recipes.load()
tags = set(book.tags)
prods, updated = bazaar.fetch()
print("total bazaar products:", len(prods))
shard_prods = {t: p for t, p in prods.items() if t in tags}
print("shard products found:", len(shard_prods), "of", len(tags))
missing = tags - set(shard_prods)
print("shards missing from bazaar:", len(missing), sorted(missing)[:10])

v = bazaar.verify_order_book_convention(shard_prods)
print("\n-- convention check (shards) --")
print(json.dumps(v, indent=2))
v2 = bazaar.verify_order_book_convention(prods)
print("\n-- convention check (ALL bazaar products) --")
print("checked", v2["checked"], "inverted", v2["inverted"],
      "asks_asc", v2["asks_ascending"], "bids_desc", v2["bids_descending"], "ok", v2["ok"])

p = shard_prods.get("SHARD_SALMON")
if p:
    print("\nSHARD_SALMON asks[:3]", [(l.price, l.amount) for l in p.asks[:3]])
    print("SHARD_SALMON bids[:3]", [(l.price, l.amount) for l in p.bids[:3]])
    print("insta_buy", p.insta_buy, "insta_sell", p.insta_sell, "spread", p.spread)
    print("movingWeek buy/sell", p.buy_moving_week, p.sell_moving_week)
print("\nmayor:", bazaar.current_mayor())
