"""Sliding-window limits are the only gate on an account-free service."""
import time

import pytest

from prd_app import db, ratelimit
from prd_app.security import hash_ip


@pytest.fixture()
def rl(app):
    with app.test_request_context("/", environ_base={"REMOTE_ADDR": "10.0.0.7"}):
        yield hash_ip()


def test_allows_up_to_the_limit_then_blocks(app, rl):
    with app.test_request_context("/"):
        for _ in range(3):
            assert ratelimit.consume("publish", rl).allowed
        result = ratelimit.consume("publish", rl)
        assert not result.allowed
        assert result.retry_after > 0
        assert "3 per hour" in result.message


def test_limits_are_per_visitor(app, rl):
    with app.test_request_context("/"):
        for _ in range(3):
            ratelimit.consume("publish", rl)
        assert ratelimit.check("publish", "someone-else").allowed


def test_old_events_fall_out_of_the_window(app, rl):
    with app.test_request_context("/"):
        old = time.time() - 7200
        for _ in range(3):
            db.execute("INSERT INTO rate_events(ip_hash, action, created_at) VALUES(?,?,?)",
                       (rl, "publish", old))
        assert ratelimit.check("publish", rl).allowed


def test_daily_limit_applies_across_hours(app, rl):
    with app.test_request_context("/"):
        now = time.time()
        # Spread across the day, none of them inside the last hour.
        for index in range(8):
            db.execute("INSERT INTO rate_events(ip_hash, action, created_at) VALUES(?,?,?)",
                       (rl, "publish", now - 7200 * (index + 1)))
        result = ratelimit.check("publish", rl)
        assert not result.allowed and "per day" in result.message


def test_global_daily_cap(app, rl):
    app.config["PRD_CONFIG"].limits.global_day = 2
    with app.test_request_context("/"):
        now = time.time()
        for index in range(2):
            db.execute("INSERT INTO rate_events(ip_hash, action, created_at) VALUES(?,?,?)",
                       (f"other-{index}", "publish", now))
        result = ratelimit.check("publish", rl)
        assert not result.allowed and "daily publishing limit" in result.message


def test_blocked_visitors_are_refused_immediately(app, rl):
    from prd_app import models

    with app.test_request_context("/"):
        models.block_ip(rl, "spam")
        assert not ratelimit.check("publish", rl).allowed
        models.unblock_ip(rl)
        assert ratelimit.check("publish", rl).allowed


def test_ip_hashing_is_salted_and_stable(app):
    with app.test_request_context("/", environ_base={"REMOTE_ADDR": "1.2.3.4"}):
        first = hash_ip()
    with app.test_request_context("/", environ_base={"REMOTE_ADDR": "1.2.3.4"}):
        assert hash_ip() == first
    with app.test_request_context("/", environ_base={"REMOTE_ADDR": "1.2.3.5"}):
        assert hash_ip() != first
    assert "1.2.3.4" not in first


def test_proxy_headers_are_used_for_the_client_address(app):
    from prd_app.security import client_ip

    with app.test_request_context("/", headers={"X-Real-IP": "203.0.113.9"},
                                  environ_base={"REMOTE_ADDR": "10.0.0.1"}):
        assert client_ip() == "203.0.113.9"
    with app.test_request_context("/", headers={"X-Forwarded-For": "203.0.113.5, 10.0.0.1"}):
        assert client_ip() == "203.0.113.5"


def test_manage_tokens_are_compared_safely(app):
    from prd_app.security import hash_token, new_manage_token, token_matches

    with app.test_request_context("/"):
        token = new_manage_token()
        stored = hash_token(token)
        assert token_matches(token, stored)
        assert not token_matches(token + "x", stored)
        assert not token_matches("", stored)
        assert not token_matches(token, "")
