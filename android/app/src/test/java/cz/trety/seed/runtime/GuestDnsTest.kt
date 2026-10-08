package cz.trety.seed.runtime

import java.net.InetAddress
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GuestDnsTest {
    @Test fun `replacement DNS is exactly owner readable and writable`() {
        val rootfs = Files.createTempDirectory("guest-dns-mode").toFile()
        val resolver = rootfs.resolve("etc/resolv.conf")
        resolver.parentFile.mkdirs()
        resolver.writeText("old")
        Files.setPosixFilePermissions(resolver.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))

        GuestDns.write(rootfs, listOf(InetAddress.getByName("8.8.8.8")))

        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(resolver.toPath())))
    }

    @Test fun `resolver symlink is rejected without changing target`() {
        val rootfs = Files.createTempDirectory("guest-dns-link").toFile()
        val target = Files.createTempFile("dns-target", ".txt")
        Files.write(target, "private".toByteArray())
        val originalMode = Files.getPosixFilePermissions(target)
        val resolver = rootfs.resolve("etc/resolv.conf")
        resolver.parentFile.mkdirs()
        Files.createSymbolicLink(resolver.toPath(), target)

        val failure = runCatching { GuestDns.write(rootfs, listOf(InetAddress.getByName("8.8.8.8"))) }.exceptionOrNull()

        org.junit.Assert.assertNotNull(failure)
        org.junit.Assert.assertTrue(Files.isSymbolicLink(resolver.toPath()))
        assertEquals("private", String(Files.readAllBytes(target)))
        assertEquals(originalMode, Files.getPosixFilePermissions(target))
    }

    @Test fun `copies active DNS servers into guest resolver file`() {
        val rootfs = Files.createTempDirectory("guest-dns").toFile()
        val resolver = rootfs.resolve("etc/resolv.conf")
        resolver.parentFile.mkdirs()
        resolver.writeText("")

        GuestDns.write(rootfs, listOf(InetAddress.getByName("192.168.0.1"), InetAddress.getByName("2001:4860:4860::8888")))

        assertEquals("nameserver 192.168.0.1\nnameserver 2001:4860:4860:0:0:0:0:8888\n", resolver.readText())
    }

    @Test fun `does not recreate guest files before extraction or erase valid DNS during a network gap`() {
        val rootfs = Files.createTempDirectory("guest-dns").toFile()
        val resolver = rootfs.resolve("etc/resolv.conf")
        GuestDns.write(rootfs, listOf(InetAddress.getByName("8.8.8.8")))
        assertFalse(resolver.exists())
        resolver.parentFile.mkdirs()
        resolver.writeText("nameserver 192.168.0.1\n")
        GuestDns.write(rootfs, emptyList())
        assertEquals("nameserver 192.168.0.1\n", resolver.readText())
    }
}
