package com.seed.app.runtime

/**
 * The runtime version baked into the APK at
 * `assets/linux/seed_version.json`. Compared against
 * `filesDir/linux/.version` to decide whether re-extraction is
 * needed on app start.
 *
 * `seedVersion` is the human-meaningful app version (matches
 * `BuildConfig.VERSION_NAME`); `buildId` is a per-build identifier
 * (timestamp + build-script hash from `scripts/build-runtime.sh`)
 * that changes every time the runtime is rebuilt.
 */
data class RootfsVersion(
    val seedVersion: String,
    val buildId: String,
) {
    companion object {
        /**
         * Parse a `seed_version.json` string. Tolerant of unknown
         * extra fields (forward-compat with future schema additions).
         * Throws [IllegalArgumentException] on malformed input.
         */
        fun parse(json: String): RootfsVersion {
            // We use a tiny hand-rolled parser instead of pulling in
            // Moshi/JSON for two fields — keeps the runtime module
            // dependency-free and the test trivial.
            val seed = stringField(json, "seed_version")
                ?: throw IllegalArgumentException("missing seed_version")
            val build = stringField(json, "build_id")
                ?: throw IllegalArgumentException("missing build_id")
            return RootfsVersion(seed, build)
        }

        private val STRING_FIELD = Regex(""""(\w+)"\s*:\s*"([^"\\]*)"""")

        private fun stringField(json: String, name: String): String? =
            STRING_FIELD.findAll(json)
                .firstOrNull { it.groupValues[1] == name }
                ?.groupValues?.get(2)
    }
}
