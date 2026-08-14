# Squid Utils

A Hypixel SkyBlock mod for Minecraft 26.1.2 (Fabric), plus the data lab that
tunes it.

| | |
|---|---|
| `mod/` | **Squid Utils** — the Fabric mod. Config GUI via MoulConfig, overlays, ModMenu entry. |
| `shardfuse/`, `scripts/` | The **shard fusion lab**: price collection, the scoring engine, and the tuning workflow. Keeps its own name because it is specifically the fusion engine, not the whole mod. |

**Features**

- **Shard Fusion** — ranks fusions by coins per hour from live Bazaar prices and
  measured demand. Built and verified.
- **Fishing** — scaffolded; config and search are in place, behaviour is not.
- **Bestiary** — scaffolded; same.

The lab has no dependencies beyond the Python standard library. Python 3.14 is
what it was built against.

## Config GUI

Four top-level categories — General, Shard Fusion, Fishing, Bestiary — each
split into accordions so a page stays readable as features land. Search covers
option names, descriptions, category names, and the `@SearchTag` keywords on
every option, so typing "tax" or "blacklist" finds the setting regardless of
which category buried it.

Open it with **`\`** or from **Mod Menu**.

## Use it

```bash
python -m shardfuse
```

```bash
python -m shardfuse --buy BUY_ORDER --sell SELL_OFFER --top 15
```

```bash
python -m shardfuse --set min_profit_per_fuse=25000 --set capture_share=0.1
```

Useful flags:

| Flag | Effect |
|---|---|
| `--buy INSTA_BUY\|BUY_ORDER` | how you acquire inputs |
| `--sell SELL_OFFER\|INSTA_SELL` | how you dispose of output |
| `--hour 3` | score as if it were 03:00 UTC, to compare hours |
| `--per-result 3` | show up to 3 recipe variants per output shard |
| `--plan` | print the fusion tree for the top recursive result |
| `--no-recursive` | one-step only |
| `--set KEY=VAL` | override any config field, repeatable |

Every knob lives in `shardfuse/config.py` and persists to `data/config.json`
via `--save-config`.

## Data

| What | Source | Notes |
|---|---|---|
| Live prices + order book | `api.hypixel.net/v2/skyblock/bazaar` | one call prices all 321 shards, no key |
| Price history | `sky.coflnet.com/api/bazaar/{tag}/history` | ~20 min spacing over long ranges |
| Historical order books | `.../snapshot?timestamp=` | full depth at any past time |
| Fusion recipes | [Campionnn/SkyShards](https://github.com/Campionnn/SkyShards) | 321 shards, 257k raw recipes |

Backfill from the Aug 4 2026 shard update:

```bash
python scripts/run_backfill.py
```

Run the live collector to build order-book history at 60s resolution, which is
finer than Coflnet serves and is what the buy-order fill model wants:

```bash
python scripts/collect.py
```

## The database has two precision tiers

One series per shard, precise where you were online and adequate where you were
not. Every row in `price_history` carries a `src`:

| tier | source | resolution | what it supports |
|---|---|---|---|
| `live` | local collector while the game is open | 60s, with full order books | **measured** trade flow and queue competition |
| `cofl` | Coflnet, for hours you were away | ~3 min in gaps, ~2 h over long sweeps | prices, weekly volumes, reference medians |

Low precision never overwrites high precision — the insert carries a guard on
`src`, so re-running a gap fill cannot degrade a row the collector wrote.

Top the database up after time away, then re-export the model:

```bash
python scripts\sync_db.py --hours 24
```

It derives flow from any unprocessed order books, finds hours with **no** local
data, fills only those, and rewrites `brain.json`. Use `--dry-run` to see the
size of the job first, `--all` to reach back to the Aug 4 update.

A trap worth knowing: Coflnet's resolution depends on how wide a range you ask
for, not on any parameter. A 2-day window returns ~2-hour spacing; a 6-hour
window returns ~3 minutes. Gap filling therefore requests narrow windows, since
raising resolution is the entire point.

### Demand is measured, not assumed

Three numbers set your throughput, and they are not equally well known:

1. **How much trades** — *measured*. The collector diffs consecutive order
   books: units that left the ask side were bought. Cross-checked against the
   independent weekly counts at a **median of 0.96x** (`scripts/probe_flow.py`).
   Shards the collector has not seen fall back to `buyMovingWeek / 168`, which
   is also a real count, just blind to time of day.
2. **Your share when resting an order** — *measured*. Count the orders queued
   within 0.5% of the best price. Nine rivals at the front means you get about a
   tenth of the flow, not a fifth because a slider said so. In practice this
   ranges from 6% on crowded shards to over 40% on quiet ones, and it reorders
   the results substantially.
3. **Your share when crossing the spread** — *assumed*. Nothing in the feed
   reveals how many other players are insta-buying the same shard. This is the
   one remaining guess, and rows relying on it are marked `?` rather than `·`.

## How the ranking works

```
fuses_per_hour = capture_share * min(input A supply, input B supply, output absorption)
coins_per_hour = profit_per_fuse * fuses_per_hour
score          = coins_per_hour * hour_factor ** hour_alpha
```

Ranking on margin alone picks shards nobody trades. Supply and absorption come
from `buyMovingWeek`/`sellMovingWeek` divided by 168, scaled by `capture_share`
because you are not the only person running a fusion flipper.

`hour_alpha` is 0.5 by default, so a 2x peak hour moves the score 1.4x while
demand and margin move it linearly — time-of-day is a tiebreaker, not a driver.

### Two things worth knowing

**Order book naming.** Hypixel names its summaries for the action *you* take,
not the side of the book. `buy_summary` is the ask side. `verify_order_book_convention`
re-checks this against live data on every run; 1,728 products, zero inversions.
A silent inversion here flips the sign of every profit number.

**Phantom prices.** Moray Eel's entire ask side was one 32-unit order at exactly
1,000,000 coins while the shard really traded near 242k. Pricing a sell offer
off that invents 1.5m of profit per fuse. Every quote is therefore checked
against the trailing median of sampled history (`reference.py`) and only
believed up to `max_premium_over_reference` beyond it. Bids get more trust than
asks: a buy order escrows coins, a sell offer only requires owning the item.

### Time of day

The only flow-like historical field is `buyMovingWeek`, a trailing 7-day sum, so

```
dMW(day, h) = flow(day, h) - flow(day - 7, h)
```

Averaging that by hour gives **zero** — the term being dropped is the same hour
a week earlier. Assuming flow separates into a daily level and an hourly shape,
`mean |dMW|` by hour recovers the shape up to a scale. See `hourly.py`. It is a
proxy; `validate_against_live` cross-checks it against measured order-book churn
once the collector has run.

## The mod (`mod/`)

```bash
cd C:\Users\thesh\Downloads\shardfuse\mod; .\gradlew.bat build
```

Output: `mod/build/libs/shardfuse-0.1.0.jar`, with MoulConfig bundled jar-in-jar.

Install by copying that jar into the instance's `mods` folder:

```bash
copy "C:\Users\thesh\Downloads\shardfuse\mod\build\libs\shardfuse-0.1.0.jar" "%APPDATA%\PrismLauncher\instances\Skyblock 1.26.1.2\minecraft\mods\"
```

### Shard icons

All 321 shards ship with real icons, fetched from the SkyBlock wiki:

```bash
python scripts\fetch_shard_icons.py
```

The wiki names them predictably (`<Shard Name>_Shard.png`) and MediaWiki renders
thumbnails server-side, so no PNG decoding or rescaling happens locally — the
files drop straight into the jar and Minecraft's texture loader takes them. Some
shards resolve through a redirect to the underlying texture (Glacite Walker to
`Packed_Ice.png`); the API follows those.

The rejected alternative was the SkyBlock resource pack's glyph font. It only
renders if the player has that pack installed, and a check of this instance
found it absent — the wiki route works for everyone.

A manifest (`shard-icons.json`) records which shards have an icon, so a missing
texture cannot spam a warning every frame. Shards without one fall back to a
rarity-coloured tile.

Images are from hypixelskyblock.minecraft.wiki (CC BY-NC-SA) — fine for personal
use, worth knowing before publishing.

### In game

- The panel draws top-left, listing the best fusions by coins per hour, each
  with cost, profit, ROI, throughput and which flow is the bottleneck.
- Below it, a graph of coins-per-hour over the session for the leading five,
  colour-matched to the list. All series share one vertical scale so you can see
  which shard is pulling ahead.
- **`\`** opens the settings screen, or use the Mod Menu config button.
- Settings live in `config/shardfuse/config.json`; the tuned model is a separate
  file, `config/shardfuse/brain.json`, so re-tuning never touches your settings.

Re-export the tuned model after a tuning session — no rebuild needed, the mod
re-reads it each refresh:

```bash
cd C:\Users\thesh\Downloads\shardfuse; python scripts\export_brain.py --brain
```

### Keeping the mod honest

The mod's engine classes touch no Minecraft API, so they can be run outside the
game and compared against the lab directly:

```bash
powershell -ExecutionPolicy Bypass -File scripts\check_parity.ps1
```

Both should report the same viable count and the same ranking. Run it after any
scoring change — tuning in the lab is only worth anything if the mod agrees.
This is how the Java port's missing demand-trend factor was found: cost, profit
and ROI matched exactly while coins-per-hour quietly diverged by up to 20% and
reordered the list.

### Build environment, and why it looks odd

Getting this to configure took several non-obvious steps. Written down so they
do not have to be rediscovered:

- **Minecraft 26.1.2 has no mappings.** Its version manifest lists only `client`
  and `server` — no `client_mappings` — and yarn has no 26.1 build. So there is
  deliberately **no `mappings(...)` line**. Calling `loom.officialMojangMappings()`
  fails with *"Failed to find official mojang mappings for 26.1.2"*.
- **Loom must be exactly 1.17.1.** Later builds (1.17.19) reject a project whose
  `mappings` configuration is empty, which is fatal given the above.
- **Dependencies are `implementation`, not `modImplementation`.** With an
  unobfuscated Minecraft there is nothing to remap, so loom no longer registers
  the `mod*` configurations at all.
- **`"minecraft"(...)` is quoted** because loom registers that configuration
  lazily and the Kotlin DSL has no type-safe accessor for it.
- **Gradle must be ≥ 9.5** (wrapper is on 9.7.0); Loom 1.17.1 is built against it.

Firmament is the reference implementation for all of the above.

## Layout

```
shardfuse/
  config.py     every tunable, one place
  recipes.py    SkyShards dataset -> Shard/Recipe
  bazaar.py     live Hypixel client + convention check
  coflnet.py    history/snapshot client, rate limited
  store.py      SQLite (WAL)
  backfill.py   historical import + live collector
  reference.py  phantom-price defence
  pricing.py    execution model: real cost, real fill time
  hourly.py     time-of-day factor
  score.py      the ranking engine
  rank.py       CLI
```
