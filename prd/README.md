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
python -m pytest -q          # 166 tests, no network needed
```

---

## Deploy it on PythonAnywhere

### The one-command way

Open a **Bash console** on PythonAnywhere and run:

```bash
git clone -b claude/prd-website-builder-ng238a https://github.com/Lukas787-tech/projects.git
cd projects/prd
pip3.10 install --user -r requirements.txt

export PYTHONANYWHERE_API_TOKEN=your-token-here
python3.10 deploy_pythonanywhere.py --dry-run          # see the plan first
python3.10 deploy_pythonanywhere.py --admin-password 'pick-something-private'
```

`deploy_pythonanywhere.py` does the rest through the API: creates the web app if
you don't have one, points it at the project, writes a `.env` with freshly
generated secrets, creates the `prd-data` directory, writes
`/var/www/<domain>_wsgi.py`, maps `/sites/` and `/static/` so nginx serves them
without waking Python, and reloads. It prints your live URL at the end.

It will not quietly take over an existing web app: if `yourname.pythonanywhere.com`
already serves something else it stops and tells you to pass `--replace-webapp`.

Useful flags:

| Flag | Effect |
|---|---|
| `--upload` | Push the project files over the API — use this to deploy **from your own computer** instead of a PythonAnywhere console (it is the default when the script isn't running on PythonAnywhere) |
| `--auto-approve` | Publish requests instantly instead of queueing them for review |
| `--virtualenv /home/you/.virtualenvs/prd` | Attach a virtualenv to the web app |
| `--python-version 3.11` | Python version for a newly created web app (translated to the API's `python311` form for you) |
| `--keep-env` | Leave an existing `.env` untouched |
| `--dry-run` | Print exactly what would happen and make only read-only API calls |
| `--replace-webapp` | Repoint an existing web app at PRD |

Create the API token at
<https://www.pythonanywhere.com/account/#api_token>. **Never commit it** — the
script writes it to `.env`, which is git-ignored. Rotate it if it ever leaks.

### Doing it by hand

If you would rather click through the Web tab:

1. Clone the repo and `pip install -r requirements.txt` as above.
2. **Web** tab → *Add a new web app* → **Manual configuration** → Python 3.10.
3. Source code: `/home/YOURNAME/projects/prd`. WSGI configuration file:

   ```python
   import sys
   sys.path.insert(0, "/home/YOURNAME/projects/prd")
   from wsgi import application  # noqa
   ```
4. Copy `.env.example` to `.env` and fill it in (`wsgi.py` loads it).
5. Add a static file mapping: URL `/sites/` → `/home/YOURNAME/prd-data/sites`.
6. Hit **Reload**.

### What the API is used for

`prd_app/deploy/pythonanywhere.py` uses these endpoints:

| Call | Why |
|---|---|
| `POST /api/v0/user/<user>/files/path/<path>` | Upload the rendered `index.html` for a site (and, during deployment, the project itself) |
| `GET`/`POST` `/api/v0/user/<user>/webapps/` | Find or create the web app |
| `PATCH /api/v0/user/<user>/webapps/<domain>/` | Point it at the project directory |
| `GET`/`POST` `/api/v0/user/<user>/webapps/<domain>/static_files/` | Map `/sites/` to the sites directory, so nginx serves published pages without waking Python |
| `POST /api/v0/user/<user>/webapps/<domain>/reload/` | Apply a new mapping or a new deploy |

When PRD is running *on* PythonAnywhere it writes published sites straight to
disk and only uses the API for the mapping and reload — faster, and it burns no
API quota. Server errors and rate limits are retried four times with exponential
backoff, and a deploy failure is reported in the dashboard rather than raised.

Every site is reachable two ways: `/s/<slug>` (served by the app, always correct)
and `/sites/<slug>/` (served by nginx from the deployed file).

### If the site 500s

Check `https://www.pythonanywhere.com/user/YOURNAME/files/var/log/YOURNAME.pythonanywhere.com.error.log`.
Nine times out of ten it is Flask missing from the Python the web app runs:

```bash
pip3.10 install --user Flask requests
```

then Reload.

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
├── deploy_pythonanywhere.py  one-command deploy through the API
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
└── tests/                  166 tests
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
