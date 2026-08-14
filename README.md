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
- `scripts/` — architecture-aware runtime build and validation tooling.
- `docs/plans/` — full design and phased implementation plans.

The pipe-backed `PiRunner` and both real pi RPC processes were accepted inside
the x86_64 Android PRoot runtime on 2026-08-14. Saved Android provider/model/key
settings are loaded from DataStore/Keystore storage and injected on embedded
runtime startup without copying the key to loopback HTTP or plaintext config.
A successful provider-backed turn, live apply/restart after Save, embedded Python
edit reload, and product hardening remain Phase 10 work.

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
# → streams middleman_line → worker_line → complete → app_reload
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

An APK bundles one runtime architecture at a time. Runtime generation writes
proot as a generated native library at exactly one of these Git-ignored paths:

- `android/app/src/main/jniLibs/x86_64/libproot.so`
- `android/app/src/main/jniLibs/arm64-v8a/libproot.so`

The matching source `rootfs.tar.gz` and tracked `seed_version.json` remain
under `android/app/src/main/assets/linux/`. AGP expands the gzip source to
merged `assets/linux/rootfs.tar` and stores it with `noCompress`; the app
extracts that rootfs data and the marker to `filesDir`, but never copies proot
there. Android 10+ forbids apps that target API 29+ from executing files in
writable app home; changing mode
bits with `chmod` does not bypass this W^X policy. AGP legacy JNI packaging
instead lets PackageManager extract `libproot.so` read-only/executable, and the
app resolves it as `applicationInfo.nativeLibraryDir/libproot.so`.

A fresh checkout has the marker but neither generated binary. Install the
Android and Docker/tooling prerequisites described in
[`android/README.md`](android/README.md) and
[`docs/build-runtime.md`](docs/build-runtime.md), then build explicitly:

```bash
# repository's x86_64 Android emulator (fresh setup)
make install
make runtime RUNTIME_ARCH=x86_64
make run

# arm64 physical device
make runtime RUNTIME_ARCH=arm64
make build
```

`make runtime` defaults to arm64 when no architecture is supplied. `make run`
never starts the large runtime build automatically: before Gradle or emulator
startup it checks the exact `jniLibs` path selected by `SYSTEM_IMAGE` and, on a
missing, invalid, or mismatched binary, prints the matching explicit x86_64 or
arm64 `make runtime RUNTIME_ARCH=...` repair command. See
[`docs/build-runtime.md`](docs/build-runtime.md) for architecture switching,
prerequisites, and generated-artifact versioning.

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
