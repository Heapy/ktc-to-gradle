package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.Versions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the wrapper assets have to say that a byte-for-byte baseline cannot explain.
 *
 * A golden file states the bytes; it does not state that the properties file names the Gradle
 * version we pin, that `gradlew.bat` has to end every line with CRLF, or that both scripts have to
 * carry the ownership marker or the converter will refuse its own output. That is what this suite
 * is for, and it is the one place in `render/` where substring assertions are the right shape.
 */
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

    /**
     * Gradle's own scripts carry no marker. `tools/update-gradle-wrapper.sh` adds one to each while
     * embedding them, because `write/FileWriter` refuses to replace a file that has none: without
     * it the converter would refuse its own output on the second run.
     */
    @Test
    fun bothLaunchersCarryTheOwnershipMarker() {
        assertContains(GradleWrapperAssets.unixLauncher(), StaticAssets.GENERATED_MARKER)
        assertContains(GradleWrapperAssets.windowsLauncher(), StaticAssets.GENERATED_MARKER)
    }

    /** The shebang has to stay on line one, so the marker goes underneath it and not above. */
    @Test
    fun theUnixLauncherStillStartsWithItsShebang() {
        assertTrue(GradleWrapperAssets.unixLauncher().startsWith("#!/bin/sh\n"))
    }

    @Test
    fun everyLineOfTheWindowsLauncherEndsWithCrLf() {
        val windows = GradleWrapperAssets.windowsLauncher()
        assertEquals(
            windows.count { it == '\r' },
            windows.count { it == '\n' },
            "Every LF in gradlew.bat has to be preceded by a CR",
        )
        assertFalse(Regex("[^\r]\n") in windows, "gradlew.bat carries a bare LF")
    }

    /** The launchers are Gradle's, unmodified apart from the marker: nothing of ours runs in them. */
    @Test
    fun theUnixLauncherIsGradleOwnScriptAndNotOneOfOurs() {
        val unix = GradleWrapperAssets.unixLauncher()
        assertContains(unix, "-jar \"\$APP_HOME/gradle/wrapper/gradle-wrapper.jar\"")
        assertFalse("ktc-to-gradle:" in unix, "no error message of ours belongs in Gradle's launcher")
    }

    /** The jar is what actually reads `gradle-wrapper.properties`, so it has to be a real jar. */
    @Test
    fun theWrapperJarIsAZipArchive() {
        assertEquals("PK", GradleWrapperAssets.wrapperJar().substring(0, 2).utf8())
    }
}

private operator fun Regex.contains(input: CharSequence): Boolean = containsMatchIn(input)
