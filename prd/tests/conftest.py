import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))


@pytest.fixture()
def config_env(tmp_path, monkeypatch):
    """Isolated configuration: temp database, temp site directory, local deploys."""
    values = {
        "PRD_DB_PATH": str(tmp_path / "prd.sqlite3"),
        "PRD_SITES_ROOT": str(tmp_path / "sites"),
        "PRD_BASE_URL": "https://prd.test",
        "PRD_SECRET_KEY": "test-secret",
        "PRD_ADMIN_PASSWORD": "hunter2",
        "PRD_IP_SALT": "test-salt",
        "PRD_DEPLOY_TARGET": "local",
        "PRD_AUTO_APPROVE": "0",
        "PYTHONANYWHERE_API_TOKEN": "",
        "PYTHONANYWHERE_USERNAME": "",
    }
    for key, value in values.items():
        monkeypatch.setenv(key, value)
    for key in ("PRD_LIMIT_PUBLISH_HOUR", "PRD_LIMIT_PUBLISH_DAY", "PRD_LIMIT_UPDATE_HOUR",
                "PRD_LIMIT_PREVIEW_MINUTE", "PRD_LIMIT_GLOBAL_DAY", "PRD_DATA_DIR"):
        monkeypatch.delenv(key, raising=False)
    return values


@pytest.fixture()
def app(config_env):
    from prd_app import create_app
    from prd_app.config import Config

    application = create_app(Config())
    application.config.update(TESTING=True)
    return application


@pytest.fixture()
def client(app):
    return app.test_client()


@pytest.fixture()
def ctx(app):
    with app.test_request_context("/"):
        yield app


@pytest.fixture()
def sample_doc():
    return {
        "meta": {"title": "Test Site", "description": "A test", "favicon": "🧪"},
        "theme": {"palette": "midnight", "font": "inter"},
        "blocks": [
            {"type": "hero", "props": {"title": "Hello", "subtitle": "World",
                                       "buttons": [{"label": "Go", "url": "https://example.com", "style": "primary"}]}},
            {"type": "footer", "props": {"text": "© test"}},
        ],
    }


def publish(client, doc, slug="test-site", **extra):
    payload = {"doc": doc, "slug": slug, "public": True, "elapsed_ms": 9000}
    payload.update(extra)
    return client.post("/api/publish", json=payload)
