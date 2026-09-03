"""The one-command deploy script, driven against a stubbed PythonAnywhere API."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import deploy_pythonanywhere as script  # noqa: E402
from prd_app.config import PythonAnywhereConfig  # noqa: E402
from prd_app.deploy.pythonanywhere import PythonAnywhereClient  # noqa: E402
from test_deploy import FakeResponse, FakeSession  # noqa: E402

DOMAIN = "tester.pythonanywhere.com"


@pytest.fixture()
def api(monkeypatch):
    """A fake PythonAnywhere that records everything the script does to it."""
    session = FakeSession({
        "GET /cpu/": FakeResponse(200, {"daily_cpu_limit_seconds": 100}),
        "GET /webapps/": FakeResponse(200, []),
        "POST /webapps/": FakeResponse(201, {"domain_name": DOMAIN}),
        f"PATCH /webapps/{DOMAIN}/": FakeResponse(200, {}),
        f"GET /webapps/{DOMAIN}/static_files/": FakeResponse(200, []),
        f"POST /webapps/{DOMAIN}/static_files/": FakeResponse(201, {}),
        f"POST /webapps/{DOMAIN}/reload/": FakeResponse(200, {}),
    })
    session.default = FakeResponse(201)

    def build(config, session_arg=None):
        return PythonAnywhereClient(config, session=session)

    monkeypatch.setattr(script, "PythonAnywhereClient", build)
    monkeypatch.setattr(script, "running_on_pythonanywhere", lambda: False)
    monkeypatch.setattr("prd_app.deploy.pythonanywhere.time.sleep", lambda _s: None)
    return session


def run(monkeypatch, *extra):
    monkeypatch.setattr(sys, "argv", [
        "deploy_pythonanywhere.py", "--token", "tok-not-real", "--username", "tester",
        "--host", "www.pythonanywhere.com", *extra,
    ])
    return script.main()


def uploads(session):
    """Remote path -> uploaded text."""
    out = {}
    for call in session.calls:
        if call["key"].startswith("POST /files/path"):
            content = call["kwargs"]["files"]["content"][1]
            out[call["key"][len("POST /files/path"):]] = content.decode("utf-8")
    return out


def test_a_full_deploy_makes_every_call_in_order(api, monkeypatch, capsys):
    assert run(monkeypatch, "--admin-password", "secret-pw") == 0
    keys = [call["key"] for call in api.calls]

    assert keys.index("GET /cpu/") < keys.index("GET /webapps/")
    assert "POST /webapps/" in keys                      # web app created
    assert f"PATCH /webapps/{DOMAIN}/" in keys           # source directory set
    assert keys.index(f"POST /webapps/{DOMAIN}/reload/") == len(keys) - 1  # reload last

    output = capsys.readouterr().out
    assert f"https://{DOMAIN}/" in output
    assert "secret-pw" in output


def test_new_webapp_is_created_with_the_requested_python(api, monkeypatch):
    run(monkeypatch, "--python-version", "3.11")
    create = next(c for c in api.calls if c["key"] == "POST /webapps/")
    assert create["kwargs"]["data"] == {"domain_name": DOMAIN, "python_version": "3.11"}


def test_source_directory_is_pointed_at_the_project(api, monkeypatch):
    run(monkeypatch)
    patch = next(c for c in api.calls if c["key"].startswith("PATCH"))
    assert patch["kwargs"]["data"]["source_directory"] == "/home/tester/projects/prd"


def test_virtualenv_is_attached_when_given(api, monkeypatch):
    run(monkeypatch, "--virtualenv", "/home/tester/.virtualenvs/prd")
    patch = next(c for c in api.calls if c["key"].startswith("PATCH"))
    assert patch["kwargs"]["data"]["virtualenv_path"] == "/home/tester/.virtualenvs/prd"


def test_project_files_are_uploaded(api, monkeypatch):
    run(monkeypatch)
    sent = uploads(api)
    assert "/home/tester/projects/prd/wsgi.py" in sent
    assert "/home/tester/projects/prd/prd_app/render.py" in sent
    assert "/home/tester/projects/prd/prd_app/static/js/editor.js" in sent
    assert "PRD" in sent["/home/tester/projects/prd/README.md"]


def test_junk_is_not_uploaded(api, monkeypatch):
    run(monkeypatch)
    for path in uploads(api):
        assert "__pycache__" not in path
        assert not path.endswith(".pyc")
        assert not path.endswith(".sqlite3")
        assert "/.git/" not in path


def test_generated_env_has_fresh_secrets_and_the_right_paths(api, monkeypatch):
    run(monkeypatch, "--admin-password", "hunter2")
    env = uploads(api)["/home/tester/projects/prd/.env"]
    values = dict(line.split("=", 1) for line in env.splitlines() if "=" in line and not line.startswith("#"))
    assert len(values["PRD_SECRET_KEY"]) == 64
    assert len(values["PRD_IP_SALT"]) == 32
    assert values["PRD_ADMIN_PASSWORD"] == "hunter2"
    assert values["PRD_BASE_URL"] == f"https://{DOMAIN}"
    assert values["PRD_DEPLOY_TARGET"] == "pythonanywhere"
    assert values["PRD_AUTO_APPROVE"] == "0"
    assert values["PRD_SITES_ROOT"] == "/home/tester/prd-data/sites"
    assert values["PYTHONANYWHERE_SITES_DIR"] == "/home/tester/prd-data/sites"


def test_two_deploys_do_not_reuse_the_same_secret_key(api, monkeypatch):
    run(monkeypatch)
    first = uploads(api)["/home/tester/projects/prd/.env"]
    api.calls.clear()
    run(monkeypatch)
    second = uploads(api)["/home/tester/projects/prd/.env"]
    assert first != second


def test_auto_approve_flag_reaches_the_env(api, monkeypatch):
    run(monkeypatch, "--auto-approve")
    assert "PRD_AUTO_APPROVE=1" in uploads(api)["/home/tester/projects/prd/.env"]


def test_a_password_is_generated_when_none_is_given(api, monkeypatch, capsys):
    run(monkeypatch)
    env = uploads(api)["/home/tester/projects/prd/.env"]
    password = next(line.split("=", 1)[1] for line in env.splitlines()
                    if line.startswith("PRD_ADMIN_PASSWORD="))
    assert len(password) >= 12
    assert password in capsys.readouterr().out       # shown once, so it is usable


def test_existing_env_can_be_kept(api, monkeypatch):
    api.responses["GET /files/path/home/tester/projects/prd/.env"] = FakeResponse(200, text="OLD=1")
    run(monkeypatch, "--keep-env")
    assert "/home/tester/projects/prd/.env" not in uploads(api)


def test_wsgi_file_lands_in_var_www_and_imports_the_app(api, monkeypatch):
    run(monkeypatch)
    wsgi = uploads(api)["/var/www/tester_pythonanywhere_com_wsgi.py"]
    assert "/home/tester/projects/prd" in wsgi
    assert "from wsgi import application" in wsgi


def test_static_mappings_are_created(api, monkeypatch):
    run(monkeypatch)
    mappings = [c["kwargs"]["data"] for c in api.calls
                if c["key"] == f"POST /webapps/{DOMAIN}/static_files/"]
    assert {"url": "/sites/", "path": "/home/tester/prd-data/sites"} in mappings
    assert {"url": "/static/", "path": "/home/tester/projects/prd/prd_app/static"} in mappings


def test_existing_mappings_are_left_alone(api, monkeypatch):
    api.responses[f"GET /webapps/{DOMAIN}/static_files/"] = FakeResponse(200, [
        {"url": "/sites/", "path": "/home/tester/prd-data/sites"},
        {"url": "/static/", "path": "/home/tester/projects/prd/prd_app/static"},
    ])
    run(monkeypatch)
    assert not any(c["key"] == f"POST /webapps/{DOMAIN}/static_files/" for c in api.calls)


def test_the_data_directory_is_created(api, monkeypatch):
    run(monkeypatch)
    assert "/home/tester/prd-data/sites/.prd-keep" in uploads(api)


def test_an_unrelated_existing_webapp_is_not_hijacked(api, monkeypatch, capsys):
    api.responses["GET /webapps/"] = FakeResponse(200, [
        {"domain_name": DOMAIN, "source_directory": "/home/tester/my-other-site"},
    ])
    with pytest.raises(SystemExit) as exit_info:
        run(monkeypatch)
    assert exit_info.value.code == 1
    assert "--replace-webapp" in capsys.readouterr().err
    assert not any(c["key"].startswith("POST /files/path/var/www") for c in api.calls)


def test_replace_flag_allows_repointing_an_existing_webapp(api, monkeypatch):
    api.responses["GET /webapps/"] = FakeResponse(200, [
        {"domain_name": DOMAIN, "source_directory": "/home/tester/my-other-site"},
    ])
    assert run(monkeypatch, "--replace-webapp") == 0
    assert "POST /webapps/" not in [c["key"] for c in api.calls]   # reused, not recreated


def test_a_prd_webapp_is_reused_without_the_flag(api, monkeypatch):
    api.responses["GET /webapps/"] = FakeResponse(200, [
        {"domain_name": DOMAIN, "source_directory": "/home/tester/projects/prd"},
    ])
    assert run(monkeypatch) == 0


def test_a_rejected_token_stops_before_anything_is_changed(api, monkeypatch, capsys):
    api.responses["GET /cpu/"] = FakeResponse(401)
    with pytest.raises(SystemExit):
        run(monkeypatch)
    assert "not accepted" in capsys.readouterr().err
    assert not any(c["key"].startswith("POST") for c in api.calls)


def test_the_other_api_host_is_tried_automatically(api, monkeypatch):
    """www rejects the token, eu accepts it — the script should not give up."""
    ok = FakeResponse(200, {"daily_cpu_limit_seconds": 100})
    api.responses["GET /cpu/"] = [FakeResponse(401), ok, ok, ok]
    monkeypatch.setattr(sys, "argv", [
        "deploy_pythonanywhere.py", "--token", "t", "--username", "tester",
    ])
    assert script.main() == 0
    env = uploads(api)["/home/tester/projects/prd/.env"]
    assert "PYTHONANYWHERE_HOST=eu.pythonanywhere.com" in env


def test_dry_run_changes_nothing(api, monkeypatch, capsys):
    assert run(monkeypatch, "--dry-run") == 0
    methods = {call["key"].split(" ", 1)[0] for call in api.calls}
    assert methods == {"GET"}, f"a dry run must only read, got {methods}"
    output = capsys.readouterr().out
    assert "would create the web app" in output.lower() or "would create it" in output.lower()
    assert "nothing on your account was touched" in output


def test_dry_run_does_not_print_the_generated_secrets(api, monkeypatch, capsys):
    run(monkeypatch, "--dry-run", "--admin-password", "top-secret-pw")
    output = capsys.readouterr().out
    assert "top-secret-pw" not in output
    assert "tok-not-real" not in output
    assert "PRD_SECRET_KEY=<generated>" in output


def test_dry_run_still_reports_a_conflicting_webapp(api, monkeypatch, capsys):
    api.responses["GET /webapps/"] = FakeResponse(200, [
        {"domain_name": DOMAIN, "source_directory": "/home/tester/something-else"},
    ])
    with pytest.raises(SystemExit):
        run(monkeypatch, "--dry-run")
    assert "--replace-webapp" in capsys.readouterr().err
