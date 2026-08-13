"""Behavioral tests for the one-command host development launcher."""

import os
from pathlib import Path
import shutil
import subprocess


def _run_dev_script(
    tmp_path: Path,
    seed_app_path: str | None = None,
) -> tuple[str, str]:
    fake_repo = tmp_path / "repo with spaces"
    scripts = fake_repo / "backend" / "scripts"
    scripts.mkdir(parents=True)
    (fake_repo / "webapp").mkdir()
    script = scripts / "dev.sh"
    source = Path(__file__).resolve().parents[1] / "scripts" / "dev.sh"
    shutil.copy2(source, script)

    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    capture = tmp_path / "captured"
    uvicorn = fake_bin / "uvicorn"
    uvicorn.write_text(
        "#!/bin/sh\n"
        'printf "%s\n%s\n" "$SEED_APP_PATH" "$PWD" > "$CAPTURE"\n'
    )
    uvicorn.chmod(0o755)

    caller = tmp_path / "caller"
    caller.mkdir()
    env = os.environ.copy()
    env["PATH"] = f"{fake_bin}{os.pathsep}{env['PATH']}"
    env["CAPTURE"] = str(capture)
    if seed_app_path is None:
        env.pop("SEED_APP_PATH", None)
    else:
        env["SEED_APP_PATH"] = seed_app_path

    subprocess.run(["bash", str(script)], cwd=caller, env=env, check=True)
    app_path, cwd = capture.read_text().splitlines()
    return app_path, cwd


def test_dev_script_exports_repo_webapp_and_uses_backend_cwd(tmp_path):
    app_path, cwd = _run_dev_script(tmp_path)

    assert app_path == str(tmp_path / "repo with spaces" / "webapp")
    assert cwd == str(tmp_path / "repo with spaces" / "backend")


def test_dev_script_preserves_absolute_seed_app_path_override(tmp_path):
    override = str(tmp_path / "other-app")

    app_path, _ = _run_dev_script(tmp_path, override)

    assert app_path == override


def test_dev_script_normalizes_relative_override_before_chdir(tmp_path):
    app_path, _ = _run_dev_script(tmp_path, "other-app")

    assert app_path == str(tmp_path / "caller" / "other-app")
