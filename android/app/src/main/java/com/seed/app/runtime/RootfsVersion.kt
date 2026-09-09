package com.seed.app.runtime

/** Architecture of programs inside the extracted Alpine rootfs. */
enum class RootfsArchitecture(val wireValue: String) {
    ARM64("arm64"),
    X86_64("x86_64"),
    ;

    companion object {
        fun parse(value: String): RootfsArchitecture = entries.firstOrNull {
            it.wireValue == value
        } ?: throw IllegalArgumentException("unsupported guest_arch: $value")
    }
}

/**
 * The runtime version baked into the APK at `assets/linux/seed_version.json`.
 * It is compared against `filesDir/linux/.version` to decide whether
 * re-extraction is needed on app start.
 *
 * [guestArchitecture] is part of the marker so switching between native ARM64
 * and QEMU x86_64 runtime assets always triggers a clean re-extraction.
 */
data class RootfsVersion(
    val seedVersion: String,
    val buildId: String,
    val guestArchitecture: RootfsArchitecture = RootfsArchitecture.ARM64,
) {
    companion object {
        /**
         * Parse a `seed_version.json` string. Old markers without `guest_arch`
         * remain ARM64-compatible; unknown future fields are ignored.
         */
        fun parse(json: String): RootfsVersion {
            val seed = stringField(json, "seed_version")
                ?: throw IllegalArgumentException("missing seed_version")
            val build = stringField(json, "build_id")
                ?: throw IllegalArgumentException("missing build_id")
            val guestArchitecture = stringField(json, "guest_arch")
                ?.let(RootfsArchitecture::parse)
                ?: RootfsArchitecture.ARM64
            return RootfsVersion(seed, build, guestArchitecture)
        }

        private val STRING_FIELD = Regex(""""(\w+)"\s*:\s*"([^"\\]*)"""")

        private fun stringField(json: String, name: String): String? =
            STRING_FIELD.findAll(json)
                .firstOrNull { it.groupValues[1] == name }
                ?.groupValues?.get(2)
    }
}
