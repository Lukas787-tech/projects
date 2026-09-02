"""Identity-free safety helpers.

PRD deliberately has no accounts, so two primitives do the work of a login:

* a salted hash of the visitor's IP, used only for rate limiting and abuse
  blocks (the raw address is never written anywhere), and
* a random manage token handed to the creator once, stored only as a hash,
  which lets them edit or take down their own site later.
"""
from __future__ import annotations

import hashlib
import hmac
import secrets

from flask import current_app, request

TOKEN_BYTES = 24


def _config():
    return current_app.config["PRD_CONFIG"]


def client_ip() -> str:
    """Best-effort client address behind PythonAnywhere's proxy."""
    forwarded = request.headers.get("X-Real-IP") or request.headers.get("X-Forwarded-For", "")
    if forwarded:
        return forwarded.split(",")[0].strip()[:64]
    return (request.remote_addr or "0.0.0.0")[:64]


def hash_ip(ip: str | None = None) -> str:
    ip = ip if ip is not None else client_ip()
    salt = _config().ip_salt.encode()
    return hashlib.sha256(salt + b"|ip|" + ip.encode()).hexdigest()[:40]


def new_manage_token() -> str:
    return secrets.token_urlsafe(TOKEN_BYTES)


def hash_token(token: str) -> str:
    salt = _config().ip_salt.encode()
    return hashlib.sha256(salt + b"|token|" + token.encode()).hexdigest()


def token_matches(token: str, stored_hash: str) -> bool:
    if not token or not stored_hash:
        return False
    return hmac.compare_digest(hash_token(token), stored_hash)


def check_admin_password(candidate: str) -> bool:
    expected = _config().admin_password
    if not expected:
        return False
    return hmac.compare_digest(candidate.encode(), expected.encode())
