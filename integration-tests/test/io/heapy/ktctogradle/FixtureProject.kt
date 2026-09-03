package io.heapy.ktctogradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal data class ProcessRun(val exitCode: Int, val text: String)

internal fun projectRoot(): Path {
    var current = Path.of("").toAbsolutePath().normalize()
    while (true) {
        if (
            Files.isRegularFile(current.resolve("project.yaml")) &&
            Files.isDirectory(current.resolve("integration-tests/fixtures"))
        ) {
            return current
        }
        current = current.parent
            ?: error("Could not locate the ktc-to-gradle project root from ${Path.of("").toAbsolutePath()}")
    }
}

internal fun copyFixture(fixture: String): Path {
    val source = projectRoot().resolve("integration-tests/fixtures/$fixture")
    val destination = Files.createTempDirectory("ktc-to-gradle-$fixture-")
    Files.walk(source).use { paths ->
        paths.forEach { path ->
            val target = destination.resolve(source.relativize(path).toString())
            if (path.isDirectory()) {
                target.createDirectories()
            } else {
                target.parent.createDirectories()
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
    return destination
}

internal fun runKotlinToolchain(project: Path, vararg arguments: String): ProcessRun {
    val root = projectRoot()
    val command = if (isWindows) {
        listOf("cmd", "/c", root.resolve("kotlin.bat").toString())
    } else {
        listOf("sh", root.resolve("kotlin").toString())
    } + arguments + listOf("--project-dir", project.toString())
    // The wrapper picks its distribution from the wrapper script beside the project.yaml it finds
    // above the working directory, so run it from the checkout that owns it, not from the copy.
    val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
    builder.environment()["KOTLIN_CLI_NO_WELCOME_BANNER"] = "1"
    val process = builder.start()
    val text = process.inputStream.bufferedReader().readText()
    return ProcessRun(process.waitFor(), text)
}

internal fun buildWithKotlinToolchain(project: Path, fixture: String): Set<String> {
    val run = runKotlinToolchain(project, "test")
    assertEquals(0, run.exitCode, "Fixture '$fixture' does not build with the Kotlin Toolchain:\n${run.text}")
    // The Toolchain writes JUnit XML for its JVM and Android tests only, so a native or web test it
    // ran is covered by the exit code above and not by the returned set.
    return testCasesIn(project.resolve("build/reports"))
}

internal fun gradle(
    project: Path,
    vararg arguments: String,
    environment: Map<String, String> = emptyMap(),
): ProcessRun {
    val wrapper = project.resolve(if (isWindows) "gradlew.bat" else "gradlew").toString()
    val launcher = if (isWindows) listOf("cmd", "/c", wrapper) else listOf("sh", wrapper)
    val builder = ProcessBuilder(launcher + listOf("--no-daemon", "--stacktrace") + arguments)
        .directory(project.toFile())
        .redirectErrorStream(true)
    builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
    // A signing key in the developer's environment would silently change what `publish` does.
    builder.environment().remove("KOTLIN_TOOLCHAIN_SIGNING_KEY")
    builder.environment().putAll(environment)
    val process = builder.start()
    val text = process.inputStream.bufferedReader().readText()
    return ProcessRun(process.waitFor(), text)
}

internal fun assertGradleRanEveryToolchainTest(project: Path, fixture: String, toolchainTests: Set<String>) {
    assertTrue(
        toolchainTests.isNotEmpty(),
        "The Kotlin Toolchain ran no test of fixture '$fixture', so nothing constrains the Gradle build",
    )
    val missing = toolchainTests - gradleTestCases(project)
    assertEquals(
        emptySet(),
        missing,
        "The converted fixture '$fixture' never ran tests the Kotlin Toolchain runs: $missing",
    )
}

private fun gradleTestCases(project: Path): Set<String> =
    Files.walk(project).use { paths ->
        paths.filter { it.isDirectory() && it.fileName.toString() == "test-results" }
            .toList()
    }.flatMapTo(mutableSetOf(), ::testCasesIn)

private fun testCasesIn(reports: Path): Set<String> {
    if (!Files.isDirectory(reports)) return emptySet()
    val files = Files.walk(reports).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().matches(REPORT_FILE) }.toList()
    }
    return files.flatMapTo(mutableSetOf()) { file ->
        TEST_CASE.findAll(Files.readString(file)).mapNotNull { element ->
            val attributes = ATTRIBUTE.findAll(element.value)
                .associate { it.groupValues[1] to it.groupValues[2] }
            val className = attributes["classname"] ?: return@mapNotNull null
            val name = attributes["name"] ?: return@mapNotNull null
            // A multiplatform test task appends the target to the reported name, as in `run()[jvm]`,
            // while the Kotlin Toolchain reports the bare name under a per-target report directory.
            "$className.${name.replace(TARGET_SUFFIX, "")}"
        }
    }
}

private val REPORT_FILE = Regex("""TEST-.+\.xml""")

private val TEST_CASE = Regex("""<testcase\s[^>]*>""")

private val ATTRIBUTE = Regex("""([A-Za-z_][\w.-]*)="([^"]*)"""")

private val TARGET_SUFFIX = Regex("""\[[^\[\]]*]$""")

internal fun assertGradleJarsMatchToolchainJars(
    project: Path,
    fixture: String,
    skippedModules: Set<String> = emptySet(),
    kotlinModuleNamesDiffer: Boolean = false,
) {
    val toolchainJars = toolchainJvmJars(project) - skippedModules
    // An Android product packages an aar or an apk instead, and has nothing to pair here.
    if (toolchainJars.isEmpty()) return
    val gradleJars = gradleJarsByName(project)
    for ((module, toolchainJar) in toolchainJars.toSortedMap()) {
        // A multiplatform module also writes an empty root jar and a metadata jar beside the one
        // that carries the JVM classes.
        val gradleJar = gradleJars["$module-jvm"] ?: gradleJars[module] ?: fail(
            "The converted fixture '$fixture' built no jar for module '$module'",
        )
        assertEquals(
            jarContent(toolchainJar, kotlinModuleNamesDiffer),
            jarContent(gradleJar, kotlinModuleNamesDiffer),
            "The jar of module '$module' in fixture '$fixture' differs from the Kotlin Toolchain jar",
        )
    }
}

internal fun assertGradleRuntimeClasspathCoversToolchain(
    project: Path,
    fixture: String,
    environment: Map<String, String> = emptyMap(),
    skippedModules: Set<String> = emptySet(),
    // Coordinates the Gradle build may resolve differently, each either `group:artifact` or a full
    // `group:artifact:version`.
    allowedMissingDependencies: Set<String> = emptySet(),
) {
    val blocks = toolchainDependencyBlocks(project, fixture).filterNot { it.module in skippedModules }
    assertTrue(
        blocks.isNotEmpty(),
        "Read no dependency graph of fixture '$fixture' out of the Kotlin Toolchain",
    )
    val expected = mutableMapOf<ClasspathKey, MutableSet<String>>()
    for (block in blocks.filter { "jvm" in it.platforms }) {
        expected.getOrPut(ClasspathKey(block.module, test = true)) { mutableSetOf() } += block.coordinates
        if (!block.test) {
            expected.getOrPut(ClasspathKey(block.module, test = false)) { mutableSetOf() } += block.coordinates
        }
    }
    // A module that targets no JVM platform resolves nothing a Gradle JVM classpath could hold.
    if (expected.isEmpty()) return

    val dump = gradle(
        project,
        "-I",
        projectRoot().resolve(CLASSPATH_INIT_SCRIPT).toString(),
        "dumpRuntimeClasspath",
        environment = environment,
    )
    assertEquals(0, dump.exitCode, "Dumping the runtime classpath of fixture '$fixture' failed:\n${dump.text}")
    val actual = gradleRuntimeClasspaths(project)

    val missing = sortedMapOf<String, Set<String>>()
    for ((key, coordinates) in expected) {
        val label = "${key.module}${if (key.test) " (test)" else ""}"
        val resolved = actual[key] ?: fail(
            "The converted fixture '$fixture' has no ${if (key.test) "test " else ""}runtime classpath " +
                "for module '${key.module}'",
        )
        val absent = coordinates.filterNotTo(sortedSetOf()) { coordinate ->
            coordinate in resolved || allowedMissingDependencies.any {
                coordinate == it || coordinate.startsWith("$it:")
            }
        }
        if (absent.isNotEmpty()) missing[label] = absent
    }
    assertEquals(
        emptyMap(),
        missing,
        "The converted fixture '$fixture' misses what the Kotlin Toolchain resolves: $missing",
    )
}

private data class ClasspathKey(val module: String, val test: Boolean)

private data class DependencyBlock(
    val module: String,
    val test: Boolean,
    val platforms: Set<String>,
    val coordinates: Set<String>,
)

private fun toolchainDependencyBlocks(project: Path, fixture: String): List<DependencyBlock> {
    val run = runKotlinToolchain(project, "show", "dependencies", "--all-modules", "--scope=runtime", "--include-tests")
    assertEquals(
        0,
        run.exitCode,
        "The Kotlin Toolchain refused to print the dependencies of fixture '$fixture':\n${run.text}",
    )
    val blocks = mutableListOf<DependencyBlock>()
    var module: String? = null
    var fragment = "main"
    var platforms = emptySet<String>()
    var coordinates = mutableSetOf<String>()

    fun flush() {
        module?.let { blocks += DependencyBlock(it, fragment == "test", platforms, coordinates) }
        module = null
        coordinates = mutableSetOf()
    }

    for (line in run.text.lineSequence()) {
        val header = BLOCK_HEADER.matchEntire(line)
        val platformList = PLATFORMS.matchEntire(line)
        val fragmentName = FRAGMENT.matchEntire(line)
        when {
            header != null -> {
                flush()
                module = header.groupValues[1]
                fragment = "main"
                platforms = emptySet()
            }
            module == null -> Unit
            platformList != null ->
                platforms = platformList.groupValues[1].split(",").mapTo(mutableSetOf(), String::trim)
            fragmentName != null -> fragment = fragmentName.groupValues[1]
            else -> resolvedCoordinate(line)?.let { coordinates += it }
        }
    }
    flush()
    return blocks
}

private fun resolvedCoordinate(line: String): String? {
    val payload = line.trimStart(*TREE_GLYPHS)
    val requested = payload.substringBefore(' ').substringBefore(',')
    // A `<module>:<fragment>:<group>:<artifact>:<version>` node states what a source set asked for;
    // only the three-part nodes below it are what the resolver settled on.
    if (requested.count { it == ':' } != 2) return null
    if (" -> " !in payload) return requested
    val version = payload.substringAfter(" -> ").substringBefore(' ').substringBefore(',')
    return requested.substringBeforeLast(':') + ":" + version
}

private fun gradleRuntimeClasspaths(project: Path): Map<ClasspathKey, Set<String>> {
    val files = Files.walk(project).use { paths ->
        paths.filter {
            Files.isRegularFile(it) && it.parent.fileName.toString() == CLASSPATH_DUMP_DIRECTORY
        }.toList()
    }
    val classpaths = mutableMapOf<ClasspathKey, MutableSet<String>>()
    for (file in files) {
        val key = ClasspathKey(
            module = file.parent.parent.parent.fileName.toString(),
            test = file.fileName.toString().contains("test", ignoreCase = true),
        )
        classpaths.getOrPut(key) { mutableSetOf() } += Files.readAllLines(file).filter(String::isNotBlank)
    }
    return classpaths
}

private fun toolchainJvmJars(project: Path): Map<String, Path> {
    val tasks = project.resolve("build/tasks")
    if (!Files.isDirectory(tasks)) return emptyMap()
    val directories = Files.list(tasks).use { it.toList() }
        .filter { it.isDirectory() && it.fileName.toString().endsWith(TOOLCHAIN_JVM_JAR_TASK) }
    return directories.mapNotNull { directory ->
        val jar = Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }.findFirst()
        }.orElse(null)
        jar?.let { directory.fileName.toString().removePrefix("_").removeSuffix(TOOLCHAIN_JVM_JAR_TASK) to it }
    }.toMap()
}

private fun gradleJarsByName(project: Path): Map<String, Path> =
    Files.walk(project).use { paths ->
        paths.filter {
            Files.isRegularFile(it) &&
                it.fileName.toString().endsWith(".jar") &&
                it.parent.fileName.toString() == "libs" &&
                it.parent.parent.fileName.toString() == "build"
        }.toList()
    }.associateBy { it.fileName.toString().removeSuffix(".jar") }

private data class JarContent(val entries: Set<String>, val manifest: Map<String, String>)

private fun jarContent(jar: Path, anonymiseKotlinModule: Boolean): JarContent = JarFile(jar.toFile()).use { file ->
    val entries = file.entries().asSequence()
        .filterNot { it.isDirectory }
        .mapTo(sortedSetOf()) { entry ->
            if (anonymiseKotlinModule && entry.name.endsWith(KOTLIN_MODULE)) KOTLIN_MODULE else entry.name
        }
    val manifest = file.manifest
        ?.mainAttributes
        ?.entries
        ?.associate { it.key.toString() to it.value.toString() }
        ?.toSortedMap()
        ?: sortedMapOf()
    JarContent(entries, manifest)
}

private const val CLASSPATH_INIT_SCRIPT = "integration-tests/testResources/dump-runtime-classpath.init.gradle.kts"

private const val CLASSPATH_DUMP_DIRECTORY = "ktc-to-gradle-classpath"

private val TREE_GLYPHS = charArrayOf(' ', '│', '├', '╰', '╭', '─')

private val BLOCK_HEADER = Regex("""Module (\S+)\s*""")

private val FRAGMENT = Regex("""│ - (\w+)\s*""")

private val PLATFORMS = Regex("""│ - platforms = \[(.*)]\s*""")

private const val TOOLCHAIN_JVM_JAR_TASK = "_jarJvm"

private const val KOTLIN_MODULE = ".kotlin_module"
