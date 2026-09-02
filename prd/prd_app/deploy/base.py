"""Deployer interface shared by every target."""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class DeployResult:
    ok: bool
    url: str = ""
    detail: str = ""
    target: str = ""
    extra: dict = field(default_factory=dict)


class Deployer:
    """Writes a rendered site somewhere the public can reach it."""

    name = "base"

    def deploy(self, slug: str, html: str) -> DeployResult:  # pragma: no cover - interface
        raise NotImplementedError

    def remove(self, slug: str) -> DeployResult:  # pragma: no cover - interface
        raise NotImplementedError

    def status(self) -> dict:
        return {"target": self.name, "ready": True, "detail": ""}
