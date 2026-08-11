"""Regression tests for dependencies required by production backend modules."""

from pathlib import Path
import tomllib


def test_httpx_is_a_production_dependency():
    """flask_manager imports httpx when the production service starts."""
    pyproject_path = Path(__file__).parents[1] / "pyproject.toml"
    with pyproject_path.open("rb") as pyproject_file:
        pyproject = tomllib.load(pyproject_file)

    assert "httpx>=0.27" in pyproject["project"]["dependencies"]
