# Android-Native PRoot Design

## Problem

The generic Linux PRoot v5.3.0 binaries run from an interactive `adb shell run-as` process, but the x86_64 binary exits with status 159 (`SIGSYS`) when started by the Android application. Android application processes inherit the Zygote application seccomp filter, while `run-as` does not use the same syscall policy. The generic x86_64 binary uses Linux syscall behavior that is incompatible with that application filter. This happens before the guest backend can start and is independent of PRoot file permissions, loader placement, rootfs dependencies, or HTTP URL construction.

## Decision

Package checksum-pinned Termux Android-native PRoot artifacts instead of generic Linux static PRoot artifacts. Termux builds PRoot against Android's Bionic runtime and carries Android-specific compatibility patches. Each architecture-specific APK bundle contains:

- `libproot.so`: the Android-native PRoot executable;
- `libproot-loader.so`: PRoot's external executable loader;
- `libtalloc.so`: PRoot's memory-allocation dependency; and
- `libandroid-shmem.so`: PRoot's Android shared-memory compatibility dependency.

All four files are installed from `jniLibs/<abi>` so Android gives executable native code the correct APK-backed location and SELinux label.

## Runtime generation

`scripts/build-runtime.sh` downloads pinned Termux `proot`, `libtalloc`, and `libandroid-shmem` Debian packages for the selected architecture. It validates each package checksum before extraction. Because Android Gradle packages native libraries using `lib*.so` names, generation deterministically rewrites PRoot's `libtalloc.so.2` dependency to `libtalloc.so`, then verifies the resulting dynamic dependency table.

Generation stages and validates the complete rootfs and native bundle before publication. Validation covers ELF architecture, expected loader type, dynamic dependencies, and pinned final checksums. Publication removes all native files for the opposite ABI and writes the runtime version marker last, preserving the existing fail-safe architecture-switch behavior. Generated native artifacts remain ignored and are never committed.

## Android integration

`NativeProot` resolves and validates the complete four-file installation from `applicationInfo.nativeLibraryDir`. `ProotEnvironment` sets `PROOT_LOADER` to the packaged loader, `LD_LIBRARY_PATH` to the same native library directory, and keeps `PROOT_TMP_DIR` under app cache for non-executable temporary data.

Make preflight validates all four native artifacts before Gradle, installation, or emulator startup. An incomplete or wrong-architecture bundle fails fast with the explicit matching `make runtime RUNTIME_ARCH=<arch>` command.

## Testing

Shell regression tests cover package checksum rejection, extraction, dependency rewriting, final checksums, complete architecture switching, staged-publication safety, and four-file Make preflight. Android unit tests cover complete native-installation validation and environment construction. An instrumentation smoke test launches the packaged PRoot and guest Python from the actual `untrusted_app` process domain, ensuring future artifacts do not regress to the Zygote-seccomp failure.

Emulator acceptance requires the app-managed backend to remain running and `http://127.0.0.1:7777/health` to return successfully; manual `run-as` execution is not sufficient.
