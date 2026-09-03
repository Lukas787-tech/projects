#!/bin/sh
# Update a deployed PRD: pull the new code and reload the web app.
#
#   ./update.sh
#
# Credentials come from .env, so there is nothing to paste. Existing sites,
# secrets and the owner password are left alone.
set -e
cd "$(dirname "$0")/.."
git pull
cd prd
exec python3.10 deploy_pythonanywhere.py --keep-env "$@"
