package com.seed.app.runtime

import android.content.res.AssetManager
import java.io.InputStream

/**
 * Production [AssetSource] backed by Android's [AssetManager].
 *
 * Lists every file under `assets/linux/` (the directory we populate
 * via `scripts/build-runtime.sh`). The set of files is fixed in
 * v0.1: `proot`, `rootfs.tar.gz`, `seed_version.json`.
 *
 * The `proot` file is marked executable; the other two are not.
 * The `seed_version.json` is handled separately by
 * [com.seed.app.runtime.RootfsVersion] — it is also extracted to
 * disk so the app can compare versions on next launch, but it is
 * not "executable" in the chmod sense.
 */
class AndroidAssetSource(
    private val assets: AssetManager,
    private val assetsDir: String = "linux",
) : AssetSource {

    override fun entries(): List<AssetEntry> {
        val names = assets.list(assetsDir) ?: return emptyList()
        return names.map { name ->
            // AssetManager.openFd gives us the exact uncompressed size
            // (the APK compresses assets, but openFd accounts for it).
            val fd = assets.openFd("$assetsDir/$name")
            AssetEntry(
                name = name,
                size = fd.length,
                executable = name == EXECUTABLE_NAME,
            )
        }.sortedBy { it.name }   // deterministic order
    }

    override fun open(name: String): InputStream = assets.open("$assetsDir/$name")

    private companion object {
        const val EXECUTABLE_NAME = "proot"
    }
}
