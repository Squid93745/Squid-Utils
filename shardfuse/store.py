"""SQLite store for price history and order-book snapshots.

WAL mode is on so the background collector can keep writing while the ranker
reads. Both tables are WITHOUT ROWID with a natural primary key, which makes the
(tag, ts) lookups the analytics do into index seeks and keeps the file small.
"""

from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path

from .config import DB_PATH

# Precision tiers. 'live' rows come from the local 60s collector and carry a
# matching order-book snapshot; 'cofl' rows are Coflnet's ~5-20 minute
# aggregates used to fill the hours the game was closed. Low precision must
# never overwrite high precision, which is what SRC_RANK enforces.
SRC_LIVE = "live"
SRC_COFL = "cofl"
SRC_RANK = {SRC_COFL: 0, SRC_LIVE: 1}

SCHEMA = """
CREATE TABLE IF NOT EXISTS price_history (
    tag       TEXT    NOT NULL,
    ts        INTEGER NOT NULL,
    buy       REAL,
    sell      REAL,
    buy_vol   INTEGER,
    sell_vol  INTEGER,
    buy_mw    INTEGER,
    sell_mw   INTEGER,
    PRIMARY KEY (tag, ts)
) WITHOUT ROWID;

-- Directly observed trade flow, derived from consecutive order-book snapshots:
-- units that left the ask side were bought, units that left the bid side were
-- sold. Only the live collector can produce these - Coflnet's aggregates are
-- too coarse to see individual fills.
CREATE TABLE IF NOT EXISTS flow (
    tag        TEXT    NOT NULL,
    ts         INTEGER NOT NULL,   -- end of the interval
    secs       INTEGER NOT NULL,   -- interval length
    bought     INTEGER,            -- units taken off the ask side
    sold       INTEGER,            -- units taken off the bid side
    ask_depth  INTEGER,            -- resting units on asks, top levels
    bid_depth  INTEGER,
    ask_orders INTEGER,            -- competing orders near top of ask
    bid_orders INTEGER,
    PRIMARY KEY (tag, ts)
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS idx_flow_ts ON flow(ts);

CREATE INDEX IF NOT EXISTS idx_price_ts ON price_history(ts);

CREATE TABLE IF NOT EXISTS orderbook (
    tag     TEXT    NOT NULL,
    ts      INTEGER NOT NULL,
    side    TEXT    NOT NULL,   -- 'ask' | 'bid'
    lvl     INTEGER NOT NULL,
    price   REAL,
    amount  INTEGER,
    orders  INTEGER,
    PRIMARY KEY (tag, ts, side, lvl)
) WITHOUT ROWID;

-- The primary key leads with tag, so looking a snapshot up by timestamp alone
-- would full-scan the table. Flow derivation does exactly that once per
-- snapshot, and the table reaches tens of millions of rows within days.
CREATE INDEX IF NOT EXISTS idx_orderbook_ts ON orderbook(ts);

CREATE TABLE IF NOT EXISTS meta (
    k TEXT PRIMARY KEY,
    v TEXT
);
"""


def _migrate(conn) -> None:
    cols = {d[1] for d in conn.execute("PRAGMA table_info(price_history)")}
    if "src" not in cols:
        conn.execute(
            f"ALTER TABLE price_history ADD COLUMN src TEXT DEFAULT '{SRC_COFL}'")
        # Rows that coincide with an order-book snapshot came from the local
        # collector, so relabel them rather than losing that provenance.
        conn.execute(
            f"UPDATE price_history SET src = '{SRC_LIVE}'"
            f" WHERE ts IN (SELECT DISTINCT ts FROM orderbook)")


@contextmanager
def connect(path: Path = DB_PATH):
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=30.0)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.executescript(SCHEMA)
        _migrate(conn)
        yield conn
        conn.commit()
    finally:
        conn.close()


def insert_history(conn, tag: str, points, src: str = SRC_COFL) -> int:
    """Insert price points, refusing to downgrade an existing better row."""
    rows = [(tag, p.ts, p.buy, p.sell, p.buy_volume, p.sell_volume,
             p.buy_moving_week, p.sell_moving_week, src) for p in points]
    conn.executemany(
        "INSERT INTO price_history"
        " (tag, ts, buy, sell, buy_vol, sell_vol, buy_mw, sell_mw, src)"
        " VALUES (?,?,?,?,?,?,?,?,?)"
        " ON CONFLICT(tag, ts) DO UPDATE SET"
        "   buy=excluded.buy, sell=excluded.sell,"
        "   buy_vol=excluded.buy_vol, sell_vol=excluded.sell_vol,"
        "   buy_mw=excluded.buy_mw, sell_mw=excluded.sell_mw,"
        "   src=excluded.src"
        f" WHERE (CASE excluded.src WHEN '{SRC_LIVE}' THEN 1 ELSE 0 END)"
        f"    >= (CASE price_history.src WHEN '{SRC_LIVE}' THEN 1 ELSE 0 END)",
        rows)
    return len(rows)


def insert_flow(conn, rows) -> int:
    """rows: (tag, ts, secs, bought, sold, ask_depth, bid_depth, ask_orders, bid_orders)"""
    conn.executemany(
        "INSERT OR REPLACE INTO flow"
        " (tag, ts, secs, bought, sold, ask_depth, bid_depth, ask_orders, bid_orders)"
        " VALUES (?,?,?,?,?,?,?,?,?)", rows)
    return len(rows)


def insert_book(conn, tag: str, ts: int, asks, bids, depth: int = 30) -> int:
    """Store the top ``depth`` levels of both sides.

    Depth matters beyond price: the queue-competition estimate counts orders
    near the top of book, so if the stored book is shallower than the crowd,
    rivals are undercounted and your capture share is overstated. Hypixel
    serves about 30 levels, so take them.
    """
    rows = []
    for side, levels in (("ask", asks), ("bid", bids)):
        for i, l in enumerate(levels[:depth]):
            rows.append((tag, ts, side, i, l.price, l.amount, l.orders))
    conn.executemany(
        "INSERT OR REPLACE INTO orderbook"
        " (tag, ts, side, lvl, price, amount, orders) VALUES (?,?,?,?,?,?,?)",
        rows)
    return len(rows)


def coverage(conn) -> dict:
    row = conn.execute(
        "SELECT COUNT(*), COUNT(DISTINCT tag), MIN(ts), MAX(ts) FROM price_history"
    ).fetchone()
    books = conn.execute(
        "SELECT COUNT(DISTINCT ts), COUNT(DISTINCT tag) FROM orderbook"
    ).fetchone()
    return {
        "rows": row[0] or 0,
        "tags": row[1] or 0,
        "first_ts": row[2],
        "last_ts": row[3],
        "book_snapshots": books[0] or 0,
        "book_tags": books[1] or 0,
    }


def latest_ts(conn, tag: str) -> int | None:
    row = conn.execute(
        "SELECT MAX(ts) FROM price_history WHERE tag = ?", (tag,)).fetchone()
    return row[0] if row and row[0] else None


def set_meta(conn, k: str, v: str) -> None:
    conn.execute("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)", (k, str(v)))


def get_meta(conn, k: str) -> str | None:
    row = conn.execute("SELECT v FROM meta WHERE k = ?", (k,)).fetchone()
    return row[0] if row else None
