# ProGuard / R8 rules. We don't minify in debug (see
# `buildTypes.debug` in `app/build.gradle.kts`); release
# uses the default Android optimize file plus this stub.
# Add app-specific keep rules here as needed.

# Default: keep nothing extra. The default Android
# optimize file already handles the standard cases
# (Activity / Fragment / Parcelable / Serializable).
