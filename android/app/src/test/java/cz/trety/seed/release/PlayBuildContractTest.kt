package cz.trety.seed.release

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contract: keep local and CI SDK provisioning aligned with Play builds. */
class PlayBuildContractTest {
    private val androidRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/build.gradle.kts").isFile }
    private val repository = androidRoot.parentFile
    private fun source(path: String) = File(repository, path).readText()
    private fun sdk(name: String): Int = Regex("\\b$name\\s*=\\s*(\\d+)")
        .find(source("android/app/build.gradle.kts"))!!.groupValues[1].toInt()

    @Test fun compileSdkMeetsPlayRequirement() = assertEquals(36, sdk("compileSdk"))
    @Test fun targetSdkMatchesCompileSdk() {
        assertEquals(36, sdk("targetSdk"))
        assertEquals(sdk("compileSdk"), sdk("targetSdk"))
    }
    @Test fun compatibleToolchainIsPinned() {
        assertTrue(source("android/build.gradle.kts").contains("version \"8.10.1\""))
        assertTrue(source("android/gradle/wrapper/gradle-wrapper.properties")
            .contains("gradle-8.11.1-bin.zip"))
    }
    @Test fun nativeLibrariesAreFilteredToMetadataAbi() {
        val build = source("android/app/build.gradle.kts")
        assertTrue(build.contains("abiFilters += packagedRuntimeAbi"))
        assertTrue(build.contains("\"arm64\" -> \"arm64-v8a\""))
        assertTrue(build.contains("\"x86_64\" -> \"x86_64\""))
        assertTrue(build.contains("runtimeMetadata[\"runtime_format\"] != \"native\""))
        assertTrue(build.contains("when (val arch = runtimeMetadata[\"native_arch\"])"))
        assertTrue(build.contains("JsonSlurper().parse(file(\"src/main/assets/linux/seed_version.json\"))"))
        assertTrue(build.contains("else -> throw GradleException"))
    }
    @Test fun sdkProvisioningMatchesBuild() {
        val make = source("Makefile")
        assertTrue(Regex("ANDROID_PLATFORM\\s*:=\\s*android-36\\b").containsMatchIn(make))
        assertTrue(Regex("ANDROID_BUILD_TOOLS\\s*:=\\s*35\\.0\\.0\\b").containsMatchIn(make))
        val ci = source(".github/workflows/verify.yml")
        assertTrue(ci.contains("'platforms;android-36' 'build-tools;35.0.0'"))
    }
}
