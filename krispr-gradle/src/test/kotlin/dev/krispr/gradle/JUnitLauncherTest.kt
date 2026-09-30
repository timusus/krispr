package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JUnitLauncherTest {
    private fun missing(vararg modules: String) =
        KrisprGradlePlugin.missingLauncherVersion(modules.map { it.substringBeforeLast(':') to it.substringAfterLast(':') })

    @Test
    fun aPlatformEngineWithoutALauncherGetsTheEngineVersion() {
        assertEquals(
            "1.10.2",
            missing("org.junit.jupiter:junit-jupiter-engine:5.10.2", "org.junit.platform:junit-platform-engine:1.10.2", "org.junit.platform:junit-platform-commons:1.10.2"),
        )
        assertEquals("6.0.0-M1", missing("org.junit.platform:junit-platform-engine:6.0.0-M1"))
    }

    @Test
    fun aClasspathWithALauncherOrNoPlatformNeedsNone() {
        assertNull(missing("org.junit.platform:junit-platform-engine:1.13.4", "org.junit.platform:junit-platform-launcher:1.13.4"))
        assertNull(missing("junit:junit:4.13.2", "org.hamcrest:hamcrest-core:1.3"))
        assertNull(missing())
    }
}
