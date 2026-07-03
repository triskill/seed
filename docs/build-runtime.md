# Building the Seed Android runtime

The Android APK ships with three files under
`android/app/src/main/assets/linux/`:

| File | Size | What |
|---|---|---|
| `proot` | ~2 MB | arm64 static binary that runs the rootfs unprivileged |
| `rootfs.tar.gz` | ~150 MB | Alpine + python3 + nodejs + npm + git + tmux + `pi` (npm) + our `backend/` + a fresh `webapp/` skeleton |
| `seed_version.json` | ~100 B | `{ "seed_version": "0.1.0", "build_id": "<timestamp>-<script-hash>" }` |

These are **not committed to git** (only `seed_version.json`'s structure is —
the real `build_id` is generated per build). To build them, run:

```bash
./scripts/build-runtime.sh
```

This script is idempotent and rebuilds from scratch in a `runtime-build/`
working directory (gitignored). The expansion step runs in a one-shot
`arm64v8/alpine:3.20` Docker container so the script works on any host
with Docker, regardless of the host arch.

This script uses `docker run --platform linux/arm64` to run the
expansion step. On an arm64 host it Just Works. On an x86_64 host
you need QEMU user-mode emulation registered with `binfmt_misc` —
either install the `qemu-user-static` package and run
`docker run --privileged --rm tonistiigi/binfmt --install all`
once, or use a recent Docker Desktop (which ships with the
emulators pre-registered). After registration, the script works
on any host.

## Build workflow

After `./scripts/build-runtime.sh` finishes, you'll see
`android/app/src/main/assets/linux/seed_version.json` modified in
`git status`. This is expected — the script bumped the `build_id`.
The change should be committed as part of the same PR that bumps
the runtime (e.g. "feat(android): rebuild runtime with pi v0.81").
Without the commit, the next CI build will re-bump and the in-APK
version will lag the source of truth.

The `proot` and `rootfs.tar.gz` artifacts inside `assets/linux/`
are gitignored and don't show up in `git status`.

## When to rebuild

- The rootfs image (Alpine version) changes.
- `pi` is upgraded.
- The backend or webapp skeleton changes.
- A new system package is needed inside the runtime.

In all cases, just re-run the script. The new `build_id` will be
different, the app will detect the version mismatch on next launch, and
re-extract automatically.

## How the app knows to re-extract

`MainActivity` reads `assets/seed_version.json` and compares it to
`filesDir/linux/.version`. If they differ (or the latter is missing),
`RuntimeExtractor` re-extracts, overwrites `.version`, and the app
proceeds. See `app/src/main/java/com/seed/app/runtime/` for the
implementation and tests.
