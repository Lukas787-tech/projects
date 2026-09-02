#!/usr/bin/env python3
"""Run PRD locally: python run.py  ->  http://127.0.0.1:5000"""
from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

# Sensible local defaults so `python run.py` works with no configuration at all.
DATA_DIR = Path(__file__).resolve().parent / ".prd-data"
os.environ.setdefault("PRD_DB_PATH", str(DATA_DIR / "prd.sqlite3"))
os.environ.setdefault("PRD_SITES_ROOT", str(DATA_DIR / "sites"))
os.environ.setdefault("PRD_BASE_URL", "http://127.0.0.1:5000")
os.environ.setdefault("PRD_ADMIN_PASSWORD", "admin")
os.environ.setdefault("PRD_SECRET_KEY", "dev-secret-not-for-production")
os.environ.setdefault("PRD_IP_SALT", "dev-salt")
os.environ.setdefault("PRD_AUTO_APPROVE", "1")
os.environ.setdefault("PRD_DEPLOY_TARGET", "local")

from prd_app import create_app  # noqa: E402

app = create_app()

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    print(f"PRD running on http://127.0.0.1:{port}  (admin password: "
          f"{os.environ['PRD_ADMIN_PASSWORD']})")
    app.run(host="127.0.0.1", port=port, debug=bool(os.environ.get("PRD_DEBUG")))
