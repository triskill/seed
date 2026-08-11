# Building the Seed Android runtime

The Android APK bundles one runtime architecture at a time. Runtime generation
publishes a native proot separately from the writable runtime data assets:

| Generated or tracked path | What |
|---|---|
| `android/app/src/main/jniLibs/x86_64/libproot.so` | Generated x86_64 static proot; Git-ignored |
| `android/app/src/main/jniLibs/arm64-v8a/libproot.so` | Generated arm64 static proot; Git-ignored |
| `android/app/src/main/assets/linux/rootfs.tar.gz` | Generated matching Alpine rootfs; Git-ignored |
| `android/app/src/main/assets/linux/seed_version.json` | Tracked extraction marker containing `seed_version` and generated `build_id` |

Only one of the two `jniLibs` proot files is retained at a time. A fresh
checkout therefore contains the marker, but neither generated proot nor
`rootfs.tar.gz`.

## Why proot is a native library

Android 10 and later forbid an app targeting API 29 or later from executing
files in its writable app home. A proot copied to `filesDir`, even with mode
`0755`, is still blocked by this W^X policy; `chmod` cannot change the file's
execution domain.

Seed packages proot through Android's native-library mechanism instead. AGP
legacy JNI packaging and PackageManager extract `libproot.so` as a
read-only/executable installed native library. At runtime the app resolves the
file at:

```text
applicationInfo.nativeLibraryDir/libproot.so
```

The app never copies or modifies proot during first-launch extraction.
`rootfs.tar.gz` and `seed_version.json` stay as source assets. AGP exposes the
gzipped rootfs as `rootfs.tar` in the merged APK; the extractor expands it and
copies the marker under `filesDir/linux`. That writable area contains runtime
data and interpreted scripts, not the proot executable.

## Prerequisites and commands

Runtime generation requires Docker with buildx, `curl`, `file`, `grep`, `gzip`,
`tar`, `uv`, and GNU coreutils including `sha256sum`. Building the Android APK
also requires JDK 17 and the Android SDK described in
[`../android/README.md`](../android/README.md).

Runtime generation defaults to arm64 with either entry point:

```bash
./scripts/build-runtime.sh
# equivalent
make runtime
```

Prefer an explicit architecture when preparing an APK:

```bash
# arm64 physical device/runtime
make runtime RUNTIME_ARCH=arm64
make build

# repository's x86_64 Android emulator
make runtime RUNTIME_ARCH=x86_64
make build
make run
```

With the direct entry point, use
`RUNTIME_ARCH=arm64 ./scripts/build-runtime.sh` or
`RUNTIME_ARCH=x86_64 ./scripts/build-runtime.sh`.

## Docker platforms

The script uses `docker buildx build` with `linux/arm64` for arm64 or
`linux/amd64` for x86_64. Both select the corresponding variant of the pinned
multi-platform `alpine:3.20.3` base image. QEMU user-mode emulation registered
with `binfmt_misc` is needed only when the selected target differs from the
Docker host architecture. A recent Docker Desktop commonly provides this; on
Linux it can be registered, for example, with:

```bash
docker run --privileged --rm tonistiigi/binfmt --install all
```

No emulation setup is required for a native-architecture target.

## Safe architecture switching

Each invocation uses fresh temporary build and staging directories. Before
publication, the script validates the selected proot ELF, Alpine checksum,
Docker image architecture, and required rootfs contents.

After all validation succeeds, publication uses individual same-filesystem
renames. It publishes a staged selected proot when needed, removes the opposite
generated `jniLibs/<abi>/libproot.so` and obsolete legacy
`assets/linux/proot`, and then publishes the rootfs. It renames
`seed_version.json` into place last as the completion marker.

Each rename is atomic, but the native proot, rootfs, cleanup, and marker are not
a globally atomic group. The marker-last order prevents a new marker from
advertising a build before the preceding publication steps complete; it does
not make the whole switch transactional.

## `make run` preflight

`make run` never invokes the large runtime build automatically. It derives the
Android ABI from `SYSTEM_IMAGE` and checks the exact selected source path before
Gradle or emulator startup:

```text
android/app/src/main/jniLibs/x86_64/libproot.so
android/app/src/main/jniLibs/arm64-v8a/libproot.so
```

A missing, non-ELF, unsupported, or mismatched file fails immediately and prints
the explicit repair command, either:

```text
make runtime RUNTIME_ARCH=x86_64
make runtime RUNTIME_ARCH=arm64
```

Generate the matching bundle, then rerun `make build` or `make run`.

## Gitignore and versioning

Generated `jniLibs/*/libproot.so` files and
`android/app/src/main/assets/linux/rootfs.tar.gz` are local build artifacts and
must not be committed. The tracked
`android/app/src/main/assets/linux/seed_version.json` is the extraction
completion marker. A successful runtime build updates its `build_id`, so the
marker appears in `git status` even though the large generated files do not.

Commit the marker only when intentionally publishing a runtime update. During a
local architecture switch, keep the generated marker together with the matching
local proot/rootfs bundle. Rebuild whenever Alpine, proot, `pi`, backend/webapp
sources, or required system packages change. On launch, `BootController`
compares the bundled marker with `filesDir/linux/.version`; a difference causes
the rootfs data to be re-extracted. Proot remains in the installed native
library directory and is not part of writable extraction.
