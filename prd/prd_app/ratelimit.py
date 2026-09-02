"""Sliding-window rate limits.

No accounts means the only thing standing between the queue and someone
requesting fifty sites a minute is this module. Windows are counted in SQLite
so limits survive a worker restart (PythonAnywhere recycles processes often).
"""
from __future__ import annotations

import time
from dataclasses import dataclass

from flask import current_app

from . import db

# action -> (limit attribute on RateLimits, window in seconds, friendly window name)
RULES: dict[str, list[tuple[str, int, str]]] = {
    "publish": [("publish_hour", 3600, "hour"), ("publish_day", 86400, "day")],
    "update": [("update_hour", 3600, "hour")],
    "preview": [("preview_minute", 60, "minute")],
}

PRUNE_AFTER = 86400 * 3


@dataclass
class LimitResult:
    allowed: bool
    message: str = ""
    retry_after: int = 0
    remaining: int = 0

    def __bool__(self) -> bool:  # pragma: no cover - convenience
        return self.allowed


def _limits():
    return current_app.config["PRD_CONFIG"].limits


def _count(ip_hash: str, action: str, since: float) -> int:
    return int(db.scalar(
        "SELECT COUNT(*) FROM rate_events WHERE action = ? AND ip_hash = ? AND created_at > ?",
        (action, ip_hash, since),
    ))


def _oldest(ip_hash: str, action: str, since: float) -> float:
    return float(db.scalar(
        "SELECT MIN(created_at) FROM rate_events WHERE action = ? AND ip_hash = ? AND created_at > ?",
        (action, ip_hash, since), default=time.time(),
    ))


def _friendly(seconds: int) -> str:
    if seconds < 90:
        return f"{max(1, seconds)} seconds"
    if seconds < 5400:
        return f"{round(seconds / 60)} minutes"
    return f"{round(seconds / 3600)} hours"


def is_blocked(ip_hash: str) -> bool:
    return db.query_one("SELECT 1 FROM blocklist WHERE ip_hash = ?", (ip_hash,)) is not None


def check(action: str, ip_hash: str) -> LimitResult:
    """Check limits without recording anything."""
    if is_blocked(ip_hash):
        return LimitResult(False, "This network has been blocked from publishing.", 3600)

    now = time.time()
    limits = _limits()
    remaining = 10_000
    for attr, window, label in RULES.get(action, []):
        allowed = getattr(limits, attr)
        used = _count(ip_hash, action, now - window)
        remaining = min(remaining, max(0, allowed - used))
        if used >= allowed:
            retry = int(_oldest(ip_hash, action, now - window) + window - now) + 1
            return LimitResult(
                False,
                f"You've hit the limit of {allowed} per {label}. Try again in {_friendly(retry)}.",
                max(1, retry),
            )

    if action == "publish":
        day_total = int(db.scalar(
            "SELECT COUNT(*) FROM rate_events WHERE action = 'publish' AND created_at > ?",
            (now - 86400,),
        ))
        if day_total >= limits.global_day:
            return LimitResult(False, "PRD is at its daily publishing limit. Please try again tomorrow.", 3600)

    return LimitResult(True, remaining=remaining)


def record(action: str, ip_hash: str) -> None:
    now = time.time()
    db.execute(
        "INSERT INTO rate_events(ip_hash, action, created_at) VALUES(?, ?, ?)",
        (ip_hash, action, now),
    )
    # Opportunistic cleanup so the table cannot grow without bound.
    if int(now) % 17 == 0:
        db.execute("DELETE FROM rate_events WHERE created_at < ?", (now - PRUNE_AFTER,))


def consume(action: str, ip_hash: str) -> LimitResult:
    result = check(action, ip_hash)
    if result.allowed:
        record(action, ip_hash)
    return result


def usage(ip_hash: str) -> dict:
    """What the visitor has left, for display in the publish dialog."""
    now = time.time()
    limits = _limits()
    return {
        "publish_hour": {"used": _count(ip_hash, "publish", now - 3600), "limit": limits.publish_hour},
        "publish_day": {"used": _count(ip_hash, "publish", now - 86400), "limit": limits.publish_day},
        "blocked": is_blocked(ip_hash),
    }
