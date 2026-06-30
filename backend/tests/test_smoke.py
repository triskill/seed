"""Smoke test for the seed v0.1 skeleton.

Verifies that both the backend (`seed_backend`) and the webapp (`seed_app`)
packages can be imported in the same process. This is the minimal end-to-end
sanity check before any real behavior is wired up.
"""
import seed_backend  # noqa: F401
import seed_app  # noqa: F401


def test_smoke_imports():
    assert True
