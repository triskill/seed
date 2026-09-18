package com.seed.app.runtime

/** Version marker for the extracted direct-native runtime. */
data class RootfsVersion(
    val seedVersion: String,
    val buildId: String,
    val runtimeFormat: String = NATIVE_RUNTIME_FORMAT,
    val runtimeFormatVersion: Int = NATIVE_RUNTIME_FORMAT_VERSION,
    val nativeArch: String = NATIVE_ARCH,
) {
    fun toMarkerJson(): String =
        """{"seed_version":"$seedVersion","build_id":"$buildId","runtime_format":"$runtimeFormat","runtime_format_version":$runtimeFormatVersion,"native_arch":"$nativeArch"}"""

    companion object {
        /** A direct-native rootfs and Android PRoot bundle; never a QEMU guest. */
        const val NATIVE_RUNTIME_FORMAT = "native"
        /** @deprecated use [NATIVE_RUNTIME_FORMAT]. */
        const val RUNTIME_FORMAT = NATIVE_RUNTIME_FORMAT
        const val NATIVE_RUNTIME_FORMAT_VERSION = 3
        /** @deprecated use [NATIVE_RUNTIME_FORMAT_VERSION]. */
        const val RUNTIME_FORMAT_VERSION = NATIVE_RUNTIME_FORMAT_VERSION
        const val NATIVE_ARCH = "arm64"
        val SUPPORTED_NATIVE_ARCHES = setOf("arm64", "x86_64")

        fun parse(json: String): RootfsVersion {
            val seed = stringField(json, "seed_version")
                ?: throw IllegalArgumentException("missing seed_version")
            val build = stringField(json, "build_id")
                ?: throw IllegalArgumentException("missing build_id")
            // `guest_arch` belonged to the removed QEMU compatibility mode.
            if (hasField(json, "guest_arch")) {
                throw IllegalArgumentException("unsupported marker field: guest_arch")
            }
            val format = stringField(json, "runtime_format")
                ?: throw IllegalArgumentException("missing runtime_format")
            if (format != RUNTIME_FORMAT) throw IllegalArgumentException("unsupported runtime_format: $format")
            val version = intField(json, "runtime_format_version")
                ?: throw IllegalArgumentException("missing runtime_format_version")
            if (version != RUNTIME_FORMAT_VERSION) throw IllegalArgumentException("unsupported runtime_format_version: $version")
            val arch = stringField(json, "native_arch")
                ?: throw IllegalArgumentException("missing native_arch")
            if (arch !in SUPPORTED_NATIVE_ARCHES) {
                throw IllegalArgumentException("unsupported native_arch: $arch")
            }
            return RootfsVersion(seed, build, format, version, arch)
        }

        private val STRING_FIELD = Regex("""\"(\w+)\"\s*:\s*\"([^\"\\]*)\"""")
        private val INT_FIELD = Regex("""\"(\w+)\"\s*:\s*(-?\d+)""")
        private fun hasField(json: String, name: String): Boolean =
            STRING_FIELD.findAll(json).any { it.groupValues[1] == name }

        private fun stringField(json: String, name: String): String? =
            STRING_FIELD.findAll(json).firstOrNull { it.groupValues[1] == name }?.groupValues?.get(2)
        private fun intField(json: String, name: String): Int? =
            INT_FIELD.findAll(json).firstOrNull { it.groupValues[1] == name }?.groupValues?.get(2)?.toIntOrNull()
    }
}
