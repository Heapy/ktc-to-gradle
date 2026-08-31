package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.ModuleLayoutProbe
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path as OkioPath
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

    @Test
    fun theDetectedMainClassDoesNotDependOnTheDirectoryListingOrder() {
        val module = module(
            "src/zzz/main.kt" to "package zzz\n\nfun main() {}\n",
            "src/aaa/main.kt" to "package aaa\n\nfun main() {}\n",
            "src/mmm/main.kt" to "package mmm\n\nfun main() {}\n",
        )

        for (fileSystem in listOf(FileSystem.SYSTEM, ReversedListingFileSystem(FileSystem.SYSTEM))) {
            assertEquals(
                "aaa.MainKt",
                ModuleLayoutProbe(fileSystem).probe(module.toString().toPath()).detectedMainClass,
            )
        }
    }

    @Test
    fun aFirstMainKtWithoutAMainFunctionDoesNotFallBackWithinTheSameSourceRoot() {
        assertEquals(
            null,
            probe(
                module(
                    "src/aaa/main.kt" to "package aaa\n\nval mainMenu = 1\n",
                    "src/zzz/main.kt" to "package zzz\n\nfun main() {}\n",
                ),
            ).detectedMainClass,
        )

        assertEquals(
            "jvm.MainKt",
            probe(
                module(
                    "src/aaa/main.kt" to "package aaa\n\nval mainMenu = 1\n",
                    "src@jvm/main.kt" to "package jvm\n\nfun main() {}\n",
                ),
            ).detectedMainClass,
        )
    }

    @Test
    fun theWalkStopsAtTheFirstMainKt() {
        val module = module(
            "src/aaa/main.kt" to "package aaa\n\nfun main() {}\n",
            "src/zzz/Service.kt" to "package zzz\n",
        )

        assertEquals(
            "aaa.MainKt",
            ModuleLayoutProbe(UnreadableDirectoryFileSystem(FileSystem.SYSTEM, "zzz"))
                .probe(module.toString().toPath())
                .detectedMainClass,
        )
    }

    private class UnreadableDirectoryFileSystem(
        delegate: FileSystem,
        private val refused: String,
    ) : ForwardingFileSystem(delegate) {
        override fun list(dir: OkioPath): List<OkioPath> {
            check(dir.name != refused) { "The walk listed '$refused', so it did not stop at the first main.kt" }
            return super.list(dir)
        }
    }

    private class ReversedListingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        override fun list(dir: OkioPath): List<OkioPath> = super.list(dir).reversed()
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
