package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class JUnitLauncherTest {
    private fun missing(vararg jars: String) = KrisprGradlePlugin.missingLauncherVersion(jars.map { File("/cache/$it") })

    @Test
    fun aPlatformEngineWithoutALauncherGetsTheEngineVersion() {
        assertEquals("1.10.2", missing("junit-jupiter-engine-5.10.2.jar", "junit-platform-engine-1.10.2.jar", "junit-platform-commons-1.10.2.jar"))
        assertEquals("6.0.0-M1", missing("junit-platform-engine-6.0.0-M1.jar"))
    }

    @Test
    fun aClasspathWithALauncherOrNoPlatformNeedsNone() {
        assertNull(missing("junit-platform-engine-1.13.4.jar", "junit-platform-launcher-1.13.4.jar"))
        assertNull(missing("junit-4.13.2.jar", "hamcrest-core-1.3.jar"))
        assertNull(missing())
    }
}
