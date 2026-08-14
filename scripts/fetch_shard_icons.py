"""Download a shard icon per shard from the SkyBlock wiki into the mod's assets.

The wiki names shard images predictably as "<Shard Name>_Shard.png", and
MediaWiki will render a thumbnail at any width server-side. That matters: it
means no PNG decoding or rescaling is needed here - the files drop straight into
the jar as Minecraft textures, and the game's own texture loader handles them.

Some shards resolve through a redirect to the underlying texture (Glacite Walker
points at Packed_Ice.png, for instance); the API follows those for us.

Images come from hypixelskyblock.minecraft.wiki, which is CC BY-NC-SA. Fine for
a personal mod; worth knowing before publishing one.

Usage:
    python scripts/fetch_shard_icons.py            # 32px, only missing files
    python scripts/fetch_shard_icons.py --force    # re-download everything
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from shardfuse import recipes

API = "https://hypixelskyblock.minecraft.wiki/api.php"
UA = "squidutils-icon-fetch/0.1 (personal Minecraft mod; contact via github)"
OUT = (Path(__file__).resolve().parent.parent / "mod" / "src" / "main"
       / "resources" / "assets" / "squidutils" / "textures" / "shard")

BATCH = 40


def _get(url: str, retries: int = 3) -> bytes:
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=45) as r:
                return r.read()
        except Exception as e:
            last = e
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"{url}: {last}")


def wiki_title(name: str) -> str:
    return f"File:{name.replace(' ', '_')}_Shard.png"


def lookup(names: list[str], width: int) -> dict[str, str]:
    """{shard name: thumbnail url} for the names the wiki actually has."""
    out: dict[str, str] = {}
    for i in range(0, len(names), BATCH):
        chunk = names[i:i + BATCH]
        titles = "|".join(wiki_title(n) for n in chunk)
        params = {
            "action": "query", "format": "json", "prop": "imageinfo",
            "iiprop": "url", "iiurlwidth": str(width),
            "titles": titles, "redirects": "1",
        }
        data = json.loads(_get(API + "?" + urllib.parse.urlencode(params)))
        pages = data.get("query", {}).get("pages", {})

        # Redirects mean the returned title may differ from what we asked for,
        # so map back through the normalised/redirect tables.
        alias: dict[str, str] = {}
        for kind in ("normalized", "redirects"):
            for e in data.get("query", {}).get(kind, []) or []:
                alias[e["to"]] = e["from"]

        wanted = {wiki_title(n): n for n in chunk}
        for page in pages.values():
            title = page.get("title", "")
            root = title
            seen = 0
            while root in alias and seen < 5:
                root = alias[root]
                seen += 1
            name = wanted.get(root) or wanted.get(title)
            if name is None or "imageinfo" not in page:
                continue
            info = page["imageinfo"][0]
            url = info.get("thumburl") or info.get("url")
            if url:
                out[name] = url
        print(f"  looked up {min(i + BATCH, len(names))}/{len(names)}",
              file=sys.stderr, flush=True)
        time.sleep(0.4)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--width", type=int, default=32)
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    book = recipes.load()
    shards = list(book.shards.values())
    OUT.mkdir(parents=True, exist_ok=True)

    names = [s.name for s in shards]
    print(f"resolving {len(names)} shard images from the wiki...")
    urls = lookup(names, args.width)
    print(f"  {len(urls)}/{len(names)} resolved")

    got, skipped, missing = 0, 0, []
    for s in shards:
        # Minecraft texture paths must be lowercase with no spaces.
        dest = OUT / (s.tag.lower().replace("shard_", "") + ".png")
        if s.name not in urls:
            missing.append(s.name)
            continue
        if dest.exists() and not args.force:
            skipped += 1
            continue
        try:
            dest.write_bytes(_get(urls[s.name]))
            got += 1
        except Exception as e:
            missing.append(f"{s.name} ({e})")
        if got and got % 25 == 0:
            print(f"  downloaded {got}", file=sys.stderr, flush=True)
        time.sleep(0.15)

    total = len(list(OUT.glob("*.png")))
    size = sum(p.stat().st_size for p in OUT.glob("*.png"))
    print(f"\ndownloaded {got}, already had {skipped}, "
          f"{len(missing)} without an image")
    if missing:
        print("  missing:", ", ".join(missing[:15]),
              "..." if len(missing) > 15 else "")
    print(f"{total} icons on disk, {size / 1024:.0f} KB total -> {OUT}")

    # A manifest lets the mod skip texture lookups for shards with no icon,
    # which otherwise log a missing-texture warning every frame.
    manifest = sorted(p.stem for p in OUT.glob("*.png"))
    (OUT.parent.parent / "shard-icons.json").write_text(
        json.dumps(manifest, separators=(",", ":")), encoding="utf-8")
    print(f"wrote manifest with {len(manifest)} entries")


if __name__ == "__main__":
    main()
