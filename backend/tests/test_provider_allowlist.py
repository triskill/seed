from seed_backend.provider_allowlist import PROVIDERS, credential_env_for


def test_credential_env_for_known_providers():
    assert credential_env_for("openai") == "OPENAI_API_KEY"
    assert credential_env_for("anthropic") == "ANTHROPIC_API_KEY"
    assert credential_env_for("opencode-go") == "OPENCODE_API_KEY"


def test_credential_env_for_unknown_provider_is_none():
    assert credential_env_for("made-up") is None
    assert credential_env_for("") is None


def test_provider_ids_are_lowercase_strings():
    for pid in PROVIDERS:
        assert pid == pid.strip().lower()
        assert pid  # no empties


def test_all_credential_env_vars_are_in_process_env_allowlist():
    from seed_backend.process_env import PI_CREDENTIAL_ENV_VARS
    keys = set(PROVIDERS.values())
    missing = keys - PI_CREDENTIAL_ENV_VARS
    assert not missing, f"credential env vars not in PI_CREDENTIAL_ENV_VARS: {missing}"
