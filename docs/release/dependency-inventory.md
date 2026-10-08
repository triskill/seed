# Release dependency and provenance inventory

Reviewed 2026-10-07. **Not a complete SBOM, license determination or redistribution
clearance.** Project licensing remains TBD. Findings concern the locally generated
ARM64 runtime and declared release dependencies; a different image needs a new
inventory tied to its hashes.

## Retained actual guest package metadata

`runtime-package-metadata.json` records **65 Alpine, 49 Python and 311 versioned
npm metadata records**, retaining paths to disambiguate vendored/example manifests.
These counts are metadata records, not necessarily unique independently distributed
products. Missing or ambiguous license fields are unresolved, not permissive grants.
The scan covers installed APK database, `*.dist-info/METADATA` and versioned
`node_modules/**/package.json` entries; it excludes credential/user files.

Source: `android/app/src/main/assets/linux/rootfs.tar.gz`, **133,990,913 bytes**,
SHA-256 **e343c38b3a9f001d65930d7e7b3fa746cd57c09c53eb63286df7d960a67ff491**.
The source is generated/ignored, not committed. Its APK counterpart is a raw TAR
because AGP strips the source gzip wrapper. This hash does not identify future
regenerated bundles or establish correspondence to a signed uploaded artifact.

The builder uses Alpine 3.22.5, installs system packages without revision pins,
resolves Python ranges afresh, and installs Pi 0.84.2 with transitive npm packages.
A versioned Docker tag is not a retained digest. A downloaded minirootfs hash does
not prove the Docker base used for the exported image. Preserve image digest,
resolved input archives/integrity data and toolchain before claiming reproduction.

## Reproducing the metadata inventory

From the repository root, with the retained generated archive present:

```sh
python3 scripts/inventory-runtime.py android/app/src/main/assets/linux/rootfs.tar.gz \
  --output docs/release/runtime-package-metadata.json
make verify-play-tools
```

Omit `--output` to emit JSON on stdout. File output uses a sibling temporary file
and atomic replacement, preserving an existing report on failure. The command
hashes and parses one open source descriptor, using its size and checking its
identity, size and timestamps before/after; detected in-place changes fail.
Atomic pathname replacement leaves a coherent inventory of the original open
snapshot (unlink-only link-count/ctime changes are permitted).
The command hashes the actual compressed source bytes and streams the TAR without
extraction. The retained source produces
65 Alpine, 49 Python and 311 npm records with the size and SHA-256 above; its JSON
is reproducible byte-for-byte. Records sort by name, metadata path, then version.
All versioned nested/vendor/example npm manifests are included; Python license
headers prefer `License-Expression`, otherwise the first 512 characters of
`License`. No external license determinations are added.

Only regular installed APK database, dist-info metadata and node_modules package
manifests are read as metadata. Absolute/traversal paths and selected symlinks are
ignored. Selected entries exceeding 2 MiB fail rather than silently disappearing;
the scan permits at most 64 MiB selected payload bytes, 200,000 yielded archive
entries and 20,000 records. These are selected-metadata limits for trusted
synthetic/build inputs, **not global decompression or memory bounds for hostile
TARs**: GNU/PAX extension processing occurs inside Python's TAR reader before an
entry is yielded. Hashing and decompression traverse archive bytes, including
unselected payloads, but only selected metadata is read for interpretation; no
authentication/user files are extracted or interpreted. Invalid UTF-8, corrupt
DEFLATE, excessive JSON nesting or archive/resource errors fail with redacted
diagnostics; malformed/unversioned npm manifests otherwise yield no record.
No credential/user settings, application databases, photos or coordinates are
interpreted. This metadata inventory
is **not a complete SBOM or license clearance**, and does not regenerate runtime
assets or prove the distribution obligations are fulfilled.

`make release-check` builds `bundle-release` before inspecting the fresh fixed
Gradle output, even under parallel make. Custom `PLAY_BUNDLE` paths are rejected
because Gradle does not write them; `PLAY_ARTIFACT` is only for manual
`make check-play-artifact` audits. `PLAY_ABI` is passed to both inspections.

## Native Android runtime

Evidence: `scripts/runtime-target.sh`, `scripts/build-runtime.sh`.

| Component | Actual pin | License evidence and unresolved work |
| --- | --- | --- |
| PRoot executable + loader | Termux proot 5.1.107.92, aarch64/x86_64 packages | [Exact tag COPYING](https://raw.githubusercontent.com/termux/proot/v5.1.107.92/COPYING) contains GPLv2 text. Verify file headers/version scope, Termux patches, loader, recipe and complete corresponding source. |
| libtalloc | Termux 2.4.3 | Exact package license unresolved. Current recipe concerns 2.5.0/GPL-3.0, not proof for this pin. Retrieve actual packaged notices and historical recipe; review compatibility with PRoot. |
| libandroid-shmem | Termux 0.7 | [Exact tag LICENSE](https://raw.githubusercontent.com/termux/libandroid-shmem/v0.7/LICENSE) states BSD-3-Clause. Preserve notices. |
| Termux terminal JNI | Transitive terminal-emulator at requested termux-app tag 0.118.3 | Included native code must be inventoried/audited alongside the four generated runtime binaries; inspect resolved AAR/build/source. |

The script rewrites PRoot's `libtalloc.so.2` dependency to `libtalloc.so`, renames
executables for Android packaging and copies binaries, not a release notice/source
bundle. Preserve those modifications, exact Termux recipes/patches/toolchain and
source archives. Applicable GPL source duties are not automatically a conclusion
that the entire Android shell must use GPL; review actual linking/aggregation
boundaries. Do not label talloc LGPL based on general upstream knowledge.

## Android release declarations

Source: `android/app/build.gradle.kts`. The resolved release graph is retained in
`android-release-dependencies.txt`; artifact hashes and measured inspection results
are in `artifact-measurements.json`. A graph is not exact per-file NOTICE clearance.
Build-tool upgrades are separately recorded in the preparation report; build tools
are not all distributed app dependencies.

| Family | Declared version |
| --- | --- |
| AndroidX core / lifecycle / activity | 1.13.1 / 2.8.2 / 1.9.0 |
| Compose BOM | 2024.06.00; resolved individual versions require dependency graph |
| Navigation / WebKit / SwipeRefreshLayout | 2.7.7 / 1.11.0 / 1.1.0 |
| DataStore / Security-Crypto | 1.0.0 / 1.1.0 (legacy credential migration only) |
| Retrofit + Moshi converter | 2.11.0 |
| OkHttp + logging interceptor | 4.12.0 |
| Moshi + Kotlin adapter | 1.15.1 |
| kotlin-reflect | 1.9.24 |
| Commons Compress | 1.27.1 |
| Termux terminal-view + terminal-emulator | requested JitPack tag 0.118.3 |

Most listed families publish Apache-2.0 metadata; this is **not exact per-artifact
license/NOTICE verification**. Retain the resolved release dependency graph, exact
AAR/JAR/POM hashes and license files. Packaging excludes META-INF/AL2.0 and LGPL2.1,
so do not assume every required notice survives in the APK; provide notices
separately as needed.

[Termux exact repository LICENSE](https://raw.githubusercontent.com/termux/termux-app/v0.118.3/LICENSE.md)
uses GPLv3-only for the repository with Apache-2.0 exceptions for terminal-view and
terminal-emulator. Do not call the whole repository Apache-2.0. Tagged internal
publication version fields may say 0.118.0; map JitPack artifacts to exact commits.
Test/debug-only JUnit, Espresso, MockWebServer and tooling declarations are not
necessarily release payload; inspect resolved release configuration, not all deps.

## Guest licensing highlights — not an exhaustive legal classification

The JSON contains actual declared fields. Significant items include:

- Alpine baselayout, APK tools, BusyBox, git and scanelf: GPL-2.0-only metadata.
- gdbm/readline: GPL-3.0-or-later; gcc runtime libraries: review LGPL/GPL runtime
  exceptions; libidn2/libunistring/xz include alternative/combined terms.
- ca-certificates and certifi include MPL terms; preserve required covered source.
- Python 3.12.14, Node 22.23.2, npm 11.6.4 and their separately bundled dependencies
  require their own review. Vim uses Vim terms; sqlite uses blessing metadata.
- Backend/webapp packages and Pi provider SDKs mostly declare permissive licenses,
  but a missing metadata field is not a license grant. seed-app still says TBD.
- Setuptools vendors autocommand 2.2.2 with LGPLv3 metadata; vendored packages and
  native extensions cannot be omitted from review.
- Pi modules at 0.84.2 include **pi-telemetry**; OpenTelemetry APIs are present in
  Python/npm. Presence alone does not prove enabled telemetry, but “no telemetry”
  must not be promised without configuration/network evidence.
- Pi's tree includes Anthropic 0.91.1, OpenAI 6.40.0, Google genai 1.52.0 and AWS
  Bedrock 3.1048.0 declarations. Do not infer provider retention/privacy terms
  from SDK software licenses.

## Required release work

1. Owner chooses project license and distribution terms.
2. Retrieve exact native package source/recipes, patches, licenses and modifications;
   resolve talloc terms and component compatibility.
3. Retain exact Alpine package revisions/APKBUILD/source archives and appropriate
   source provision for applicable copyleft licenses.
4. Inspect full resolved Android and guest trees, vendored code, native extensions,
   licenses/NOTICE texts and artifact hashes; supply required notices inside or
   alongside the app with a reliable public source-delivery mechanism.
5. Tie the final inventory to the actual signed AAB and delivered APK variants.
6. Owner/legal review approves obligations and process before publication.

This branch neither selects a project license nor supplies a complete source offer.
See `../release-license-audit.md` and `google-play-readiness.md` for open gates.
