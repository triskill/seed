# Release licensing and provenance — pending review

This is a checklist, **not** a license determination. Do not publish a release on the strength of this inventory alone.

Google Play preparation retains an [expanded dependency inventory](release/dependency-inventory.md)
and [actual guest package metadata](release/runtime-package-metadata.json), tied to the
current generated rootfs hash. Missing license fields, exact native source/patch
mapping, Android notices and project terms remain unresolved; these materials do
not constitute legal clearance.

- Project license: `README.md` and `backend/pyproject.toml` still say TBD; no project `LICENSE` file. Owner/legal review must select a license and confirm redistribution model.
- Native bundle: `scripts/runtime-target.sh` pins Termux package URLs, versions and SHA-256 values for PRoot, libtalloc and libandroid-shmem (both supported ABIs); `scripts/build-runtime.sh` verifies the inputs and outputs. Hashes establish provenance of expected binaries, **not** permissions to redistribute them. Verify license text, notices, corresponding source and any source-offer obligations for the *actual pinned packages* before release. README flags PRoot's GPL implications; do not assume licenses of libtalloc or libandroid-shmem.
- Android: inspect license files and transitive dependencies of the pinned Termux terminal artifacts and other Gradle dependencies; comments in Gradle are not proof. Preserve required notices.
- Guest rootfs: inventory Alpine packages and Python/npm packages in generated runtime, and their license/notice/source obligations. Keep a reproducible dependency manifest for the distributed image.
- Release configuration: `android/app/build.gradle.kts` currently disables minification. Decide whether to enable it only with release-build and runtime smoke coverage; signing and connected-device acceptance remain outstanding in `TODO.md`.

Owner decisions still needed: project license, redistribution/source-offer process, whether to enable minification, and device acceptance criteria. None of these is silently changed by dependency cleanup.
