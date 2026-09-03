"""Thin SQLite layer.

PythonAnywhere free accounts get SQLite for free and no MySQL headaches, so PRD
uses stdlib sqlite3 with a tiny helper layer instead of pulling in an ORM.
"""
from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Any, Iterable, Sequence

from flask import current_app, g

SCHEMA_PATH = Path(__file__).with_name("schema.sql")


def _connect(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path), timeout=15, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 8000")
    return conn


def get_db() -> sqlite3.Connection:
    if "prd_db" not in g:
        g.prd_db = _connect(Path(current_app.config["PRD_CONFIG"].db_path))
    return g.prd_db


def close_db(_exc: BaseException | None = None) -> None:
    conn = g.pop("prd_db", None)
    if conn is not None:
        conn.close()


# Columns added after the first release. CREATE TABLE IF NOT EXISTS will not
# add them to a database that already exists, so they are applied by hand.
LATE_COLUMNS = (
    ("sites", "custom_domain", "TEXT NOT NULL DEFAULT ''"),
    ("sites", "domain_verified", "INTEGER NOT NULL DEFAULT 0"),
)


def _add_late_columns(conn: sqlite3.Connection) -> None:
    for table, column, spec in LATE_COLUMNS:
        info = list(conn.execute(f"PRAGMA table_info({table})"))
        if not info:
            continue  # the table does not exist yet; the schema creates it complete
        if column not in {row["name"] for row in info}:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {spec}")


def init_db(path: Path) -> None:
    """Create or update the schema. Safe to call on every boot."""
    conn = _connect(Path(path))
    try:
        # Columns first: the schema script indexes one of them, so running it
        # against an older database would fail before the column exists.
        _add_late_columns(conn)
        conn.executescript(SCHEMA_PATH.read_text(encoding="utf-8"))
    finally:
        conn.close()


def query(sql: str, params: Sequence[Any] = ()) -> list[sqlite3.Row]:
    return list(get_db().execute(sql, params).fetchall())


def query_one(sql: str, params: Sequence[Any] = ()) -> sqlite3.Row | None:
    return get_db().execute(sql, params).fetchone()


def execute(sql: str, params: Sequence[Any] = ()) -> sqlite3.Cursor:
    return get_db().execute(sql, params)


def insert(sql: str, params: Sequence[Any] = ()) -> int:
    cur = get_db().execute(sql, params)
    return int(cur.lastrowid or 0)


def scalar(sql: str, params: Sequence[Any] = (), default: Any = 0) -> Any:
    row = query_one(sql, params)
    if row is None:
        return default
    value = row[0]
    return default if value is None else value


def transaction() -> "Transaction":
    return Transaction(get_db())


class Transaction:
    """`with transaction():` — commits on success, rolls back on error."""

    def __init__(self, conn: sqlite3.Connection) -> None:
        self.conn = conn

    def __enter__(self) -> sqlite3.Connection:
        self.conn.execute("BEGIN IMMEDIATE")
        return self.conn

    def __exit__(self, exc_type, exc, tb) -> bool:
        if exc_type is None:
            self.conn.execute("COMMIT")
        else:
            self.conn.execute("ROLLBACK")
        return False


def get_setting(key: str, default: str = "") -> str:
    row = query_one("SELECT value FROM settings WHERE key = ?", (key,))
    return row["value"] if row else default


def set_setting(key: str, value: str) -> None:
    execute(
        "INSERT INTO settings(key, value) VALUES(?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (key, value),
    )


def rows_to_dicts(rows: Iterable[sqlite3.Row]) -> list[dict]:
    return [dict(row) for row in rows]
