"""Write sites to the local filesystem; Flask serves them from /s/<slug>."""
from __future__ import annotations

import shutil
from pathlib import Path

from .base import DeployResult, Deployer


class LocalDeployer(Deployer):
    name = "local"

    def __init__(self, sites_root: Path, base_url: str = "") -> None:
        self.sites_root = Path(sites_root)
        self.base_url = base_url.rstrip("/")

    def _dir(self, slug: str) -> Path:
        return self.sites_root / slug

    def public_url(self, slug: str) -> str:
        return f"{self.base_url}/{slug}" if self.base_url else f"/{slug}"

    def deploy(self, slug: str, html: str) -> DeployResult:
        try:
            target = self._dir(slug)
            target.mkdir(parents=True, exist_ok=True)
            (target / "index.html").write_text(html, encoding="utf-8")
        except OSError as exc:
            return DeployResult(False, detail=f"Could not write the site: {exc}", target=self.name)
        return DeployResult(True, url=self.public_url(slug), detail="Written to disk", target=self.name)

    def remove(self, slug: str) -> DeployResult:
        try:
            shutil.rmtree(self._dir(slug), ignore_errors=True)
        except OSError as exc:  # pragma: no cover - rmtree already ignores errors
            return DeployResult(False, detail=str(exc), target=self.name)
        return DeployResult(True, detail="Removed from disk", target=self.name)

    def status(self) -> dict:
        return {
            "target": self.name,
            "ready": True,
            "detail": f"Serving from {self.sites_root}",
        }
