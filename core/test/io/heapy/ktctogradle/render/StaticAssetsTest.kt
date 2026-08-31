package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.Versions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Asset invariants whose intent is not explained by byte-for-byte golden files. */
class StaticAssetsTest {
    @Test
    fun wrapperPropertiesEmbedsTheGradleVersionAndChecksum() {
        val properties = StaticAssets.wrapperProperties()
        assertContains(properties, "gradle-${Versions.GRADLE}-bin.zip")
        assertContains(properties, "distributionSha256Sum=${Versions.GRADLE_SHA256}")
    }

    @Test
    fun theGeneratedPropertiesTurnOffTheDefaultKotlinHierarchyTemplate() {
        assertContains(
            StaticAssets.generatedGradleProperties(),
            "kotlin.mpp.applyDefaultHierarchyTemplate=false",
        )
    }

    @Test
    fun theGeneratedPropertiesTurnOffTheKotlinTestVariantInference() {
        assertContains(
            StaticAssets.generatedGradleProperties(),
            "kotlin.test.infer.jvm.variant=false",
        )
    }

    @Test
    fun headerCarriesTheConverterVersion() {
        assertContains(StaticAssets.header(), Versions.CONVERTER)
    }

    @Test
    fun bothLaunchersCarryTheOwnershipMarker() {
        assertContains(GradleWrapperAssets.unixLauncher(), StaticAssets.GENERATED_MARKER)
        assertContains(GradleWrapperAssets.windowsLauncher(), StaticAssets.GENERATED_MARKER)
    }

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

    @Test
    fun theUnixLauncherIsGradleOwnScriptAndNotOneOfOurs() {
        val unix = GradleWrapperAssets.unixLauncher()
        assertContains(unix, "-jar \"\$APP_HOME/gradle/wrapper/gradle-wrapper.jar\"")
        assertFalse("ktc-to-gradle:" in unix, "no error message of ours belongs in Gradle's launcher")
    }

    @Test
    fun theWrapperJarIsAZipArchive() {
        assertEquals("PK", GradleWrapperAssets.wrapperJar().substring(0, 2).utf8())
    }
}

private operator fun Regex.contains(input: CharSequence): Boolean = containsMatchIn(input)
