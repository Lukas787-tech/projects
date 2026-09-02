"""Configuration for PRD, read entirely from the environment.

Nothing secret is ever hard-coded here: the PythonAnywhere API token, the admin
password and the session key all come from environment variables so the repo
stays safe to publish.
"""
from __future__ import annotations

import os
import secrets
from dataclasses import dataclass, field
from pathlib import Path


def _env(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def _env_int(name: str, default: int) -> int:
    raw = _env(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_bool(name: str, default: bool = False) -> bool:
    raw = _env(name).lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on"}


DEFAULT_DATA_DIR = Path(_env("PRD_DATA_DIR") or (Path.home() / "prd-data"))


@dataclass
class RateLimits:
    publish_hour: int = 3
    publish_day: int = 8
    update_hour: int = 20
    preview_minute: int = 90
    global_day: int = 250

    @classmethod
    def from_env(cls) -> "RateLimits":
        return cls(
            publish_hour=_env_int("PRD_LIMIT_PUBLISH_HOUR", 3),
            publish_day=_env_int("PRD_LIMIT_PUBLISH_DAY", 8),
            update_hour=_env_int("PRD_LIMIT_UPDATE_HOUR", 20),
            preview_minute=_env_int("PRD_LIMIT_PREVIEW_MINUTE", 90),
            global_day=_env_int("PRD_LIMIT_GLOBAL_DAY", 250),
        )


@dataclass
class PythonAnywhereConfig:
    token: str = ""
    username: str = ""
    host: str = "www.pythonanywhere.com"
    domain: str = ""
    sites_dir: str = ""
    sites_url: str = "/sites/"
    timeout: int = 30

    @property
    def configured(self) -> bool:
        return bool(self.token and self.username)

    @property
    def api_base(self) -> str:
        return f"https://{self.host}/api/v0/user/{self.username}"

    @classmethod
    def from_env(cls) -> "PythonAnywhereConfig":
        username = _env("PYTHONANYWHERE_USERNAME")
        host = _env("PYTHONANYWHERE_HOST", "www.pythonanywhere.com")
        domain = _env("PYTHONANYWHERE_DOMAIN") or (
            f"{username}.pythonanywhere.com" if username else ""
        )
        sites_dir = _env("PYTHONANYWHERE_SITES_DIR") or (
            f"/home/{username}/prd-data/sites" if username else ""
        )
        url = _env("PYTHONANYWHERE_SITES_URL", "/sites/")
        if not url.startswith("/"):
            url = "/" + url
        if not url.endswith("/"):
            url += "/"
        return cls(
            token=_env("PYTHONANYWHERE_API_TOKEN"),
            username=username,
            host=host,
            domain=domain,
            sites_dir=sites_dir.rstrip("/"),
            sites_url=url,
            timeout=_env_int("PYTHONANYWHERE_TIMEOUT", 30),
        )


@dataclass
class Config:
    secret_key: str = field(default_factory=lambda: _env("PRD_SECRET_KEY") or secrets.token_hex(32))
    admin_password: str = field(default_factory=lambda: _env("PRD_ADMIN_PASSWORD"))
    ip_salt: str = field(default_factory=lambda: _env("PRD_IP_SALT") or "prd-default-salt")
    db_path: Path = field(default_factory=lambda: Path(_env("PRD_DB_PATH") or DEFAULT_DATA_DIR / "prd.sqlite3"))
    sites_root: Path = field(default_factory=lambda: Path(_env("PRD_SITES_ROOT") or DEFAULT_DATA_DIR / "sites"))
    base_url: str = field(default_factory=lambda: _env("PRD_BASE_URL").rstrip("/"))
    deploy_target: str = field(default_factory=lambda: _env("PRD_DEPLOY_TARGET", "auto").lower())
    auto_approve: bool = field(default_factory=lambda: _env_bool("PRD_AUTO_APPROVE", False))
    limits: RateLimits = field(default_factory=RateLimits.from_env)
    pythonanywhere: PythonAnywhereConfig = field(default_factory=PythonAnywhereConfig.from_env)

    # Hard ceilings that protect the renderer and the database.
    max_document_bytes: int = 300_000
    max_blocks: int = 80

    @property
    def admin_enabled(self) -> bool:
        return bool(self.admin_password)

    def to_flask(self) -> dict:
        return {
            "SECRET_KEY": self.secret_key,
            "SESSION_COOKIE_HTTPONLY": True,
            "SESSION_COOKIE_SAMESITE": "Lax",
            "MAX_CONTENT_LENGTH": self.max_document_bytes + 100_000,
            "JSON_SORT_KEYS": False,
        }
