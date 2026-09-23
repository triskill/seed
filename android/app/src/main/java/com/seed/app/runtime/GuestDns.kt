package com.seed.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Mirror Android's active network DNS into Alpine, which has no Android resolver service. */
internal object GuestDns {
    fun sync(context: Context, rootfs: File) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val network = connectivity.activeNetwork ?: return
        val servers = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
        write(rootfs, servers)
    }

    @Synchronized
    fun write(rootfs: File, servers: List<InetAddress>) {
        if (servers.isEmpty()) return // Network transitions must not erase a working resolver.
        val resolver = File(rootfs, "etc/resolv.conf")
        if (!resolver.isFile) return // Extraction is not finished yet.
        val contents = servers.distinct().joinToString(separator = "", postfix = "") {
            "nameserver ${it.hostAddress?.substringBefore('%')}\n"
        }
        val parent = requireNotNull(resolver.parentFile)
        val temp = Files.createTempFile(parent.toPath(), ".resolv-", ".tmp")
        try {
            Files.write(temp, contents.toByteArray(Charsets.UTF_8))
            check(temp.toFile().setReadable(true, false)) { "Could not make guest DNS readable" }
            Files.move(temp, resolver.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
