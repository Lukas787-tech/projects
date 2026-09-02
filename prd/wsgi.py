"""WSGI entry point for PythonAnywhere.

In the PythonAnywhere "Web" tab, point the WSGI configuration file at this
module, or paste its contents in. Configuration comes from environment
variables -- see .env.example and the README.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent
if str(PROJECT_DIR) not in sys.path:
    sys.path.insert(0, str(PROJECT_DIR))

# PythonAnywhere does not export the Web tab's environment variables to the
# WSGI process automatically, so load a .env file next to this one if present.
ENV_FILE = PROJECT_DIR / ".env"
if ENV_FILE.exists():
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))

from prd_app import create_app  # noqa: E402

application = create_app()
