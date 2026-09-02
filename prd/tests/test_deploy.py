"""Deployers, including the PythonAnywhere API client (against a stub session)."""
import json
from pathlib import Path

import pytest

from prd_app.config import Config, PythonAnywhereConfig
from prd_app.deploy import LocalDeployer, build_deployer
from prd_app.deploy.pythonanywhere import (
    PythonAnywhereClient, PythonAnywhereDeployer, PythonAnywhereError,
)


class FakeResponse:
    def __init__(self, status_code=200, payload=None, text=""):
        self.status_code = status_code
        self._payload = payload
        self.text = text
        self.content = b"x" if payload is not None or text else b""

    def json(self):
        return self._payload


class FakeSession:
    """Records calls and replays scripted responses keyed by 'METHOD /path'."""

    def __init__(self, responses=None):
        self.headers = {}
        self.calls = []
        self.responses = responses or {}
        self.default = FakeResponse(200, {})

    def request(self, method, url, **kwargs):
        path = url.split("/api/v0/user/tester", 1)[-1]
        key = f"{method} {path}"
        self.calls.append({"key": key, "url": url, "kwargs": kwargs})
        value = self.responses.get(key, self.default)
        if isinstance(value, list):
            return value.pop(0) if value else self.default
        return value


@pytest.fixture(autouse=True)
def no_backoff_sleep(monkeypatch):
    """Retries are covered by call counts, not by actually waiting."""
    monkeypatch.setattr("prd_app.deploy.pythonanywhere.time.sleep", lambda _s: None)


@pytest.fixture()
def pa_config(tmp_path):
    return PythonAnywhereConfig(
        token="test-token-not-real", username="tester", host="www.pythonanywhere.com",
        domain="tester.pythonanywhere.com", sites_dir="/home/tester/prd-data/sites",
        sites_url="/sites/",
    )


def deployer_with(session, pa_config):
    client = PythonAnywhereClient(pa_config, session=session)
    return PythonAnywhereDeployer(pa_config, base_url="https://tester.pythonanywhere.com", client=client)


# ---------------------------------------------------------------- local ----

def test_local_deployer_writes_and_removes(tmp_path):
    deployer = LocalDeployer(tmp_path / "sites", "https://prd.test")
    result = deployer.deploy("hello", "<html>hi</html>")
    assert result.ok
    assert result.url == "https://prd.test/s/hello"
    assert (tmp_path / "sites" / "hello" / "index.html").read_text() == "<html>hi</html>"
    assert deployer.remove("hello").ok
    assert not (tmp_path / "sites" / "hello").exists()


def test_build_deployer_falls_back_to_local_without_a_token(config_env):
    assert build_deployer(Config()).name == "local"


def test_build_deployer_picks_pythonanywhere_when_configured(config_env, monkeypatch):
    monkeypatch.setenv("PRD_DEPLOY_TARGET", "auto")
    monkeypatch.setenv("PYTHONANYWHERE_API_TOKEN", "abc")
    monkeypatch.setenv("PYTHONANYWHERE_USERNAME", "tester")
    assert build_deployer(Config()).name == "pythonanywhere"


def test_explicit_local_target_wins_over_a_configured_token(config_env, monkeypatch):
    monkeypatch.setenv("PRD_DEPLOY_TARGET", "local")
    monkeypatch.setenv("PYTHONANYWHERE_API_TOKEN", "abc")
    monkeypatch.setenv("PYTHONANYWHERE_USERNAME", "tester")
    assert build_deployer(Config()).name == "local"


# -------------------------------------------------------- pythonanywhere ----

def test_token_is_sent_as_an_authorization_header(pa_config):
    session = FakeSession()
    PythonAnywhereClient(pa_config, session=session)
    assert session.headers["Authorization"] == "Token test-token-not-real"


def test_deploy_uploads_the_page_and_creates_the_static_mapping(pa_config):
    session = FakeSession({
        "POST /files/path/home/tester/prd-data/sites/mysite/index.html": FakeResponse(201),
        "POST /files/path/home/tester/prd-data/sites/mysite.html": FakeResponse(201),
        "GET /webapps/tester.pythonanywhere.com/static_files/": FakeResponse(200, []),
        "POST /webapps/tester.pythonanywhere.com/static_files/": FakeResponse(201, {}),
        "POST /webapps/tester.pythonanywhere.com/reload/": FakeResponse(200, {}),
    })
    result = deployer_with(session, pa_config).deploy("mysite", "<html>x</html>")
    assert result.ok, result.detail
    assert result.url == "https://tester.pythonanywhere.com/s/mysite"
    assert result.extra["static_url"] == "https://tester.pythonanywhere.com/sites/mysite/"

    keys = [call["key"] for call in session.calls]
    assert "POST /files/path/home/tester/prd-data/sites/mysite/index.html" in keys
    assert "POST /webapps/tester.pythonanywhere.com/static_files/" in keys
    assert "POST /webapps/tester.pythonanywhere.com/reload/" in keys

    mapping = next(c for c in session.calls if c["key"].endswith("static_files/") and c["key"].startswith("POST"))
    assert mapping["kwargs"]["data"] == {"url": "/sites/", "path": "/home/tester/prd-data/sites"}


def test_existing_mapping_is_not_recreated(pa_config):
    session = FakeSession({
        "POST /files/path/home/tester/prd-data/sites/x/index.html": FakeResponse(201),
        "POST /files/path/home/tester/prd-data/sites/x.html": FakeResponse(201),
        "GET /webapps/tester.pythonanywhere.com/static_files/":
            FakeResponse(200, [{"id": 1, "url": "/sites/", "path": "/home/tester/prd-data/sites"}]),
    })
    deployer = deployer_with(session, pa_config)
    assert deployer.deploy("x", "<html/>").ok
    assert not any(call["key"].startswith("POST /webapps") for call in session.calls)


def test_mapping_pointing_elsewhere_is_reported_not_overwritten(pa_config):
    session = FakeSession({
        "POST /files/path/home/tester/prd-data/sites/x/index.html": FakeResponse(201),
        "POST /files/path/home/tester/prd-data/sites/x.html": FakeResponse(201),
        "GET /webapps/tester.pythonanywhere.com/static_files/":
            FakeResponse(200, [{"id": 1, "url": "/sites/", "path": "/somewhere/else"}]),
    })
    result = deployer_with(session, pa_config).deploy("x", "<html/>")
    assert result.ok and "Warning" in result.detail


def test_mapping_is_only_checked_once_per_process(pa_config):
    session = FakeSession({
        "GET /webapps/tester.pythonanywhere.com/static_files/":
            FakeResponse(200, [{"id": 1, "url": "/sites/", "path": "/home/tester/prd-data/sites"}]),
    })
    session.default = FakeResponse(201)
    deployer = deployer_with(session, pa_config)
    deployer.deploy("a", "<html/>")
    deployer.deploy("b", "<html/>")
    lookups = [c for c in session.calls if c["key"].startswith("GET /webapps")]
    assert len(lookups) == 1


def test_upload_failure_is_surfaced_not_raised(pa_config):
    session = FakeSession()
    session.default = FakeResponse(403, text="forbidden")
    result = deployer_with(session, pa_config).deploy("x", "<html/>")
    assert not result.ok and "403" in result.detail


def test_server_errors_are_retried_then_reported(pa_config):
    session = FakeSession()
    session.default = FakeResponse(502)
    result = deployer_with(session, pa_config).deploy("x", "<html/>")
    assert not result.ok
    assert len([c for c in session.calls if "files/path" in c["key"]]) == 4  # MAX_ATTEMPTS


def test_status_reports_a_bad_token(pa_config):
    session = FakeSession({"GET /cpu/": FakeResponse(401)})
    status = deployer_with(session, pa_config).status()
    assert status["ready"] is False and "401" in status["detail"]


def test_status_reports_a_working_connection(pa_config):
    session = FakeSession({"GET /cpu/": FakeResponse(200, {"daily_cpu_limit_seconds": 100})})
    status = deployer_with(session, pa_config).status()
    assert status["ready"] is True and "tester" in status["detail"]


def test_local_write_is_preferred_when_the_directory_is_on_this_machine(tmp_path, pa_config):
    pa_config.sites_dir = str(tmp_path)
    session = FakeSession({
        "GET /webapps/tester.pythonanywhere.com/static_files/":
            FakeResponse(200, [{"url": "/sites/", "path": str(tmp_path)}]),
    })
    result = deployer_with(session, pa_config).deploy("localish", "<html>local</html>")
    assert result.ok
    assert (tmp_path / "localish" / "index.html").read_text() == "<html>local</html>"
    assert not any("files/path" in call["key"] for call in session.calls)


def test_remove_deletes_both_paths(pa_config):
    session = FakeSession()
    session.default = FakeResponse(204)
    assert deployer_with(session, pa_config).remove("bye").ok
    deleted = [c["key"] for c in session.calls if c["key"].startswith("DELETE")]
    assert any("bye/index.html" in key for key in deleted)
    assert any(key.endswith("bye.html") for key in deleted)


def test_token_never_appears_in_a_deploy_result(pa_config):
    session = FakeSession()
    session.default = FakeResponse(500, text="boom test-token-not-real")
    result = deployer_with(session, pa_config).deploy("x", "<html/>")
    assert "test-token-not-real" not in result.detail
