package cz.trety.seed.release

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxNativeContractTest {
    @Test fun rebuildIsAnExplicitDependencyAndPreservesJavaPin() {
        val script = File("build.gradle.kts").readText()
        assertTrue(script.contains("isTransitive = false"))
        assertTrue(script.contains("terminal-emulator:0.118.3@aar"))
        assertTrue(script.contains(".builtBy(rebuildTermuxAar)"))
        assertTrue(script.contains("module = \"terminal-emulator\""))
        assertTrue(script.contains("28.2.13676358"))
        assertTrue(script.contains("androidComponents.sdkComponents.sdkDirectory"))
        assertFalse(script.contains("providers.environmentVariable(\"ANDROID_SDK_ROOT\")"))
        assertFalse(script.contains("resolutionStrategy.force"))
    }
}
