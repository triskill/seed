# Termux native rebuild provenance

Only `terminal-emulator/src/main/jni/termux.c` and `Android.mk` are imported from
Termux v0.118.3 commit `5b657c6adf4304e5198951ce815fe0205dcac29c`.
Raw source URL prefix: https://raw.githubusercontent.com/termux/termux-app/5b657c6adf4304e5198951ce815fe0205dcac29c/terminal-emulator/src/main/jni/
Neither file is modified; in particular the existing cmd_cwd ReleaseStringUTFChars
behavior is not changed. No other Termux implementation or headers are imported.
The exact repository LICENSE.md retains the terminal-view/emulator Apache-2.0
exception. Apache-2.0.txt is the official https://www.apache.org/licenses/LICENSE-2.0.txt
copy (SHA256 cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30).
This does not select or change Seed's project GPL version.

Input pins are enforced in `scripts/rebuild-termux-aar.py`:

- termux.c: af9485e2f170eb91b5c5594063190727fd5d4b7932a30e32349c6fdea0b7eee5
- Android.mk: 118312b11e9d9a3458e7139af9f56d487b4f792ad7ba3b0c423867923db55fa1
- LICENSE.md: a8992ad867b1b4ea442693acb2e1ee829a814758e79a099bd8243a04ae383304
- Original emulator AAR: cbb915c4d7c51883f85397b46b74bf4b82fd32a8bbf0baeba1e0bc6d990d3e8a
- Original and rebuilt classes.jar: fc67861136b78b167bab46ef4ead0bc1f8ada750796f4874f779820d5f7ff56b

## Toolchain and build

NDK r28c, package 28.2.13676358, installed locally by SDK manager with already
accepted licenses; no license acceptance command was run. SDK:
`/home/borbot/android-sdk`. source.properties SHA256:
`c00aa236fdb205e9be9edd9e2169763e48aca52735efff4e16f34205d49783b5`.
Gradle does not silently provision this toolchain. CI installs the pinned package.

ndk-build uses android-24, release optimization, C11, Wall/Wextra/Werror, Os,
fno-stack-protector, default STL, APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true and explicit
max/common-page-size=16384 linker flags in APP_LDFLAGS (not C compilation flags).
No ELF headers are patched. Both arm64-v8a and x86_64 are rebuilt; the existing
runtime ABI filter still selects the APK/AAB ABI.

Actual locally built libraries:

| ABI | SHA256 | PT_LOAD offset/vaddr/alignment |
| --- | --- | --- |
| arm64-v8a | 7d1b31c50d044b0f25438540ed12744c554bba79ef50ff0c1c557e5f90e9b7d8 | 0/0/16384; 6608/22992/16384 |
| x86_64 | 7e9d2fa079bc77b929dd43b6981ab615e146bc43fb75609dc0f27faa9e60955d | 0/0/16384; 6416/22800/16384 |

Both have SONAME libtermux.so, no RPATH/RUNPATH/TEXTREL or executable stack.
DT_NEEDED is libc.so, libm.so, libdl.so: modern NDK eliminates the old unused
libstdc++.so dependency; all three are platform libraries available at API24.
There is no libc++_shared.so. Exported JNI functions remain createSubprocess,
setPtyWindowSize, waitFor, close and setPtyUTF8Mode. Java classes are unchanged.

All 8 original non-native ZIP entries were compared byte-for-byte successfully.
All old JNI entries (including 32-bit directories) are removed. New notices/source
copies live under META-INF/termux-native; no original non-native entry is replaced.
ZIP timestamps/modes/order are normalized. The AAR is published atomically only
following ELF checks. Build debris is cleared before compilation. Generated output
is under `android/app/build/generated/termux-native`, never committed. Detailed
symbols/dynamic sections, toolchain/source/output hashes are in provenance.json.

## Verification scope

Helper tests demonstrated red before implementation (missing helper), then 3 tests
passed: pin rejection, unsafe ZIP paths, deterministic repack preserving Java and
future assets while dropping old JNI. Both native compilations passed Werror and
ELF checks. The native-only Gradle task passed with both ANDROID_HOME and ANDROID_SDK_ROOT
unset: `env -u ANDROID_HOME -u ANDROID_SDK_ROOT ./gradlew --no-daemon :app:rebuildTermuxAar`.
SDK resolution uses `androidComponents.sdkComponents.sdkDirectory`, honoring
AGP's local.properties/environment resolution instead of requiring environment
variables. A source-contract assertion first failed before this correction.
Parent verification: 380 JVM tests per variant, both lint/APK/AAB builds and
instrumentation compilation passed; actual APK/AAB inspectors and make release-check
passed. See ../reports/play-release-engineering-batch.md. Real 16KB-page phone behavior remains untested;
no device, app data, upload or signing operation was performed.
