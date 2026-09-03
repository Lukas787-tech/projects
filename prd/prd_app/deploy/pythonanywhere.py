"""Deploy to PythonAnywhere through its REST API.

Three API calls make a published site real:

1. ``POST /files/path/<path>``      -- upload the rendered HTML,
2. ``POST /webapps/<domain>/static_files/`` -- map a URL prefix to that folder
   once, so nginx serves the sites without waking Python (free-tier CPU
   seconds are worth protecting),
3. ``POST /webapps/<domain>/reload/`` -- pick up a new mapping.

When PRD itself runs on PythonAnywhere the upload step is skipped in favour of
a direct local write, which is faster and uses no API quota; the mapping and
reload still go through the API.
"""
from __future__ import annotations

import os
import time
from pathlib import Path

import requests

from ..config import PythonAnywhereConfig
from .base import DeployResult, Deployer

RETRY_STATUS = {429, 500, 502, 503, 504}
MAX_ATTEMPTS = 4


class PythonAnywhereError(RuntimeError):
    pass


class PythonAnywhereClient:
    """Thin, retrying wrapper around the PythonAnywhere v0 API."""

    def __init__(self, config: PythonAnywhereConfig, session: requests.Session | None = None) -> None:
        self.config = config
        self.session = session or requests.Session()
        self.session.headers.update({
            "Authorization": f"Token {config.token}",
            "User-Agent": "PRD/1.0 (+https://github.com/Lukas787-tech/projects)",
        })

    # -- plumbing ----------------------------------------------------------
    def request(self, method: str, path: str, **kwargs) -> requests.Response:
        url = f"{self.config.api_base}{path}"
        kwargs.setdefault("timeout", self.config.timeout)
        last_error = ""
        for attempt in range(MAX_ATTEMPTS):
            try:
                response = self.session.request(method, url, **kwargs)
            except requests.RequestException as exc:
                last_error = f"network error: {type(exc).__name__}"
            else:
                if response.status_code not in RETRY_STATUS:
                    return response
                last_error = f"HTTP {response.status_code}"
            if attempt < MAX_ATTEMPTS - 1:
                time.sleep(2 ** attempt)
        raise PythonAnywhereError(f"{method} {path} failed after {MAX_ATTEMPTS} attempts ({last_error})")

    # -- endpoints ---------------------------------------------------------
    def whoami(self) -> dict:
        response = self.request("GET", "/cpu/")
        if response.status_code == 401:
            raise PythonAnywhereError("PythonAnywhere rejected the API token (401).")
        if response.status_code >= 400:
            raise PythonAnywhereError(f"PythonAnywhere returned HTTP {response.status_code} for /cpu/.")
        return response.json() if response.content else {}

    def upload(self, remote_path: str, content: str | bytes) -> None:
        payload = content.encode("utf-8") if isinstance(content, str) else content
        name = remote_path.rsplit("/", 1)[-1] or "index.html"
        response = self.request(
            "POST",
            f"/files/path{remote_path}",
            files={"content": (name, payload, "application/octet-stream")},
        )
        if response.status_code not in (200, 201):
            raise PythonAnywhereError(
                f"Upload of {remote_path} failed with HTTP {response.status_code}: {response.text[:200]}"
            )

    def delete(self, remote_path: str) -> bool:
        response = self.request("DELETE", f"/files/path{remote_path}")
        return response.status_code in (200, 204, 404)

    def read_file(self, remote_path: str) -> bytes | None:
        response = self.request("GET", f"/files/path{remote_path}")
        if response.status_code == 404:
            return None
        if response.status_code >= 400:
            raise PythonAnywhereError(f"Could not read {remote_path} (HTTP {response.status_code}).")
        return response.content

    def create_webapp(self, domain: str, python_version: str) -> dict:
        response = self.request(
            "POST", "/webapps/",
            data={"domain_name": domain, "python_version": python_version},
        )
        if response.status_code not in (200, 201):
            raise PythonAnywhereError(
                f"Could not create the web app (HTTP {response.status_code}): {response.text[:300]}"
            )
        return response.json() if response.content else {}

    def update_webapp(self, domain: str, **fields: str) -> dict:
        response = self.request("PATCH", f"/webapps/{domain}/", data=fields)
        if response.status_code not in (200, 201):
            raise PythonAnywhereError(
                f"Could not configure the web app (HTTP {response.status_code}): {response.text[:300]}"
            )
        return response.json() if response.content else {}

    def webapps(self) -> list[dict]:
        response = self.request("GET", "/webapps/")
        if response.status_code >= 400:
            raise PythonAnywhereError(f"Could not list web apps (HTTP {response.status_code}).")
        return response.json() or []

    def static_mappings(self, domain: str) -> list[dict]:
        response = self.request("GET", f"/webapps/{domain}/static_files/")
        if response.status_code >= 400:
            raise PythonAnywhereError(f"Could not read static mappings (HTTP {response.status_code}).")
        return response.json() or []

    def add_static_mapping(self, domain: str, url: str, path: str) -> None:
        response = self.request(
            "POST", f"/webapps/{domain}/static_files/", data={"url": url, "path": path}
        )
        if response.status_code not in (200, 201):
            raise PythonAnywhereError(
                f"Could not add the static mapping (HTTP {response.status_code}): {response.text[:200]}"
            )

    def reload(self, domain: str) -> None:
        response = self.request("POST", f"/webapps/{domain}/reload/")
        if response.status_code >= 400:
            raise PythonAnywhereError(f"Reload failed (HTTP {response.status_code}).")


class PythonAnywhereDeployer(Deployer):
    name = "pythonanywhere"

    def __init__(self, config: PythonAnywhereConfig, base_url: str = "",
                 client: PythonAnywhereClient | None = None) -> None:
        self.config = config
        self.base_url = base_url.rstrip("/")
        self.client = client or PythonAnywhereClient(config)
        self._mapping_checked = False

    # -- helpers -----------------------------------------------------------
    def remote_dir(self, slug: str) -> str:
        return f"{self.config.sites_dir}/{slug}"

    def static_url(self, slug: str) -> str:
        return f"https://{self.config.domain}{self.config.sites_url}{slug}/"

    def public_url(self, slug: str) -> str:
        """Canonical link we hand to the creator (always served by the app)."""
        base = self.base_url or (f"https://{self.config.domain}" if self.config.domain else "")
        return f"{base}/{slug}" if base else f"/{slug}"

    def _write_locally(self, slug: str, html: str) -> bool:
        """If the sites directory is on this machine, skip the upload."""
        parent = Path(self.config.sites_dir)
        try:
            if not parent.exists() or not os.access(parent, os.W_OK):
                return False
            target = parent / slug
            target.mkdir(parents=True, exist_ok=True)
            (target / "index.html").write_text(html, encoding="utf-8")
            (parent / f"{slug}.html").write_text(html, encoding="utf-8")
            return True
        except OSError:
            return False

    def ensure_static_mapping(self) -> str:
        """Map <sites_url> to the sites directory once. Returns a status note."""
        if self._mapping_checked or not self.config.domain:
            return ""
        wanted_url, wanted_path = self.config.sites_url, self.config.sites_dir
        mappings = self.client.static_mappings(self.config.domain)
        for mapping in mappings:
            if mapping.get("url") == wanted_url:
                self._mapping_checked = True
                if (mapping.get("path") or "").rstrip("/") != wanted_path:
                    return (f"Warning: {wanted_url} already maps to {mapping.get('path')} "
                            f"on PythonAnywhere, not {wanted_path}.")
                return ""
        self.client.add_static_mapping(self.config.domain, wanted_url, wanted_path)
        self.client.reload(self.config.domain)
        self._mapping_checked = True
        return f"Created static mapping {wanted_url} -> {wanted_path} and reloaded the web app."

    # -- Deployer ----------------------------------------------------------
    def deploy(self, slug: str, html: str) -> DeployResult:
        notes: list[str] = []
        try:
            if self._write_locally(slug, html):
                notes.append("Written directly to the PythonAnywhere disk.")
            else:
                self.client.upload(f"{self.remote_dir(slug)}/index.html", html)
                self.client.upload(f"{self.config.sites_dir}/{slug}.html", html)
                notes.append("Uploaded via the PythonAnywhere Files API.")
            mapping_note = self.ensure_static_mapping()
            if mapping_note:
                notes.append(mapping_note)
        except PythonAnywhereError as exc:
            return DeployResult(False, detail=str(exc), target=self.name)

        return DeployResult(
            True,
            url=self.public_url(slug),
            detail=" ".join(notes),
            target=self.name,
            extra={"static_url": self.static_url(slug),
                   "flat_url": f"https://{self.config.domain}{self.config.sites_url}{slug}.html"},
        )

    def remove(self, slug: str) -> DeployResult:
        errors = []
        for path in (f"{self.remote_dir(slug)}/index.html",
                     f"{self.config.sites_dir}/{slug}.html",
                     self.remote_dir(slug)):
            try:
                self.client.delete(path)
            except PythonAnywhereError as exc:
                errors.append(str(exc))
        local = Path(self.config.sites_dir) / slug
        if local.exists():
            import shutil

            shutil.rmtree(local, ignore_errors=True)
            (Path(self.config.sites_dir) / f"{slug}.html").unlink(missing_ok=True)
        if errors:
            return DeployResult(False, detail="; ".join(errors)[:400], target=self.name)
        return DeployResult(True, detail="Removed from PythonAnywhere", target=self.name)

    def status(self) -> dict:
        info = {"target": self.name, "ready": False, "detail": "", "domain": self.config.domain,
                "username": self.config.username, "sites_dir": self.config.sites_dir}
        if not self.config.configured:
            info["detail"] = "No API token configured."
            return info
        try:
            self.client.whoami()
        except PythonAnywhereError as exc:
            info["detail"] = str(exc)
            return info
        info["ready"] = True
        info["detail"] = f"Connected as {self.config.username} on {self.config.host}."
        return info
