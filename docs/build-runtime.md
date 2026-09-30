# Building the Seed Android runtime

Seed supports two direct-native runtime lanes and does not package QEMU:

| Lane | Android ABI | `RUNTIME_ARCH` | Alpine/Docker platform |
|---|---|---|---|
| Local emulator | `x86_64` | `x86_64` | x86_64 / `linux/amd64` |
| Physical phone | `arm64-v8a` | `arm64` | ARM64 / `linux/arm64` |

A runtime build publishes four Android-native PRoot files under
`android/app/src/main/jniLibs/<abi>/`, plus the matching generated
`android/app/src/main/assets/linux/rootfs.tar.gz`. Only one complete ABI and
rootfs are kept at a time; they must always be built together.

## Local x86_64 emulator

On an x86_64 Linux host, use the accelerated Android 34 x86_64 AVD:

```bash
make install                         # installs SDK packages and recreates seed_dev if ABI changed
make runtime RUNTIME_ARCH=x86_64     # explicit, potentially slow Docker build
make run
```

`make run` verifies the four x86_64 libraries before Gradle or emulator startup.
It intentionally does not build the large rootfs implicitly. If it reports a
missing/mismatched runtime, run the printed `make runtime RUNTIME_ARCH=x86_64`
command. If a previous ARM AVD named `seed_dev` exists, `make avd` (or
`make install`) recreates it with the configured x86_64 image.

## ARM64 phone

For a connected ARM64 device, use:

```bash
make run-phone-test DEVICE_ID=<serial>
```

That target validates/builds the ARM64 runtime and then installs the matching
APK. To return to emulator development afterward, rebuild x86_64 before
running `make run`.

## Pi model catalog updates

The image pins **Pi 0.84.2** and checks **Node >=22.19.0** before npm
installation. Its offline `pi update --help` smoke check requires `--models`;
the image build never runs an authenticated model update.

The protected `POST /control/v1/models/update` runs only `pi update --models`,
using the same app-private `PI_CODING_AGENT_DIR` as terminal, control, and chat.
Pi may refresh catalog caches (`models-store.json`) and OAuth credentials.
Known Pi credential environment variables are available only to this trusted
subprocess; the runtime capability is excluded and stdin/output are discarded.
The subprocess has a 24-second outer deadline, followed by forced process-group
termination/reap on timeout or cancellation. Android's HTTP read timeout must
remain longer (30 seconds). Update and catalog/selection operations serialize;
the dedicated catalog runner is invalidated even after partial failures.

GET catalog refresh stays read-only. Updating never restarts chat agents or
changes the saved selection. Exit zero may be a no-op and does not certify
working provider authentication; errors are generic and never expose output.
Pi 0.84.2's bundled `pi-ai/dist/models.js:getSupportedThinkingLevels` explicitly
permits `max`, like `xhigh`, only with a non-null `thinkingLevelMap` entry. The
backend follows that metadata instead of enabling these levels for all models.

**Source changes do not upgrade an existing phone rootfs.** Explicitly rebuild
the matching runtime, assemble/install its APK, and confirm extraction of the
new build marker. An authenticated openai-codex subscription device must still
verify catalog population separately; offline tests do not establish that.

## Build details

Runtime generation requires Docker with buildx, `curl`, `ar`, `file`,
`readelf`, Python 3, `tar` with xz support, `uv`, and GNU `sha256sum`.
`scripts/runtime-target.sh` pins the Termux PRoot, libtalloc and
libandroid-shmem packages for each ABI, and `scripts/build-runtime.sh` verifies
both package and final ELF checksums. Android packages those executable files
through legacy JNI packaging because Android W^X policy prevents executing a
copy from writable app storage.

The marker `assets/linux/seed_version.json` declares `runtime_format: native`,
format version 3, and `native_arch: arm64|x86_64`. A changed marker causes the
app to re-extract the rootfs. Legacy `guest_arch`/QEMU markers are rejected.

After assembly, verify the selected APK inventory with:

```bash
make check-apk-runtime
```

Generated native files and `rootfs.tar.gz` are Git-ignored. Keep a local marker
change together with its matching generated bundle, and commit it only when
intentionally publishing a runtime update.
