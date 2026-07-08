package com.seed.app.runtime

import android.content.res.AssetManager
import java.io.InputStream

/**
 * Production [AssetSource] backed by Android's [AssetManager].
 *
 * Lists every file under `assets/linux/` (the directory we populate
 * via `scripts/build-runtime.sh`). The set of files is fixed in
 * v0.1: `proot`, `rootfs.tar`, `seed_version.json`.
 *
 * Naming note: the source file is `rootfs.tar.gz`, but the Android
 * Gradle Plugin's CompressAssetsTask auto-gunzips `.gz` files in
 * `assets/` and strips the extension, so at runtime
 * [AssetManager.list] returns `rootfs.tar` (not `rootfs.tar.gz`).
 * The build's `noCompress` patterns in `app/build.gradle.kts` must
 * match this merged name — see the comment there.
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
            // AssetManager.openFd gives us the exact byte size and
            // is the only way to read an uncompressed asset in place.
            // It THROWS FileNotFoundException on compressed assets,
            // even ones the rest of AssetManager can read — so the
            // build must keep the runtime assets on the noCompress
            // list in app/build.gradle.kts. Drop or rename a
            // pattern there and the app crashes on first launch
            // with "This file can not be opened as a file
            // descriptor; it is probably compressed". Note also
            // that the pattern must match the post-gunzip name
            // (`linux/rootfs.tar`, not `linux/rootfs.tar.gz`) — see
            // the build script for why.
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
