# Building the Seed Android runtime

The Android APK bundles one native ARM64 runtime. Runtime generation publishes
native PRoot separately from writable runtime data assets:

| Generated or tracked path | What |
|---|---|
| `android/app/src/main/jniLibs/<abi>/libproot.so` | Generated Android-native PRoot executable; Git-ignored |
| `android/app/src/main/jniLibs/<abi>/libproot-loader.so` | Generated external PRoot loader; Git-ignored |
| `android/app/src/main/jniLibs/<abi>/libtalloc.so` | Generated PRoot allocation dependency; Git-ignored |
| `android/app/src/main/jniLibs/<abi>/libandroid-shmem.so` | Generated Android shared-memory dependency; Git-ignored |
| `android/app/src/main/assets/linux/rootfs.tar.gz` | Generated matching Alpine rootfs; Git-ignored |
| `android/app/src/main/assets/linux/seed_version.json` | Tracked native marker containing `seed_version`, `build_id`, `runtime_format: native-arm64`, `runtime_format_version: 2`, and `native_arch: arm64` |

Only one ABI's complete four-file native bundle is retained at a time. A fresh
checkout therefore contains the marker, but neither generated native files nor
`rootfs.tar.gz`.

## Native ARM64 runtime

The only supported target is native ARM64 (`arm64-v8a`): ARM64 Android,
The generated package contains ARM64 Termux PRoot, ARM64 Alpine, Node, and Pi.
1. Do not add a foreign-architecture launcher to the APK.

The native runtime is generated with either entry point:

```bash
./scripts/build-runtime.sh
# equivalent
make runtime
```

`RUNTIME_ARCH` is retained as a compatibility spelling but accepts only
`arm64`; any other value fails before downloads or publication.


## Why proot is a native library

Android 10 and later forbid an app targeting API 29 or later from executing
files in its writable app home. A proot copied to `filesDir`, even with mode
`0755`, is still blocked by this W^X policy; `chmod` cannot change the file's
execution domain.

Seed packages PRoot and its native dependencies through Android's
native-library mechanism instead. AGP legacy JNI packaging and PackageManager
extract the complete bundle into the installed app's executable native-library
directory. At runtime the app resolves all four files under
`applicationInfo.nativeLibraryDir`, sets `PROOT_LOADER` to the packaged loader,
and sets `LD_LIBRARY_PATH` to that directory.

The Android-native PRoot build is also required for Android application seccomp
The runtime is validated in the Android application domain, not by host-side binaries.
with `SIGSYS` when launched by a Zygote-spawned `untrusted_app` process. The
Termux build targets Bionic and includes Android-specific compatibility work.

The app never copies or modifies native code during first-launch extraction.
`rootfs.tar.gz` and `seed_version.json` stay as source assets. AGP exposes the
gzipped rootfs as `rootfs.tar` in the merged APK; the extractor expands it and
copies the marker under `filesDir/linux`. That writable area contains runtime
data and interpreted scripts, not the proot executable.

## Prerequisites and commands

Runtime generation requires Docker with buildx, `curl`, `ar`, `file`, `readelf`,
Python 3, `grep`, `gzip`, `tar` with xz support, `uv`, and GNU coreutils
including `sha256sum`. Building the Android APK also requires JDK 17 and the
Android SDK described in [`../android/README.md`](../android/README.md).

```bash
# Native ARM64 runtime for a physical ARM64 phone or ARM64 AVD
make runtime
make build
make run-phone-test DEVICE_ID=<serial>  # physical phone
# or: make install && make run             # ARM64 AVD
```


## Native bundle provenance

`scripts/runtime-target.sh` pins Termux packages for PRoot 5.1.107.92,
libtalloc 2.4.3, and libandroid-shmem 0.7 separately for each architecture.
Generation verifies package checksums before extraction and final artifact
checksums afterward. It rewrites PRoot's `libtalloc.so.2` dependency to the
Android-packageable name `libtalloc.so`, then verifies the resulting ELF dynamic
dependencies with `readelf`.

PRoot is GPL-2.0 software. Corresponding upstream source for the packaged
version is the Termux PRoot tag
[`v5.1.107.92`](https://github.com/termux/proot/tree/v5.1.107.92); the Termux
package recipe is in
[`termux-packages/packages/proot`](https://github.com/termux/termux-packages/tree/master/packages/proot).
Distributors of an APK containing these generated binaries must satisfy the
applicable source and license obligations for PRoot and its dependencies.

## Docker platform

The script uses `docker buildx` with `linux/arm64` and the ARM64 variant of the
pinned `alpine:3.22.5` base image. Node 22 is required by pi 0.80.3, and the
image build runs `pi --version` before publication. Docker host emulation may
be needed when building on an x86 host; this affects only the build host, never
the APK runtime guest.

## Safe architecture switching

Each invocation uses fresh temporary build and staging directories. Before
publication, the script validates the ARM64 proot ELF, Alpine checksum, Docker
image architecture, and required rootfs contents.

After all validation succeeds, publication uses individual same-filesystem
renames. It publishes the staged four-file native bundle when needed, removes
The inventory checker rejects obsolete native files and requires the four ARM64 runtime libraries.
`assets/linux/proot`, and then publishes the ARM64 rootfs. It renames
`seed_version.json` into place last as the completion marker.

Each rename is atomic, but the native bundle, rootfs, cleanup, and marker are not
a globally atomic group. The marker-last order prevents a new marker from
advertising a build before the preceding publication steps complete; it does
not make the whole switch transactional.

## `make run` preflight

`make run` never invokes runtime generation automatically. It requires the
system image ABI to be `arm64-v8a` and checks these exact source paths before
Gradle or emulator startup:

```text
android/app/src/main/jniLibs/arm64-v8a/libproot.so
android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so
android/app/src/main/jniLibs/arm64-v8a/libtalloc.so
android/app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so
```

A missing, non-ELF, or mismatched file fails immediately; repair with
`make runtime`.
After assembling an APK, run `make check-apk-runtime` to verify that no x86 ABI,
The inventory checker rejects obsolete native files and requires the four ARM64 runtime libraries.

## Gitignore and versioning

Generated `jniLibs` PRoot, loader, talloc, and Android-shmem files and
`android/app/src/main/assets/linux/rootfs.tar.gz` are local build artifacts and
must not be committed. The tracked
`android/app/src/main/assets/linux/seed_version.json` is the extraction
completion marker. A successful runtime build updates its `build_id`, so the
marker appears in `git status` even though the large generated files do not.

Commit the marker only when intentionally publishing a runtime update. For a local runtime rebuild, keep the generated marker together with its
matching native/rootfs bundle. Rebuild whenever Alpine, PRoot, its Termux package
dependencies, `pi`, backend/webapp sources, or required system packages change.
On launch, `BootController`
compares the bundled marker with `filesDir/linux/.version`; a difference causes
the rootfs data to be re-extracted. Proot remains in the installed native
library directory and is not part of writable extraction.
