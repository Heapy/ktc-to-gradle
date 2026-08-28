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
    fun unixLauncherRefusesToRunWithoutUnzipAndCleansUpAfterItself() {
        val unix = StaticAssets.unixGradleLauncher()
        assertContains(unix, "if ! command -v unzip >/dev/null 2>&1; then")
        assertContains(unix, "ktc-to-gradle: unzip is required to unpack Gradle")
        // The guard has to precede the download: a missing unzip must not cost a Gradle distribution.
        assertTrue(unix.indexOf("command -v unzip") < unix.indexOf("command -v curl"))
        // The unpack directory is removed on an ordinary failure and on a CI cancellation alike;
        // an EXIT trap alone does not run when the shell is killed by a signal.
        assertContains(unix, "trap 'STATUS=\$?; rm -rf \"\$TMP\" || :; exit \$STATUS' EXIT")
        assertContains(unix, "trap 'exit 143' TERM")
        assertContains(unix, "trap - EXIT HUP INT TERM")
    }

    @Test
    fun windowsLauncherEndsWithCrLfAndUnixLauncherDoesNot() {
        assertTrue(StaticAssets.windowsGradleLauncher().endsWith("\r\n"))
        val unix = StaticAssets.unixGradleLauncher()
        assertTrue(unix.endsWith("\n"))
        assertFalse(unix.endsWith("\r\n"))
    }
}
