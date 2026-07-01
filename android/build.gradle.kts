// Top-level build file. The actual configuration lives in
// `app/build.gradle.kts`; this file declares the plugins and
// uses `apply false` to make them available to subprojects
// without applying them here. The version numbers must match
// the plugins block in `app/build.gradle.kts` (Gradle's
// `plugins {}` DSL requires the version to be specified in
// exactly one place — either the top-level `plugins` with
// `apply false` or each subproject's `plugins`).
//
// We pin all versions so a fresh clone builds without
// "newer-than-tested" warnings and the lockfile-like behaviour
// makes CI deterministic.

plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
