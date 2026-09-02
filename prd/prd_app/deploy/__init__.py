"""Deployment targets."""
from __future__ import annotations

from flask import current_app

from .base import DeployResult, Deployer
from .local import LocalDeployer
from .pythonanywhere import PythonAnywhereClient, PythonAnywhereDeployer, PythonAnywhereError

__all__ = [
    "DeployResult", "Deployer", "LocalDeployer", "PythonAnywhereClient",
    "PythonAnywhereDeployer", "PythonAnywhereError", "get_deployer", "build_deployer",
]


def build_deployer(config) -> Deployer:
    """Pick a deployer from configuration.

    ``auto`` uses PythonAnywhere when a token is configured and the local disk
    otherwise, so development works with no credentials at all.
    """
    target = (config.deploy_target or "auto").lower()
    if target == "local":
        return LocalDeployer(config.sites_root, config.base_url)
    if target == "pythonanywhere":
        return PythonAnywhereDeployer(config.pythonanywhere, config.base_url)
    if config.pythonanywhere.configured:
        return PythonAnywhereDeployer(config.pythonanywhere, config.base_url)
    return LocalDeployer(config.sites_root, config.base_url)


def get_deployer() -> Deployer:
    """The app-wide deployer, built once per process."""
    deployer = current_app.extensions.get("prd_deployer")
    if deployer is None:
        deployer = build_deployer(current_app.config["PRD_CONFIG"])
        current_app.extensions["prd_deployer"] = deployer
    return deployer
