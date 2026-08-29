package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.PluginResolution
import io.heapy.ktctogradle.load.KotlinSettings
import io.heapy.ktctogradle.load.Layout
import io.heapy.ktctogradle.load.ProductSpec
import io.heapy.ktctogradle.load.SerializationSpec
import io.heapy.ktctogradle.load.Settings
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.PluginDecl
import io.heapy.ktctogradle.model.PluginFamily
import io.heapy.ktctogradle.render.declaration
import io.heapy.ktctogradle.render.dsl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plugin resolution is a pure `List<ToolchainModel> -> versions` function, so every case here is a
 * hand-built model and an `assertEquals`. No filesystem, no YAML.
 */
class PluginResolutionTest {
    @Test
    fun higherNumericPartWins() {
        assertEquals("2.4.10", highest("2.4.9", "2.4.10"))
    }

    @Test
    fun aMissingPartCountsAsZero() {
        assertEquals("2.4.1", highest("2.4", "2.4.1"))
        assertEquals("2.4.0", highest("2.4", "2.4.0"))
    }

    @Test
    fun aReleaseBeatsThePreReleaseThatCarriesTheSameNumbers() {
        assertEquals("2.4.10", highest("2.4.10-RC", "2.4.10"))
    }

    @Test
    fun qualifiersAreOrderedAlphabetically() {
        assertEquals("2.4.10-beta1", highest("2.4.10-alpha1", "2.4.10-beta1"))
        assertEquals("2.4.10-rc1", highest("2.4.10-beta1", "2.4.10-rc1"))
    }

    @Test
    fun aNumericQualifierSuffixIsComparedAsANumber() {
        assertEquals("2.4.10-beta10", highest("2.4.10-beta9", "2.4.10-beta10"))
    }

    @Test
    fun aQualifierPrefixLosesToTheLongerQualifier() {
        assertEquals("2.4.10-beta1", highest("2.4.10-beta", "2.4.10-beta1"))
    }

    @Test
    fun anUnpinnedProjectUsesTheDefaultKotlinVersion() {
        val diagnostics = DiagnosticCollector()
        val versions = PluginResolution.resolveVersions(listOf(jvmModel()), diagnostics)
        assertEquals(mapOf("org.jetbrains.kotlin.jvm" to Versions.KOTLIN), versions)
        assertEquals(emptyList(), diagnostics.collected())
    }

    /** The pin decides the version, and the module that would have taken the default is told so. */
    @Test
    fun anExplicitPinWinsOverTheDefaultEvenWhenItIsLower() {
        val diagnostics = DiagnosticCollector()
        val versions = PluginResolution.resolveVersions(
            listOf(jvmModel(kotlinVersion = "2.0.0"), jvmModel()),
            diagnostics,
        )
        assertEquals("2.0.0", versions.getValue("org.jetbrains.kotlin.jvm"))
        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.WARNING,
                    "The kotlin plugin is requested at more than one version (2.0.0, ${Versions.KOTLIN}); " +
                        "Gradle loads it once for the whole build, so the generated build uses 2.0.0 for " +
                        "every module",
                ),
            ),
            diagnostics.collected(),
        )
    }

    @Test
    fun twoConflictingExplicitPinsPickTheHighest() {
        val diagnostics = DiagnosticCollector()
        val versions = PluginResolution.resolveVersions(
            listOf(jvmModel(kotlinVersion = "2.0.0"), jvmModel(kotlinVersion = "2.2.0")),
            diagnostics,
        )
        assertEquals("2.2.0", versions.getValue("org.jetbrains.kotlin.jvm"))
    }

    @Test
    fun anAndroidModuleDoesNotForceAKotlinPin() {
        val diagnostics = DiagnosticCollector()
        val versions = PluginResolution.resolveVersions(
            listOf(
                androidModel(kotlinVersion = "1.9.0", serialization = true),
                jvmModel(),
            ),
            diagnostics,
        )
        assertEquals(Versions.KOTLIN, versions.getValue("org.jetbrains.kotlin.jvm"))
        assertEquals(Versions.KOTLIN, versions.getValue("org.jetbrains.kotlin.plugin.serialization"))
        assertEquals(Versions.ANDROID_GRADLE_PLUGIN, versions.getValue("com.android.application"))
    }

    @Test
    fun theMultiVersionWarningIsUnchangedWordForWord() {
        val diagnostics = DiagnosticCollector()
        PluginResolution.resolveVersions(
            listOf(jvmModel(kotlinVersion = "2.4.10"), jvmModel(kotlinVersion = "2.4.20")),
            diagnostics,
        )
        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.WARNING,
                    "The kotlin plugin is requested at more than one version (2.4.10, 2.4.20); Gradle loads it " +
                        "once for the whole build, so the generated build uses 2.4.20 for every module",
                ),
            ),
            diagnostics.collected(),
        )
    }

    @Test
    fun oneVersionAcrossTheProjectWarnsAboutNothing() {
        val diagnostics = DiagnosticCollector()
        PluginResolution.resolveVersions(listOf(jvmModel(), jvmModel()), diagnostics)
        assertEquals(emptyList(), diagnostics.collected())
    }

    @Test
    fun aPluginOnlySubprojectsUseIsDeclaredOnceInTheRootWithApplyFalse() {
        val subprojects = listOf(jvmModel(), jvmModel(), androidModel())
        val versions = PluginResolution.resolveVersions(subprojects, DiagnosticCollector())
        assertEquals(
            listOf(
                PluginDecl(GradlePlugin.Kotlin.JVM, Versions.KOTLIN, apply = false),
                PluginDecl(GradlePlugin.Android.APPLICATION, Versions.ANDROID_GRADLE_PLUGIN, apply = false),
            ),
            PluginResolution.inheritedDeclarations(root = null, subprojects = subprojects, versions = versions),
        )
    }

    @Test
    fun aPluginTheRootAppliesItselfIsNotInherited() {
        val root = jvmModel()
        val subprojects = listOf(jvmModel(), androidModel())
        val versions = PluginResolution.resolveVersions(listOf(root) + subprojects, DiagnosticCollector())
        assertEquals(
            listOf(PluginDecl(GradlePlugin.Android.APPLICATION, Versions.ANDROID_GRADLE_PLUGIN, apply = false)),
            PluginResolution.inheritedDeclarations(root, subprojects, versions),
        )
    }

    @Test
    fun onlyARootScriptPrintsVersions() {
        val model = jvmModel(product = "jvm/app", serialization = true)
        val versions = PluginResolution.resolveVersions(listOf(model), DiagnosticCollector())
        assertEquals(
            listOf(
                PluginDecl(GradlePlugin.Kotlin.JVM, Versions.KOTLIN),
                PluginDecl(GradlePlugin.Builtin.APPLICATION, null),
                PluginDecl(GradlePlugin.Kotlin.SERIALIZATION, Versions.KOTLIN),
            ),
            PluginResolution.declarationsFor(model, versions, declareVersions = true),
        )
        assertEquals(
            listOf(
                PluginDecl(GradlePlugin.Kotlin.JVM, null),
                PluginDecl(GradlePlugin.Builtin.APPLICATION, null),
                PluginDecl(GradlePlugin.Kotlin.SERIALIZATION, null),
            ),
            PluginResolution.declarationsFor(model, versions, declareVersions = false),
        )
    }

    @Test
    fun aBuiltinIsUnversionedAndThereforeNeverResolvedOrInherited() {
        for (builtin in GradlePlugin.Builtin.entries) {
            assertNull(builtin.family, "${builtin.id} must carry no plugin family")
        }
        val model = jvmModel(product = "jvm/app")
        assertTrue(GradlePlugin.Builtin.APPLICATION in PluginResolution.pluginsOf(model))
        val versions = PluginResolution.resolveVersions(listOf(model), DiagnosticCollector())
        assertEquals(mapOf("org.jetbrains.kotlin.jvm" to Versions.KOTLIN), versions)
        assertEquals(
            listOf(PluginDecl(GradlePlugin.Kotlin.JVM, Versions.KOTLIN, apply = false)),
            PluginResolution.inheritedDeclarations(root = null, subprojects = listOf(model), versions = versions),
        )
    }

    @Test
    fun aModuleWhoseProductFailedToBindContributesNoPlugins() {
        val model = jvmModel().copy(errors = mapOf("product" to "Every module must declare product"))
        assertEquals(emptyList(), PluginResolution.pluginsOf(model))
        assertEquals(emptyMap(), PluginResolution.resolveVersions(listOf(model), DiagnosticCollector()))
    }

    @Test
    fun aModuleWhoseSerializationFailedToBindStillContributesItsOtherPlugins() {
        val model = jvmModel().copy(
            errors = mapOf("settings.kotlin.serialization" to "settings.kotlin.serialization must be a string or object"),
        )
        assertEquals(listOf(GradlePlugin.Kotlin.JVM), PluginResolution.pluginsOf(model))
    }

    @Test
    fun aMultiplatformModuleOnAndroidAppliesTheAndroidLibraryPlugin() {
        val model = jvmModel(product = "kmp/lib", platforms = listOf("jvm", "android"))
        assertEquals(
            listOf(GradlePlugin.Kotlin.MULTIPLATFORM, GradlePlugin.Android.KMP_LIBRARY),
            PluginResolution.pluginsOf(model),
        )
    }

    @Test
    fun everyPluginRendersItsOwnDslSyntax() {
        assertEquals("kotlin(\"jvm\")", GradlePlugin.Kotlin.JVM.dsl())
        assertEquals("kotlin(\"multiplatform\")", GradlePlugin.Kotlin.MULTIPLATFORM.dsl())
        assertEquals("kotlin(\"plugin.serialization\")", GradlePlugin.Kotlin.SERIALIZATION.dsl())
        assertEquals("id(\"com.android.application\")", GradlePlugin.Android.APPLICATION.dsl())
        assertEquals("id(\"com.android.kotlin.multiplatform.library\")", GradlePlugin.Android.KMP_LIBRARY.dsl())
        assertEquals("application", GradlePlugin.Builtin.APPLICATION.dsl())
        assertEquals("base", GradlePlugin.Builtin.BASE.dsl())
        assertEquals("id(\"com.google.devtools.ksp\")", GradlePlugin.Other("com.google.devtools.ksp", null).dsl())
    }

    @Test
    fun aDeclarationCarriesItsVersionAndItsApplyFlag() {
        assertEquals("base", PluginDecl(GradlePlugin.Builtin.BASE, null).declaration())
        assertEquals(
            "kotlin(\"jvm\") version \"2.4.10\"",
            PluginDecl(GradlePlugin.Kotlin.JVM, "2.4.10").declaration(),
        )
        assertEquals(
            "id(\"com.android.application\") version \"9.0.0\" apply false",
            PluginDecl(GradlePlugin.Android.APPLICATION, "9.0.0", apply = false).declaration(),
        )
    }

    @Test
    fun everyFamilyNameIsTheOneTheWarningQuotes() {
        assertEquals(listOf("kotlin", "android"), PluginFamily.entries.map(PluginFamily::displayName))
    }

    private fun highest(left: String, right: String): String =
        listOf(left, right).maxWith(PluginResolution.versionOrder)

    private fun jvmModel(
        product: String = "jvm/lib",
        platforms: List<String> = listOf("jvm"),
        kotlinVersion: String? = null,
        serialization: Boolean = false,
    ): ToolchainModel = model(product, platforms, kotlinVersion, serialization)

    private fun androidModel(
        kotlinVersion: String? = null,
        serialization: Boolean = false,
    ): ToolchainModel = model("android/app", listOf("android"), kotlinVersion, serialization)

    private fun model(
        product: String,
        platforms: List<String>,
        kotlinVersion: String?,
        serialization: Boolean,
    ): ToolchainModel = ToolchainModel(
        product = ProductSpec(product, platforms),
        layout = Layout.AMPER,
        aliases = emptyMap(),
        dependencies = emptyMap(),
        testDependencies = emptyMap(),
        repositories = emptyList(),
        settings = if (kotlinVersion == null && !serialization) {
            Settings.EMPTY
        } else {
            Settings(
                kotlin = KotlinSettings(
                    version = kotlinVersion,
                    serialization = if (serialization) SerializationSpec() else null,
                ),
            )
        },
        qualifiedSections = emptyList(),
        unsupported = emptyList(),
        unknownKeys = emptyList(),
        errors = emptyMap(),
    )
}
