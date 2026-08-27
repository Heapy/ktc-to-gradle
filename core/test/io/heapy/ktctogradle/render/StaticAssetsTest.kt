package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.Versions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticAssetsTest {
    @Test
    fun wrapperPropertiesEmbedsTheGradleVersionAndChecksum() {
        val properties = StaticAssets.wrapperProperties()
        assertContains(properties, "gradle-${Versions.GRADLE}-bin.zip")
        assertContains(properties, "distributionSha256Sum=${Versions.GRADLE_SHA256}")
    }

    @Test
    fun headerCarriesTheConverterVersion() {
        assertContains(StaticAssets.header(), Versions.CONVERTER)
    }

    @Test
    fun windowsLauncherEndsWithCrLfAndUnixLauncherDoesNot() {
        assertTrue(StaticAssets.windowsGradleLauncher().endsWith("\r\n"))
        val unix = StaticAssets.unixGradleLauncher()
        assertTrue(unix.endsWith("\n"))
        assertFalse(unix.endsWith("\r\n"))
    }
}
