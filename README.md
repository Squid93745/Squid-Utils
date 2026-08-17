# Squid Utils

*Hypixel SkyBlock QoL mod for shard fusions, Bazaar orders, and various other parts of the game.*

## Table of Contents
- [Releases](#releases)
- [Dependencies](#dependencies)
- [Features](#features)
- [Troubleshooting](#troubleshooting)
- [Contacts](#contacts)
- [Credits](#credits)

---

## Releases

Released on Modrinth.

---

## Dependencies

#### 26.1.2
- **Fabric API** — required
- **Fabric Language Kotlin** — required
- **Mod Menu** — optional, recommended for easy access to settings
- Java 25, Fabric Loader ≥ 0.19.0

---

## Features

### Shard Fusion
- Ranks every fusion by **profit per fuse** using live Bazaar prices and how fast the market actually absorbs each shard.
- **Profit Shards** tables — four independently configurable trading-mode variants (e.g. Buy Order → Instasell, Instabuy → Sell Offer), each ranked by margin × real market speed.
- **Recommended** table — a blended overall ranking across all viable fusions.
- **XP per fuse** table for grinding Fusion XP efficiently and without going broke.
- **Multi-step routing** — finds the cheapest chain of fusions and buys to reach a result, not just a single hop.
- **Shopping list** — queue everything you need across multiple routes into one running list, with a batch-depth warning and large purchase protections, so you don't buy too much of a shard and overpay.
- **Fuse, in order** panel — every queued fusion step across your whole shopping list, dependency-sorted so you know what to fuse first.
- Item tooltip line showing the cheapest route to fuse any shard you hover over.
- Bindable hotkey to jump straight to a shard's route screen.
- Quickfuse keybind that complies with Hypixel's rules to fuse shards as easily and quickly as possible (legally).

### Bazaar
- Tracks your own placed **buy orders** and **sell offers**.
- Alerts you — chat message, sound, and/or an on-screen display — the moment one of your orders gets outbid or undercut.

### Fishing
- **Frozen Blaze** anti-afk overlay so you don't forget to move, and Frozen Blaze stops hitting mobs around you.

### HUD
- Every panel (fusion tables, shopping list, fuse order) is its own movable, resizable overlay.
- Built-in HUD editor to lay them out wherever you want.

### Commands
- `\` opens the settings menu.
- `/squidutils` (or `/squid`) does the same from chat.

---

## Troubleshooting

> **The hunting exp tracker is off.**
> Open your stats menu so the mod can read your hunting wisdom. Calculations stay accurate until you gain/lose hunting wisdom.

> **I changed the Table settings and nothing seems different.**
> It reschedules live, no restart needed — give it one full interval to take effect.

---

## Contacts
- Bug reports and suggestions: DM 2squid on Discord.
- IGN: Squid93745

---

## Credits
- Built on [MoulConfig](https://github.com/NotEnoughUpdates/MoulConfig) for the settings GUI.
- Loom/mapping setup modeled on [Firmament](https://github.com/nea89o/Firmament).
- Price and order-book data from Hypixel's own Bazaar API.
- Made by Squid93745.

## Contributors
Shoutout to the wonderful beta testers who helped this mod become what it is now!
- Bjork99
- Metalhat46
