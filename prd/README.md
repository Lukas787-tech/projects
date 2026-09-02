# ⚡ PRD — Personalise · Request · Deploy

A no-code website builder that runs on PythonAnywhere.

Someone lands on the site, picks a preset (Discord server ad, personal bio page,
company site, …), drags blocks around in a clean animated editor until it looks
right, then **requests** it. You approve the request from an owner dashboard and
PRD **deploys** it as plain, fast HTML. No accounts anywhere — not for you, not
for them.

```
Personalise ─────────────► Request ─────────────► Deploy
 drag-and-drop editor       rate-limited queue     rendered HTML on
 10 presets, 23 blocks      no sign-up needed      PythonAnywhere
```

---

## What's in the box

| | |
|---|---|
| **Editor** | Live preview, click-to-select on the page, drag blocks in from the sidebar, layers panel, undo/redo, autosave, phone/tablet/desktop preview, keyboard shortcuts |
| **Blocks** | 23 sections — hero, Discord invite card, link buttons, social icons, features, stats, gallery, pricing, testimonials, FAQ, timeline, people, CTA, contact, embeds, marquee, countdown, nav, footer, and more |
| **Presets** | Discord server · personal bio · company · portfolio · product launch · event · gaming clan · CV · coming soon · blank |
| **Themes** | 9 palettes, 9 fonts, custom colours, corner radius, page width, spacing, background textures, motion toggle |
| **Requests** | No account. Per-visitor rate limits, honeypot + timing bot checks, a private manage link per site |
| **Gallery** | Public sites with live thumbnails, sort by new/visits/remixes, one-click **Remix** into your own editor |
| **Owner dashboard** | Request queue, approve/reject with a reason, redeploy, take offline, delete, block a visitor, activity log |
| **Deploy** | Local disk or the PythonAnywhere API (file upload → static-file mapping → web app reload) |

Everything a visitor types is escaped at render time, URLs are scheme-checked,
and embeds come from a fixed allow-list (YouTube, Spotify, SoundCloud, Vimeo,
Google Maps, Discord widget). Published pages are served with a strict CSP.

---

## Run it locally

```bash
cd prd
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python run.py                                     # http://127.0.0.1:5000
```

`run.py` sets development defaults for you: a SQLite database in `prd/.prd-data/`,
local deploys, auto-approve on, and the owner password `admin`
(dashboard at <http://127.0.0.1:5000/admin>).

Run the tests with:

```bash
python -m pytest -q          # 134 tests, no network needed
```

---

## Deploy it on PythonAnywhere

### 1. Get the code onto PythonAnywhere

In a Bash console:

```bash
git clone https://github.com/Lukas787-tech/projects.git
cd projects/prd
mkvirtualenv prd --python=/usr/bin/python3.11
pip install -r requirements.txt
mkdir -p ~/prd-data/sites
```

### 2. Create the web app

**Web** tab → *Add a new web app* → **Manual configuration** → Python 3.11.

* **Source code:** `/home/YOURNAME/projects/prd`
* **Virtualenv:** `/home/YOURNAME/.virtualenvs/prd`
* **WSGI configuration file:** replace its contents with

  ```python
  import sys
  sys.path.insert(0, "/home/YOURNAME/projects/prd")
  from wsgi import application  # noqa
  ```

### 3. Configure it

Create `/home/YOURNAME/projects/prd/.env` (it is git-ignored; `wsgi.py` loads it):

```ini
PRD_SECRET_KEY=<python -c "import secrets;print(secrets.token_hex(32))">
PRD_ADMIN_PASSWORD=<something only you know>
PRD_IP_SALT=<another random string>
PRD_DB_PATH=/home/YOURNAME/prd-data/prd.sqlite3
PRD_SITES_ROOT=/home/YOURNAME/prd-data/sites
PRD_BASE_URL=https://YOURNAME.pythonanywhere.com
PRD_DEPLOY_TARGET=pythonanywhere
PRD_AUTO_APPROVE=0

PYTHONANYWHERE_API_TOKEN=<from the API token page>
PYTHONANYWHERE_USERNAME=YOURNAME
PYTHONANYWHERE_HOST=www.pythonanywhere.com     # eu.pythonanywhere.com for EU accounts
PYTHONANYWHERE_DOMAIN=YOURNAME.pythonanywhere.com
PYTHONANYWHERE_SITES_DIR=/home/YOURNAME/prd-data/sites
PYTHONANYWHERE_SITES_URL=/sites/
```

Create the API token at
<https://www.pythonanywhere.com/account/#api_token>. **Never commit it** — keep it
in `.env` or the Web tab's environment variables, and rotate it if it leaks.

### 4. Reload

Hit **Reload** on the Web tab. Visit your domain, build something, and approve it
from `/admin`.

### What the API is used for

`prd_app/deploy/pythonanywhere.py` uses three endpoints:

| Call | Why |
|---|---|
| `POST /api/v0/user/<user>/files/path/<path>` | Upload the rendered `index.html` for a site |
| `GET`/`POST` `/api/v0/user/<user>/webapps/<domain>/static_files/` | Map `/sites/` to the sites directory once, so nginx serves published pages without waking Python |
| `POST /api/v0/user/<user>/webapps/<domain>/reload/` | Pick up that new mapping |

When PRD is running *on* PythonAnywhere it writes the files directly to disk and
only uses the API for the mapping and reload — faster, and it burns no API quota.
Server errors and rate limits are retried four times with exponential backoff,
and a deploy failure is reported in the dashboard rather than raised.

Every site is reachable two ways: `/s/<slug>` (served by the app, always correct)
and `/sites/<slug>/` (served by nginx from the deployed file).

---

## Configuration reference

| Variable | Default | What it does |
|---|---|---|
| `PRD_SECRET_KEY` | random per boot | Signs the owner session cookie — set it, or logins drop on every reload |
| `PRD_ADMIN_PASSWORD` | *(empty)* | Password for `/admin`. Empty disables the dashboard entirely |
| `PRD_IP_SALT` | `prd-default-salt` | Salt for hashing visitor IPs. Raw addresses are never stored |
| `PRD_DB_PATH` | `~/prd-data/prd.sqlite3` | SQLite database |
| `PRD_SITES_ROOT` | `~/prd-data/sites` | Where rendered sites are written |
| `PRD_BASE_URL` | *(empty)* | Public base URL, used to build shareable links |
| `PRD_DEPLOY_TARGET` | `auto` | `auto` \| `local` \| `pythonanywhere` |
| `PRD_AUTO_APPROVE` | `0` | `1` publishes instantly instead of queueing for review |
| `PRD_LIMIT_PUBLISH_HOUR` | `3` | New sites per visitor per hour |
| `PRD_LIMIT_PUBLISH_DAY` | `8` | New sites per visitor per day |
| `PRD_LIMIT_UPDATE_HOUR` | `20` | Edits per visitor per hour |
| `PRD_LIMIT_PREVIEW_MINUTE` | `90` | Live-preview renders per visitor per minute |
| `PRD_LIMIT_GLOBAL_DAY` | `250` | New sites across the whole instance per day |

All PythonAnywhere variables are listed in [`.env.example`](.env.example).

---

## How a site is stored

A site is one JSON document — no HTML is ever stored:

```json
{
  "version": 1,
  "meta":   {"title": "Nebula", "description": "…", "favicon": "💬"},
  "theme":  {"palette": "royal", "font": "outfit", "radius": 18, "effect": "none"},
  "blocks": [
    {"id": "b1a2", "type": "hero", "props": {"title": "…", "buttons": [...]}},
    {"id": "b3c4", "type": "discord", "props": {"invite": "https://discord.gg/…"}}
  ]
}
```

`prd_app/document.py` validates it (unknown blocks and props are dropped, selects
fall back to defaults, URLs are scheme-checked, sizes are capped) and
`prd_app/render.py` turns it into a standalone HTML file with inline CSS and a
small runtime script. The same renderer powers the editor preview, so what you
see while building is byte-for-byte what gets deployed.

Anyone can export their design as `.json` from the editor's **Page** tab and load
it back later.

---

## Adding a new block

Blocks are data, not templates. Two steps:

1. Add an entry to `BLOCK_LIST` in `prd_app/blocks.py` describing its fields.
2. Add a `r_<type>(props, ctx)` function to `RENDERERS` in `prd_app/render.py`,
   plus its CSS in `BLOCK_CSS`.

The editor picks it up automatically — the block library, the inspector form and
validation are all generated from that one definition.

---

## Project layout

```
prd/
├── run.py                  local dev server
├── wsgi.py                 PythonAnywhere entry point
├── requirements.txt
├── .env.example
├── prd_app/
│   ├── __init__.py         app factory, error pages, security headers
│   ├── config.py           environment-driven configuration
│   ├── db.py  schema.sql   SQLite layer
│   ├── models.py           sites, requests, gallery, audit log
│   ├── blocks.py           block + field registry (the single source of truth)
│   ├── document.py         validation, sanitising, themes, slugs
│   ├── presets.py          ten ready-made sites
│   ├── render.py           document → standalone HTML
│   ├── ratelimit.py        sliding-window limits
│   ├── security.py         IP hashing, manage tokens
│   ├── services.py         the personalise → request → deploy pipeline
│   ├── api.py views.py admin.py
│   ├── deploy/             local + PythonAnywhere deployers
│   ├── static/             app CSS/JS (editor is vanilla JS, no build step)
│   └── templates/
└── tests/                  134 tests
```

No build tooling, no npm, no CDN dependency beyond Google Fonts — which degrades
to system fonts if it is unreachable.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/schema` | Block registry, palettes, fonts — what the editor is built from |
| `GET` | `/api/presets` · `/api/presets/<id>` | Preset list and documents |
| `POST` | `/api/preview` | Render a document to HTML without saving it |
| `GET` | `/api/slug?slug=` | Address validation and availability |
| `POST` | `/api/publish` | Request a new site → returns the manage token |
| `GET` | `/api/sites/<slug>?t=` | Status of a site you hold the token for |
| `POST` | `/api/sites/<slug>/update` · `/offline` · `/delete` | Manage your own site |
| `GET` | `/api/gallery` | Public live sites |
| `GET` | `/api/templates/<slug>` | A public site's document, to remix |
| `GET` | `/healthz` | Health check |
