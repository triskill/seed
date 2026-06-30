"""Seed v0.1 webapp — Flask app factory.

Task 0.1 ships only `/` (renders the placeholder card) and `/api/ping`
(used by the backend's `FlaskManager.wait_ready` in Task 0.5 and by the
smoke test to confirm the process is up). New routes are added by the
worker agent in later phases.
"""
from __future__ import annotations

from flask import Flask, jsonify, render_template


def create_app() -> Flask:
    app = Flask(__name__)

    @app.get("/")
    def index():
        return render_template("index.html")

    @app.get("/api/ping")
    def ping():
        return jsonify(pong=True)

    return app


# Module-level WSGI target so `flask --app seed_app.app run` works
# without needing an app factory call.
app = create_app()


if __name__ == "__main__":
    # Dev convenience: `python -m seed_app.app`
    app.run(host="127.0.0.1", port=7778, debug=True)
