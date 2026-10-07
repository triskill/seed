# Seed

A self-improving Android app. The APK is an immutable shell with four
screens (App, Chat, Shell, Settings). An embedded Linux runtime
(proot + Alpine) hosts a Python orchestrator that drives two `pi` agent
instances — a "middle-man" for intent and a "worker" for building — and
the worker mutates a Flask + SQLite web app shown in a WebView. By the
end of day 1, the user has the web app they asked for.

## Status

**v0.1 bootstrap.** The repo currently contains:

- `backend/` — Python package (`seed_backend`) for the FastAPI orchestrator,
  pi runner, and Flask subprocess manager.
- `webapp/` — Flask app (`seed_app`) that the worker mutates.
- `android/` — Compose shell that extracts the rootfs and starts the embedded
  runtime through a natively packaged proot.
- `scripts/` — native ARM64 runtime build and validation tooling.
- `docs/plans/` — full design and phased implementation plans.

The pipe-backed `PiRunner` and both real pi RPC processes were accepted inside
the native ARM64 Android PRoot runtime on 2026-08-14. Saved Android provider/model/key
preferences are stored in Android DataStore; provider credentials are owned by the
backend/Pi runtime in private app storage. The old encrypted Android credential
store is migration-only, not the credential store for new installs. First launch
starts the regular embedded app with its packaged Pi defaults. Provider login and
model/thinking selection are optional, separate controls in Settings. Private app
storage is not a guarantee of encryption or isolation from agent execution.

### Google Play beta preparation

Work is tracked in the [execution plan](docs/plans/2026-10-07-app-store-preparation.md)
and [readiness audit](docs/release/google-play-readiness.md). See the
[execution report](docs/reports/google-play-preparation.md) for measured results.

```sh
make bundle-release      # unsigned AAB, existing generated runtime required
make verify-play-tools   # artifact inspector regression fixtures
make check-play-artifact # default ARM64 beta AAB; fails on remaining Termux 16 KB gate
```

Use `PLAY_ARTIFACT=/path/to/app.apk` and `PLAY_ABI=x86_64` for explicit alternatives.
Licensing, signing, policy eligibility, privacy and actual release-device acceptance
remain gates; unsigned bundles and draft listing materials are not publication approval.

## Android identity and existing data

The Android namespace and application ID are **`cz.trety.seed`** (launcher
`cz.trety.seed/cz.trety.seed.MainActivity`). This is a **new Android app**, not
an upgrade of `com.seed.app`. Existing `com.seed.app` installations remain
separate: preserve their data and do not uninstall or clear them. No migration
is provided for generated apps, settings, credentials/Keystore keys, or WebView
browser data. The new package cannot access those old private files or keys;
Runtime Restore is not a cross-package migration mechanism.

See [namespace verification](docs/reports/android-namespace-identity.md).

## Quick start (host dev)

Requires Python 3.11+ (3.12 is what we test on) and the
`pi` CLI on `PATH` (v0.79+).

```bash
# Create a venv (PEP 668 system Pythons require this)
python3 -m venv .venv
source .venv/bin/activate

# Install both packages in editable mode
pip install -U pip hatchling
pip install -e "backend/[dev]"
pip install -e "webapp/[dev]"

# Smoke test (no API key needed — uses fake pi fixtures)
pytest backend/ webapp/ -v

# Run the dev server (orchestrator + Flask webapp)
./backend/scripts/dev.sh
# → curl http://127.0.0.1:7777/health

# Open a real chat session (Phase 3 demo, no API key needed)
.venv/bin/python backend/scripts/demo_phase3.py
# → streams middleman_line → worker_line → complete
```

### Running the real agent loop

The orchestrator uses real `pi` when started via
`./backend/scripts/dev.sh`. You need an API key for the
provider/model in `.pi/agent/settings.json` (default:
`opencode-go` / `deepseek-v4-flash`). Set it in your
shell — **never commit it**:

```bash
export OPENCODE_API_KEY="sk-..."
./backend/scripts/dev.sh
```

See [`docs/pi-config.md`](docs/pi-config.md) for the full
configuration story (local `.pi/agent/`, env var
overrides, why no `auth.json` in the repo).

## Android runtime workflow

Seed has two **direct-native** runtime lanes and no QEMU guest mode:

- local development: x86_64 Android AVD, x86_64 PRoot and x86_64 Alpine;
- phone acceptance: ARM64 Android, ARM64 PRoot and ARM64 Alpine.

Only one generated ABI/rootfs bundle is retained at a time, so switch lanes by
rebuilding the runtime. On an x86_64 Linux host, the local workflow is:

```bash
make install                         # installs/recreates the x86_64 seed_dev AVD
make runtime RUNTIME_ARCH=x86_64     # required once and after switching from phone
make run
```

For an ARM64 physical phone:

```bash
make run-phone-test DEVICE_ID=<serial>
```

`make run-phone-test` verifies or builds the ARM64 runtime automatically. After
phone testing, rerun `make runtime RUNTIME_ARCH=x86_64` before `make run`.


## Layout

```
backend/
  pyproject.toml
  seed_backend/        # FastAPI orchestrator (Task 0.2+)
  tests/               # pytest tests, currently a smoke import
webapp/
  pyproject.toml
  seed_app/            # Flask + SQLite app the worker mutates
    app.py
    templates/
    static/
docs/
  plans/
    2026-06-30-seed-app-design.md     # full design
    2026-06-30-seed-v0.1-bootstrap.md # 11-phase implementation plan
```

## Reference

- **Design:** [`docs/plans/2026-06-30-seed-app-design.md`](docs/plans/2026-06-30-seed-app-design.md)
- **Implementation plan:** [`docs/plans/2026-06-30-seed-v0.1-bootstrap.md`](docs/plans/2026-06-30-seed-v0.1-bootstrap.md)

## License

TBD. Note: the Android runtime uses proot, which is GPL — see design doc §12.
