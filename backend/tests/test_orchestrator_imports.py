from seed_backend import orchestrator  # noqa: F401


def test_module_imports():
    assert orchestrator.pi_cmd_for_role("middleman")
    assert orchestrator.pi_env_for_role("middleman")
