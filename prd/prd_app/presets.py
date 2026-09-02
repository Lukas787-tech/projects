"""Starting points.

Each preset is a complete document, so "pick a preset" means "here is a
finished site you can now change", which is much friendlier than a blank page.
"""
from __future__ import annotations

import copy
from typing import Any

from .blocks import new_block


def _doc(title: str, favicon: str, description: str, theme: dict, blocks: list[dict]) -> dict:
    return {
        "version": 1,
        "meta": {"title": title, "description": description, "favicon": favicon, "og_image": ""},
        "theme": theme,
        "blocks": blocks,
    }


def _theme(palette: str, **kwargs: Any) -> dict:
    base = {"palette": palette, "font": "inter", "heading_font": "inter",
            "radius": 18, "width": 1080, "spacing": "normal", "effect": "none", "animations": True}
    base.update(kwargs)
    return base


# ---------------------------------------------------------------------------

DISCORD = _doc(
    "Nebula — Discord Server", "💬",
    "A friendly Discord community for gamers, artists and night owls.",
    _theme("royal", font="outfit", heading_font="outfit", effect="none"),
    [
        new_block("nav", brand="Nebula", links=[{"label": "About", "url": "#about"},
                                                {"label": "Perks", "url": "#perks"},
                                                {"label": "FAQ", "url": "#faq"}],
                  cta_label="Join", cta_url="https://discord.gg/"),
        new_block("hero", eyebrow="Now 1,200+ members", title="A Discord that actually feels like home",
                  subtitle="Daily events, active voice chats, zero drama. Come say hi — we don't bite.",
                  layout="center", glow="aurora",
                  buttons=[{"label": "Join the server", "url": "https://discord.gg/", "style": "primary"},
                           {"label": "See the rules", "url": "#faq", "style": "ghost"}]),
        new_block("discord", name="Nebula", tagline="Gaming • Art • Music • Late night chaos",
                  invite="https://discord.gg/", cta="Join the server",
                  members="1,240", online="312", boosts="14", surface="soft"),
        new_block("features", heading="Why people stay", subheading="",
                  items=[{"icon": "🎮", "title": "Game nights", "text": "Minecraft, Valorant, Among Us — every Friday, no skill required."},
                         {"icon": "🎨", "title": "Creative corner", "text": "Share art, music and builds. Feedback is kind here."},
                         {"icon": "🛡️", "title": "Real moderation", "text": "Staff online around the clock. Report and it gets handled."},
                         {"icon": "🎁", "title": "Giveaways", "text": "Nitro and game keys most months."},
                         {"icon": "🎧", "title": "Chill VCs", "text": "Study rooms, music rooms, 3am talks."},
                         {"icon": "🤝", "title": "No gatekeeping", "text": "New members get a welcome, not a quiz."}],
                  columns="3"),
        new_block("stats", items=[{"value": "1,240", "label": "Members"},
                                  {"value": "312", "label": "Online now"},
                                  {"value": "48", "label": "Channels"},
                                  {"value": "24/7", "label": "Mods awake"}], surface="soft"),
        new_block("gallery", heading="Inside the server", columns="3", shape="wide", items=[]),
        new_block("faq", heading="Before you join",
                  items=[{"q": "Is there an age requirement?", "a": "13+, per Discord's own terms. Be sensible."},
                         {"q": "Do I have to talk in VC?", "a": "Never. Lurking is a valid lifestyle."},
                         {"q": "What gets you banned?", "a": "Slurs, harassment, spam, NSFW outside the marked channels. That's it."},
                         {"q": "Can I advertise my server?", "a": "Only in #self-promo, and only if you're actually part of the community."}]),
        new_block("cta", heading="See you in there", text="One click and you're in. No forms, no verification maze.",
                  buttons=[{"label": "Join Nebula", "url": "https://discord.gg/", "style": "primary"}], surface="accent"),
        new_block("footer", text="© 2026 Nebula", show_badge=True,
                  socials=[{"platform": "discord", "url": "https://discord.gg/"},
                           {"platform": "youtube", "url": "https://youtube.com/"}]),
    ],
)

BIO = _doc(
    "your name — links", "✨",
    "Everything I make, in one place.",
    _theme("candy", font="dm", heading_font="poppins", effect="dots", radius=22, width=760),
    [
        new_block("hero", layout="avatar", eyebrow="", title="hey, i'm alex",
                  subtitle="designer by day, streamer by night. links below 👇",
                  image="", glow="beam", buttons=[], pad="l"),
        new_block("socials", size="l", pad="s",
                  items=[{"platform": "discord", "url": "https://discord.gg/"},
                         {"platform": "youtube", "url": "https://youtube.com/"},
                         {"platform": "instagram", "url": "https://instagram.com/"},
                         {"platform": "tiktok", "url": "https://tiktok.com/"}]),
        new_block("links", style="soft", pad="m",
                  items=[{"icon": "🎥", "label": "Latest video", "url": "https://youtube.com/", "note": "new"},
                         {"icon": "🛍️", "label": "My shop", "url": "https://example.com/", "note": ""},
                         {"icon": "💬", "label": "Discord server", "url": "https://discord.gg/", "note": "1.2k"},
                         {"icon": "☕", "label": "Buy me a coffee", "url": "https://example.com/", "note": ""}]),
        new_block("about", heading="about me", layout="stack", align="center",
                  body="22, based in berlin. i make **UI design** videos and play far too much *Rocket League*.\n\nbusiness enquiries below."),
        new_block("contact", heading="say hi", email="you@example.com", discord="yourtag", surface="soft"),
        new_block("footer", text="made with love", show_badge=True),
    ],
)

COMPANY = _doc(
    "Northlight — Studio", "🏢",
    "We design and build digital products for ambitious teams.",
    _theme("paper", font="inter", heading_font="playfair", effect="none", radius=14, width=1140),
    [
        new_block("nav", brand="Northlight", sticky=True,
                  links=[{"label": "Services", "url": "#services"}, {"label": "Work", "url": "#work"},
                         {"label": "Pricing", "url": "#pricing"}, {"label": "Contact", "url": "#contact"}],
                  cta_label="Book a call", cta_url="#contact"),
        new_block("hero", layout="split", eyebrow="Digital studio", title="Software that earns its keep",
                  subtitle="We help small teams ship products people actually use — strategy, design and engineering under one roof.",
                  glow="none", image="",
                  buttons=[{"label": "Start a project", "url": "#contact", "style": "primary"},
                           {"label": "See our work", "url": "#work", "style": "ghost"}]),
        new_block("stats", items=[{"value": "68", "label": "Projects shipped"},
                                  {"value": "12", "label": "Years"},
                                  {"value": "96%", "label": "Client retention"}], surface="soft"),
        new_block("features", heading="What we do", subheading="Three things, done properly.",
                  items=[{"icon": "🧭", "title": "Product strategy", "text": "Research, positioning and a roadmap you can defend to a board."},
                         {"icon": "🎨", "title": "Design", "text": "Interfaces that feel obvious. Design systems that survive contact with reality."},
                         {"icon": "⚙️", "title": "Engineering", "text": "Web and mobile builds, sensible architecture, documented handover."}],
                  columns="3"),
        new_block("gallery", heading="Selected work", columns="3", shape="square", items=[]),
        new_block("testimonials", heading="Clients", columns="3",
                  items=[{"quote": "They shipped in eight weeks what our last agency scoped for six months.", "author": "Priya N.", "role": "COO, Fieldwork", "avatar": ""},
                         {"quote": "The only team that pushed back on our bad ideas. Worth every euro.", "author": "Tom B.", "role": "Founder, Rally", "avatar": ""}],
                  surface="soft"),
        new_block("pricing", heading="Engagements", subheading="Fixed scope or ongoing — your call.",
                  items=[{"name": "Sprint", "price": "€6k", "period": "/ 2 weeks", "features": "Discovery workshop\nClickable prototype\nBuild-ready spec", "cta": "Book a sprint", "url": "#contact", "highlight": False},
                         {"name": "Build", "price": "€18k", "period": "/ month", "features": "Full design + engineering\nWeekly releases\nDedicated Slack channel\nHandover docs", "cta": "Talk to us", "url": "#contact", "highlight": True},
                         {"name": "Retainer", "price": "€4k", "period": "/ month", "features": "Ongoing improvements\nMonitoring & fixes\nQuarterly roadmap", "cta": "Enquire", "url": "#contact", "highlight": False}]),
        new_block("faq", heading="Questions",
                  items=[{"q": "How fast can you start?", "a": "Usually within two weeks. Discovery can start sooner."},
                         {"q": "Do you work with early-stage startups?", "a": "Yes — the Sprint package exists for exactly that."},
                         {"q": "Who owns the code?", "a": "You do, from day one."}]),
        new_block("contact", heading="Let's talk", text="Tell us what you're building. We reply within a day.",
                  email="hello@example.com", location="Berlin · Remote", surface="soft"),
        new_block("footer", text="© 2026 Northlight Studio",
                  links=[{"label": "Privacy", "url": "#"}, {"label": "Imprint", "url": "#"}],
                  socials=[{"platform": "linkedin", "url": "https://linkedin.com/"},
                           {"platform": "github", "url": "https://github.com/"}]),
    ],
)

PORTFOLIO = _doc(
    "Mara Ruiz — Portfolio", "🎨",
    "Selected design and photography work.",
    _theme("midnight", font="space", heading_font="space", effect="noise", radius=10, width=1180),
    [
        new_block("nav", brand="Mara Ruiz", links=[{"label": "Work", "url": "#work"}, {"label": "About", "url": "#about"}],
                  cta_label="Hire me", cta_url="#contact"),
        new_block("hero", layout="center", eyebrow="Art director · Barcelona",
                  title="I make brands look like they mean it",
                  subtitle="Identity, packaging and art direction for food, music and culture.",
                  glow="beam", buttons=[{"label": "View work", "url": "#work", "style": "primary"}]),
        new_block("marquee", text="branding • packaging • art direction • photography • type", speed="normal", surface="soft"),
        new_block("gallery", heading="Work", columns="2", shape="wide", items=[]),
        new_block("about", heading="About", layout="split", align="left",
                  body="Ten years in studios, four freelancing. I work in small, tight teams and I like projects where the constraints are real.\n\nPreviously: **Estudio Nube**, *Casa Mono*, and a lot of late nights."),
        new_block("timeline", heading="Path",
                  items=[{"date": "2016", "title": "Junior designer", "text": "Estudio Nube, Barcelona"},
                         {"date": "2019", "title": "Art director", "text": "Casa Mono"},
                         {"date": "2022", "title": "Independent", "text": "Working with clients across Europe"}]),
        new_block("contact", heading="Work with me", email="hola@example.com",
                  text="Available for projects from spring.", surface="soft"),
        new_block("footer", text="© 2026 Mara Ruiz",
                  socials=[{"platform": "instagram", "url": "https://instagram.com/"},
                           {"platform": "email", "url": "hola@example.com"}]),
    ],
)

PRODUCT = _doc(
    "Trailhead — Launch", "🚀",
    "The simplest way to plan your next hike.",
    _theme("forest", font="outfit", heading_font="outfit", effect="grid", radius=20),
    [
        new_block("nav", brand="Trailhead", links=[{"label": "Features", "url": "#features"}, {"label": "Pricing", "url": "#pricing"}],
                  cta_label="Get early access", cta_url="#cta"),
        new_block("hero", layout="split", eyebrow="Now in beta", title="Plan your hike in 30 seconds",
                  subtitle="Routes, weather, water sources and a share link your friends can actually read. No account needed.",
                  glow="aurora", buttons=[{"label": "Get early access", "url": "#cta", "style": "primary"},
                                          {"label": "How it works", "url": "#features", "style": "ghost"}]),
        new_block("features", heading="Everything in one place",
                  items=[{"icon": "🗺️", "title": "Smart routes", "text": "Elevation, surface and bail-out points, drawn automatically."},
                         {"icon": "🌦️", "title": "Real weather", "text": "Hour-by-hour along your route, not just at the trailhead."},
                         {"icon": "🔗", "title": "Share links", "text": "One link, works on any phone, no app install."},
                         {"icon": "📶", "title": "Offline packs", "text": "Download before you lose signal."}],
                  columns="4"),
        new_block("gallery", heading="", columns="3", shape="tall", items=[]),
        new_block("pricing", heading="Simple pricing",
                  items=[{"name": "Free", "price": "$0", "period": "forever", "features": "Unlimited routes\n3 offline packs\nShare links", "cta": "Start free", "url": "#cta", "highlight": False},
                         {"name": "Pro", "price": "$4", "period": "/month", "features": "Unlimited offline packs\nLive tracking\nGroup planning\nPriority support", "cta": "Go Pro", "url": "#cta", "highlight": True}]),
        new_block("testimonials", heading="Early testers",
                  items=[{"quote": "Replaced three apps for me.", "author": "Nils", "role": "Beta tester", "avatar": ""},
                         {"quote": "The share links alone are worth it.", "author": "Sara", "role": "Hiking club lead", "avatar": ""}],
                  columns="2", surface="soft"),
        new_block("cta", heading="Get early access", text="We're letting in 100 people a week.",
                  buttons=[{"label": "Join the waitlist", "url": "#", "style": "primary"}], surface="accent"),
        new_block("footer", text="© 2026 Trailhead"),
    ],
)

EVENT = _doc(
    "Signal Fest 2026", "🎪",
    "One day, four stages, a lot of noise.",
    _theme("ember", font="sora", heading_font="sora", effect="beam", radius=16),
    [
        new_block("nav", brand="Signal Fest", links=[{"label": "Line-up", "url": "#lineup"}, {"label": "Schedule", "url": "#schedule"}],
                  cta_label="Tickets", cta_url="#tickets"),
        new_block("hero", layout="center", eyebrow="18 July 2026 · Leipzig",
                  title="Signal Fest 2026", subtitle="Four stages. Twenty acts. One very long day.",
                  glow="beam", buttons=[{"label": "Get tickets", "url": "#tickets", "style": "primary"}]),
        new_block("countdown", heading="Doors open in", target="2026-07-18T10:00", done_text="We're open — come in!", surface="soft"),
        new_block("team", heading="Line-up", columns="4",
                  items=[{"name": "KOSMA", "role": "Main stage · 22:00", "photo": "", "url": ""},
                         {"name": "Vela", "role": "Main stage · 20:30", "photo": "", "url": ""},
                         {"name": "Duo Nero", "role": "Tent · 19:00", "photo": "", "url": ""},
                         {"name": "Ada Frost", "role": "Garden · 17:30", "photo": "", "url": ""}]),
        new_block("timeline", heading="Schedule",
                  items=[{"date": "10:00", "title": "Doors", "text": "Market stalls and coffee."},
                         {"date": "13:00", "title": "First sets", "text": "Garden and tent stages open."},
                         {"date": "20:00", "title": "Main stage", "text": "Headliners until late."},
                         {"date": "02:00", "title": "Close", "text": "Night buses from the east gate."}]),
        new_block("faq", heading="Practical stuff",
                  items=[{"q": "Is it cashless?", "a": "Card and phone everywhere. Bring a little cash for the market."},
                         {"q": "Can I bring a bag?", "a": "One small bag per person, searched at the gate."},
                         {"q": "Under 18?", "a": "Allowed until 22:00 with a guardian."}]),
        new_block("cta", heading="Tickets", text="Early bird until 1 May.",
                  buttons=[{"label": "Buy tickets", "url": "#", "style": "primary"}], surface="accent"),
        new_block("footer", text="© 2026 Signal Fest"),
    ],
)

CLAN = _doc(
    "IRONCLAD — Community", "🛡️",
    "Competitive clan. Casual people.",
    _theme("cyber", font="space", heading_font="space", effect="grid", radius=8),
    [
        new_block("nav", brand="IRONCLAD", links=[{"label": "Roster", "url": "#roster"}, {"label": "Apply", "url": "#apply"}],
                  cta_label="Discord", cta_url="https://discord.gg/"),
        new_block("hero", layout="center", eyebrow="EU · Founded 2021", title="IRONCLAD",
                  subtitle="Top-500 squads, weekly scrims, and a server where nobody flames you for missing a shot.",
                  glow="grid", buttons=[{"label": "Apply to join", "url": "#apply", "style": "primary"},
                                        {"label": "Watch us play", "url": "https://twitch.tv/", "style": "ghost"}]),
        new_block("stats", items=[{"value": "3", "label": "Active rosters"},
                                  {"value": "42", "label": "Tournaments"},
                                  {"value": "9", "label": "Trophies"}], surface="soft"),
        new_block("team", heading="Roster", columns="4",
                  items=[{"name": "kite", "role": "IGL", "photo": "", "url": ""},
                         {"name": "nova", "role": "Entry", "photo": "", "url": ""},
                         {"name": "sil", "role": "Support", "photo": "", "url": ""},
                         {"name": "orb", "role": "Sniper", "photo": "", "url": ""}]),
        new_block("features", heading="What we offer",
                  items=[{"icon": "🎯", "title": "Coaching", "text": "VOD reviews every Sunday with a coach who's actually ranked."},
                         {"icon": "📅", "title": "Scrims", "text": "Three nights a week against organised teams."},
                         {"icon": "🏆", "title": "Tournaments", "text": "We enter open cups monthly. Prize splits are public."}],
                  columns="3"),
        new_block("about", heading="Apply", layout="stack",
                  body="Requirements: **16+**, Diamond or above, a microphone, and the ability to lose without going nuclear.\n\nApplications open on the first of every month in our Discord."),
        new_block("cta", heading="Join the server", text="Applications, scrim pings and memes all live in Discord.",
                  buttons=[{"label": "Open Discord", "url": "https://discord.gg/", "style": "primary"}], surface="accent"),
        new_block("footer", text="© 2026 IRONCLAD",
                  socials=[{"platform": "discord", "url": "https://discord.gg/"},
                           {"platform": "twitch", "url": "https://twitch.tv/"},
                           {"platform": "steam", "url": "https://steamcommunity.com/"}]),
    ],
)

RESUME = _doc(
    "Jonas Weber — CV", "📄",
    "Backend engineer, ten years in.",
    _theme("sand", font="dm", heading_font="dm", effect="none", radius=12, width=880),
    [
        new_block("hero", layout="avatar", title="Jonas Weber", eyebrow="Backend engineer · Hamburg",
                  subtitle="Python, Go and the unglamorous parts of distributed systems.",
                  glow="none", image="",
                  buttons=[{"label": "Download CV", "url": "#", "style": "primary"},
                           {"label": "Email me", "url": "mailto:you@example.com", "style": "ghost"}], pad="l"),
        new_block("timeline", heading="Experience",
                  items=[{"date": "2021 — now", "title": "Senior engineer · Kettle", "text": "Payments platform. Cut p99 latency by 60%, led a four-person team."},
                         {"date": "2018 — 2021", "title": "Engineer · Farbe GmbH", "text": "Order pipeline, event sourcing, on-call rotation."},
                         {"date": "2015 — 2018", "title": "Junior · Hafen Labs", "text": "Django monolith and a lot of learning."}]),
        new_block("features", heading="Skills", columns="4",
                  items=[{"icon": "🐍", "title": "Python", "text": "Django, FastAPI, asyncio"},
                         {"icon": "🐹", "title": "Go", "text": "Services and CLIs"},
                         {"icon": "🗄️", "title": "Postgres", "text": "Modelling, tuning, migrations"},
                         {"icon": "☁️", "title": "Infra", "text": "Terraform, Docker, CI"}]),
        new_block("contact", heading="Contact", email="you@example.com", location="Hamburg, DE", surface="soft"),
        new_block("footer", text="© 2026 Jonas Weber", show_badge=True,
                  socials=[{"platform": "github", "url": "https://github.com/"},
                           {"platform": "linkedin", "url": "https://linkedin.com/"}]),
    ],
)

SOON = _doc(
    "Something is coming", "🌙",
    "A new thing, soon.",
    _theme("midnight", font="sora", heading_font="sora", effect="aurora", radius=20, width=820),
    [
        new_block("hero", layout="center", eyebrow="Coming soon", title="Something is coming",
                  subtitle="We're building it. Leave your email and we'll tell you the moment it's live.",
                  glow="aurora", buttons=[{"label": "Notify me", "url": "mailto:you@example.com", "style": "primary"}], pad="xl"),
        new_block("countdown", heading="", target="", done_text="It's live.", surface="none"),
        new_block("socials", size="m", items=[{"platform": "x", "url": "https://x.com/"},
                                              {"platform": "discord", "url": "https://discord.gg/"}]),
        new_block("footer", text="© 2026", show_badge=True),
    ],
)

BLANK = _doc(
    "My new site", "🌐", "",
    _theme("midnight"),
    [
        new_block("hero", title="My new site", eyebrow="", subtitle="Click anything to edit it. Drag blocks in from the left.",
                  buttons=[{"label": "A button", "url": "#", "style": "primary"}]),
        new_block("footer", text="© 2026"),
    ],
)


PRESETS: list[dict] = [
    {"id": "discord", "name": "Discord server", "emoji": "💬", "accent": "#5865f2",
     "tagline": "Advertise a server and grow it", "tags": ["community", "gaming"], "doc": DISCORD},
    {"id": "bio", "name": "Personal bio page", "emoji": "✨", "accent": "#e11d8f",
     "tagline": "All your links on one pretty page", "tags": ["personal", "creator"], "doc": BIO},
    {"id": "company", "name": "Company website", "emoji": "🏢", "accent": "#1f6feb",
     "tagline": "Services, proof and a way to get hired", "tags": ["business"], "doc": COMPANY},
    {"id": "portfolio", "name": "Portfolio", "emoji": "🎨", "accent": "#7c5cff",
     "tagline": "Show the work, get the work", "tags": ["creative"], "doc": PORTFOLIO},
    {"id": "product", "name": "Product launch", "emoji": "🚀", "accent": "#34d399",
     "tagline": "Features, pricing, waitlist", "tags": ["business", "startup"], "doc": PRODUCT},
    {"id": "event", "name": "Event page", "emoji": "🎪", "accent": "#ff6b3d",
     "tagline": "Line-up, schedule, countdown, tickets", "tags": ["event"], "doc": EVENT},
    {"id": "clan", "name": "Gaming clan", "emoji": "🛡️", "accent": "#ff2e88",
     "tagline": "Roster, scrims and recruitment", "tags": ["gaming", "community"], "doc": CLAN},
    {"id": "resume", "name": "CV / résumé", "emoji": "📄", "accent": "#b45309",
     "tagline": "One page that gets you the interview", "tags": ["personal"], "doc": RESUME},
    {"id": "soon", "name": "Coming soon", "emoji": "🌙", "accent": "#22d3ee",
     "tagline": "A teaser with a countdown", "tags": ["minimal"], "doc": SOON},
    {"id": "blank", "name": "Blank canvas", "emoji": "⬜", "accent": "#98a0b4",
     "tagline": "Start from almost nothing", "tags": ["minimal"], "doc": BLANK},
]

PRESET_MAP: dict[str, dict] = {preset["id"]: preset for preset in PRESETS}


def preset_doc(preset_id: str) -> dict:
    preset = PRESET_MAP.get(preset_id) or PRESET_MAP["blank"]
    return copy.deepcopy(preset["doc"])


def preset_index() -> list[dict]:
    """Preset metadata for the gallery / picker (without the full documents)."""
    return [
        {k: v for k, v in preset.items() if k != "doc"} | {"blocks": len(preset["doc"]["blocks"])}
        for preset in PRESETS
    ]
