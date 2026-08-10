# Building the Seed Android runtime

The Android APK bundles one runtime architecture at a time. The selected proot
and rootfs are generated under `android/app/src/main/assets/linux/`:

| File | Size | What |
|---|---|---|
| `proot` | ~2 MB | arm64 or x86_64 static binary that runs the rootfs unprivileged |
| `rootfs.tar.gz` | ~150 MB | Matching Alpine rootfs with python3, nodejs, npm, git, tmux, `pi`, the backend, and a fresh webapp skeleton |
| `seed_version.json` | ~100 B | `{ "seed_version": "0.1.0", "build_id": "<timestamp>-<script-hash>" }` |

The build requires Docker with buildx, `curl`, `file`, `grep`, `gzip`, `tar`,
`uv`, and GNU coreutils (including `sha256sum`). Runtime generation defaults to
arm64 with either entry point:

```bash
./scripts/build-runtime.sh
# equivalent
make runtime
```

Prefer an explicit target when preparing an APK:

```bash
# arm64 physical device/runtime
make runtime RUNTIME_ARCH=arm64
make build

# repository's x86_64 Android emulator
make runtime RUNTIME_ARCH=x86_64
make build
make run
```

With the direct entry point, select a target as
`RUNTIME_ARCH=x86_64 ./scripts/build-runtime.sh` (or `arm64`).

## Docker platforms

The script uses `docker buildx build` with `linux/arm64` for arm64 or
`linux/amd64` for x86_64. Both select the corresponding variant of the
multi-platform `alpine:3.20.3` base image. QEMU user-mode emulation registered
with `binfmt_misc` is needed only when the selected target differs from the
Docker host architecture. A recent Docker Desktop commonly provides this; on
Linux it can be registered, for example, with:

```bash
docker run --privileged --rm tonistiigi/binfmt --install all
```

No emulation setup is required for a native-architecture target.

## Safe architecture switching

Each invocation creates a fresh temporary build context rather than reusing the
old `runtime-build/` directory. Completed assets are staged on the same
filesystem as the destination. The script validates the selected proot ELF,
Alpine checksum, Docker image architecture, and required rootfs contents before
publishing anything.

When changing architectures, the replacement proot and complete rootfs are
therefore staged and validated before they replace the previous bundle.
`seed_version.json` is renamed into place last as the completion marker, so the
app never observes a new version for a partially published bundle.

## `make run` preflight

`make run` never invokes the large runtime build automatically. Before APK
assembly or emulator startup, it checks the packaged proot against the ABI from
the configured `SYSTEM_IMAGE`. A missing, non-ELF, unsupported, or mismatched
binary causes an immediate failure and prints the explicit repair command, such
as:

```text
make runtime RUNTIME_ARCH=x86_64
```

Generate the matching bundle, then rerun `make build` or `make run`.

## Gitignore and versioning

A fresh checkout contains only the tracked
`android/app/src/main/assets/linux/seed_version.json`. Generated `proot` and
`rootfs.tar.gz` files are gitignored and must not be committed. A successful
runtime build updates the tracked marker's `build_id`, so it appears in
`git status`. Commit that marker when intentionally publishing a runtime update;
architecture-specific binaries remain local build artifacts. During local
architecture switching, keep the generated marker with the APK being tested.

Rebuild whenever Alpine, `pi`, backend/webapp sources, or required system
packages change. On launch, `BootController` compares the bundled marker with
`filesDir/linux/.version`; a difference causes the runtime to be re-extracted.
