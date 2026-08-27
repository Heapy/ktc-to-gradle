package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.ModuleLayoutProbe
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleLayoutProbeTest {
    @Test
    fun aPackageQualifiedMainInSrcIsDetected() {
        val module = module("src/app/main.kt" to "package app\n\nfun main() {}\n")

        assertEquals("app.MainKt", probe(module).detectedMainClass)
    }

    @Test
    fun aMainInSrcAtJvmIsDetected() {
        val module = module("src@jvm/app/main.kt" to "package app\n\nfun main() {}\n")

        assertEquals("app.MainKt", probe(module).detectedMainClass)
    }

    @Test
    fun aMainInTheDefaultPackageKeepsTheBareClassName() {
        val module = module("src/main.kt" to "fun main() {}\n")

        assertEquals("MainKt", probe(module).detectedMainClass)
    }

    @Test
    fun aSourceTreeWithoutAMainKtDetectsNothing() {
        val module = module("src/app/Service.kt" to "package app\n\nfun main() {}\n")

        assertNull(probe(module).detectedMainClass)
    }

    @Test
    fun aMainKtWithoutAMainFunctionDetectsNothing() {
        val module = module("src/main.kt" to "package app\n\nval mainMenu = 1\n")

        assertNull(probe(module).detectedMainClass)
    }

    @Test
    fun aModuleWithoutSourcesDetectsNothing() {
        val module = module()

        assertNull(probe(module).detectedMainClass)
    }

    @Test
    fun srcTakesPrecedenceOverSrcAtJvm() {
        val module = module(
            "src/main.kt" to "package common\n\nfun main() {}\n",
            "src@jvm/main.kt" to "package jvm\n\nfun main() {}\n",
        )

        assertEquals("common.MainKt", probe(module).detectedMainClass)
    }

    @Test
    fun existingSourceDirsReportsOnlyDirectoriesThatExist() {
        val module = module(
            "src/main.kt" to "fun main() {}\n",
            "src@jvm/Jvm.kt" to "class Jvm\n",
            "test@android/Test.kt" to "class Test\n",
            "resources@jvm/app.properties" to "a=b\n",
            "testResources/fixture.txt" to "fixture\n",
            "docs/readme.md" to "hi\n",
            "srcgen/Gen.kt" to "class Gen\n",
        )

        assertEquals(
            setOf("src", "src@jvm", "test@android", "resources@jvm", "testResources"),
            probe(module).existingSourceDirs,
        )
    }

    @Test
    fun aModuleWithNoSourceDirectoriesReportsAnEmptySet() {
        assertEquals(emptySet<String>(), probe(module()).existingSourceDirs)
    }

    private fun probe(module: Path) =
        ModuleLayoutProbe(FileSystem.SYSTEM).probe(module.toString().toPath())

    private fun module(vararg files: Pair<String, String>): Path {
        val directory = Files.createTempDirectory("ktc-to-gradle-layout-")
        write(directory.resolve("module.yaml"), "product: jvm/app\n")
        for ((path, content) in files) write(directory.resolve(path), content)
        return directory
    }

    private fun write(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content)
    }
}
