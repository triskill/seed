"""Tests for the project-local pi config wiring.

The orchestrator spawns `pi` subprocesses for the middle-man
and worker roles. Two small pure helpers pin the exact
invocation and environment:

  * `pi_cmd_for_role(role)` returns the argv (provider,
    model, mode, thinking level, no-session flag).
  * `pi_env_for_role(role)` returns the env dict that
    points `PI_CODING_AGENT_DIR` at the project's local
    config so the agent uses our defaults (cheap model on
    opencode-go) instead of the user's `~/.pi/agent/`.

These tests lock in the defaults the user picked
(`deepseek-v4-flash` on `opencode-go`) and guard the
SEED_PI_* env var override knobs. They do NOT spawn `pi`
itself — they only check the returned strings. A separate
manual demo (`scripts/demo_phase3.py` + the new
`scripts/demo_pi.py` if added) exercises the real CLI.
"""
from __future__ import annotations

import pytest

from seed_backend.orchestrator import (
    _DEFAULT_PI_MODEL,
    _DEFAULT_PI_PROVIDER,
    _DEFAULT_PI_THINKING,
    pi_cmd_for_role,
    pi_env_for_role,
)


def test_pi_cmd_for_role_includes_provider_and_model():
    """The default argv pins both provider and model explicitly.

    The defaults are read from the `_DEFAULT_PI_*` constants
    (not hard-coded here) so a future change to the model
    in `orchestrator.py` doesn't silently break the test.
    Asserts the structural shape rather than the exact
    string to keep the test focused on the contract.
    """
    argv = pi_cmd_for_role("middleman")
    assert argv[0] == "pi"
    # Find the --provider / --model / --thinking / --mode / --no-session flags
    # and check they appear with the expected values. Using a
    # list of (flag, expected) pairs makes the test easy to
    # read and easy to extend if more flags are added.
    expected_pairs = [
        ("--mode", "rpc"),
        ("--provider", _DEFAULT_PI_PROVIDER),
        ("--model", _DEFAULT_PI_MODEL),
        ("--thinking", _DEFAULT_PI_THINKING),
        ("--no-session", None),
    ]
    for flag, expected in expected_pairs:
        assert flag in argv, f"missing flag {flag} in {argv!r}"
        if expected is not None:
            idx = argv.index(flag)
            assert argv[idx + 1] == expected, (
                f"flag {flag} expected {expected!r} got {argv[idx + 1]!r}"
            )


def test_pi_cmd_for_role_works_for_both_roles():
    """Both `middleman` and `worker` get the same flags today.

    Phase 4 will add per-role `--append-system-prompt` files,
    at which point the two calls will diverge. For now the
    flags are identical — this test guards that and would
    catch an accidental asymmetry.
    """
    mm = pi_cmd_for_role("middleman")
    wk = pi_cmd_for_role("worker")
    # Drop the role from any future --role flag; today the
    # role is implicit in how the orchestrator uses the
    # argv, not in a flag. So the two lists are identical.
    assert mm == wk, f"role asymmetry: {mm!r} vs {wk!r}"


def test_pi_cmd_for_role_rejects_unknown_role():
    """An unknown role raises ValueError so a typo fails fast."""
    with pytest.raises(ValueError, match="unknown pi role"):
        pi_cmd_for_role("manager")
    with pytest.raises(ValueError, match="unknown pi role"):
        pi_cmd_for_role("")


def test_pi_cmd_for_role_honors_seeds(monkeypatch):
    """SEED_PI_PROVIDER / SEED_PI_MODEL / SEED_PI_THINKING override the defaults.

    These are the knobs a developer uses to swap to a
    different model for a single run without touching
    code. The test sets the env vars, calls the helper,
    and asserts the new values appear in the argv.
    """
    monkeypatch.setenv("SEED_PI_PROVIDER", "anthropic")
    monkeypatch.setenv("SEED_PI_MODEL", "claude-haiku-4-5")
    monkeypatch.setenv("SEED_PI_THINKING", "off")
    argv = pi_cmd_for_role("middleman")
    assert argv[argv.index("--provider") + 1] == "anthropic"
    assert argv[argv.index("--model") + 1] == "claude-haiku-4-5"
    assert argv[argv.index("--thinking") + 1] == "off"


def test_pi_env_for_role_sets_pi_coding_agent_dir():
    """The env dict overrides `PI_CODING_AGENT_DIR` to the project-local config."""
    env = pi_env_for_role("middleman")
    assert "PI_CODING_AGENT_DIR" in env
    # The path should be inside the repo, end with `.pi/agent`.
    assert env["PI_CODING_AGENT_DIR"].endswith(".pi/agent"), env["PI_CODING_AGENT_DIR"]
    # And it should actually exist (the helper mkdirs it
    # on first call so a fresh clone works).
    import os
    assert os.path.isdir(env["PI_CODING_AGENT_DIR"])


def test_pi_env_for_role_inherits_parent_env(monkeypatch):
    """The env dict inherits from `os.environ` so API keys set in
    the shell are passed through to the child.

    The orchestrator never wants API keys in argv (visible
    in `ps`); they must come from env. This test guards
    that the helper starts from `os.environ` rather than
    building a fresh dict.
    """
    monkeypatch.setenv("OPENCODE_API_KEY", "sk-test-fake-key-for-unit-test")
    env = pi_env_for_role("worker")
    assert env.get("OPENCODE_API_KEY") == "sk-test-fake-key-for-unit-test"


def test_pi_env_for_role_rejects_unknown_role():
    """Same ValueError contract as `pi_cmd_for_role`."""
    with pytest.raises(ValueError, match="unknown pi role"):
        pi_env_for_role("manager")
