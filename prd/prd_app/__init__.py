"""PRD — Personalise, Request, Deploy.

A no-code website builder that publishes to PythonAnywhere.
"""
from __future__ import annotations

from flask import Flask, jsonify, render_template, request

from .config import Config

__version__ = "1.0.0"


def create_app(config: Config | None = None) -> Flask:
    app = Flask(__name__)
    config = config or Config()

    app.config["PRD_CONFIG"] = config
    app.config.update(config.to_flask())

    from . import db

    db.init_db(config.db_path)
    config.sites_root.mkdir(parents=True, exist_ok=True)
    app.teardown_appcontext(db.close_db)

    from .admin import bp as admin_bp
    from .api import bp as api_bp
    from .views import bp as views_bp

    app.register_blueprint(views_bp)
    app.register_blueprint(api_bp)
    app.register_blueprint(admin_bp)

    register_errors(app)

    @app.after_request
    def security_headers(response):
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
        if not request.path.startswith("/s/"):
            response.headers.setdefault("X-Frame-Options", "SAMEORIGIN")
        return response

    from . import icons

    # Icons are drawn once per page as a sprite and referenced by name.
    app.jinja_env.globals["icon"] = icons.use
    app.jinja_env.globals["icon_sprite"] = icons.sprite

    @app.template_filter("shortdate")
    def shortdate(value: str) -> str:
        return (value or "").replace("T", " ")[:16]

    return app


def register_errors(app: Flask) -> None:
    def wants_json() -> bool:
        return request.path.startswith("/api/") or request.accept_mimetypes.best == "application/json"

    @app.errorhandler(404)
    def not_found(_error):
        if wants_json():
            return jsonify({"ok": False, "error": "Not found."}), 404
        return render_template("error.html", code=404,
                               title="Nothing here",
                               message="That page or site does not exist."), 404

    @app.errorhandler(403)
    def forbidden(_error):
        if wants_json():
            return jsonify({"ok": False, "error": "Forbidden."}), 403
        return render_template("error.html", code=403, title="Not allowed",
                               message="You need a valid manage link for that."), 403

    @app.errorhandler(413)
    def too_large(_error):
        return jsonify({"ok": False, "error": "That design is too large to send."}), 413

    @app.errorhandler(429)
    def too_many(_error):
        if wants_json():
            return jsonify({"ok": False, "error": "Slow down a little."}), 429
        return render_template("error.html", code=429, title="Slow down",
                               message="You've made a lot of requests. Try again shortly."), 429

    @app.errorhandler(500)
    def server_error(error):  # pragma: no cover - defensive
        app.logger.exception("Unhandled error: %s", error)
        if wants_json():
            return jsonify({"ok": False, "error": "Something broke on our side."}), 500
        return render_template("error.html", code=500, title="Something broke",
                               message="That is our fault. Try again in a moment."), 500
