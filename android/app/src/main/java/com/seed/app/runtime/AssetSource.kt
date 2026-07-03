package com.seed.app.runtime

import java.io.InputStream

/**
 * One file inside the runtime assets bundle.
 *
 * @param name Path relative to the assets root (e.g. "proot" or
 *   "rootfs.tar.gz"). The extractor writes it to the same path under
 *   the target directory.
 * @param size Exact byte size; used to compute total progress and
 *   to pre-size the output buffer.
 * @param executable If true, the file is chmod +x on disk after
 *   writing. Used for the proot binary (the only executable asset
 *   in v0.1).
 */
data class AssetEntry(
    val name: String,
    val size: Long,
    val executable: Boolean,
)

/**
 * Source of runtime assets. The production implementation wraps
 * Android's [android.content.res.AssetManager] (see
 * [AndroidAssetSource]); tests use a [MapAssetSource]-like fake.
 *
 * Why an interface: [RuntimeExtractor] is unit-tested on the JVM,
 * where [AssetManager] is unavailable. The interface keeps the
 * extraction logic pure and testable.
 */
interface AssetSource {
    /** All files that should be extracted, in deterministic order. */
    fun entries(): List<AssetEntry>

    /** Open a stream for [name]. Caller closes it. */
    fun open(name: String): InputStream
}
