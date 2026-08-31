package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.ProjectInterpreter
import io.heapy.ktctogradle.load.ProjectLoader
import io.heapy.ktctogradle.load.ToolchainProject
import io.heapy.ktctogradle.model.FileContent
import io.heapy.ktctogradle.model.GeneratedFile
import io.heapy.ktctogradle.model.GradleProject
import io.heapy.ktctogradle.render.GradleWrapperAssets
import io.heapy.ktctogradle.render.StaticAssets
import io.heapy.ktctogradle.render.renderModule
import io.heapy.ktctogradle.render.renderSettings
import io.heapy.ktctogradle.write.FileWriter
import okio.FileSystem
import okio.Path

class Converter(private val fileSystem: FileSystem = systemFileSystem) {
    internal fun generateFiles(start: Path): GenerationResult =
        generateBuild(ProjectLoader(fileSystem).load(start))

    fun convert(start: Path, force: Boolean = false, dryRun: Boolean = false): ConversionResult {
        val project = ProjectLoader(fileSystem).load(start)
        val (generated, diagnostics) = generateBuild(project)
        val written = FileWriter(fileSystem).write(project.root, generated, force = force, dryRun = dryRun)
        return ConversionResult(
            root = project.root.toString(),
            writtenFiles = written.map(Path::toString),
            diagnostics = diagnostics,
        )
    }
}

internal fun generateBuild(project: ToolchainProject): GenerationResult {
    val diagnostics = DiagnosticCollector()
    // A failure discards the collector with the stage that owned it, so what was already reported
    // travels on the failure instead: an error recorded for one module must not disappear because a
    // later module stopped the run.
    val gradle = try {
        ProjectInterpreter.interpret(project, diagnostics)
    } catch (failure: ConversionException) {
        throw ConversionException(failure.message.orEmpty(), diagnostics.collected())
    }
    return GenerationResult(renderProject(gradle), diagnostics.collected())
}

private fun renderProject(project: GradleProject): List<GeneratedFile> = buildList {
    add(GeneratedFile(project.root / "settings.gradle.kts", FileContent.Text(renderSettings(project))))
    for (module in project.modules) {
        add(GeneratedFile(module.directory / "build.gradle.kts", FileContent.Text(renderModule(module))))
    }
    add(GeneratedFile(project.root / "gradlew", FileContent.Text(GradleWrapperAssets.unixLauncher())))
    add(GeneratedFile(project.root / "gradlew.bat", FileContent.Text(GradleWrapperAssets.windowsLauncher())))
    val wrapperDirectory = project.root / "gradle" / "wrapper"
    add(GeneratedFile(wrapperDirectory / "gradle-wrapper.properties", FileContent.Text(StaticAssets.wrapperProperties())))
    // A binary cannot carry the ownership marker, so the jar follows the adjacent properties file.
    // This intentionally replaces a manually swapped jar while that companion remains generated;
    // README.md documents the user-visible consequence.
    add(
        GeneratedFile(
            wrapperDirectory / "gradle-wrapper.jar",
            FileContent.Binary(GradleWrapperAssets.wrapperJar(), ownershipFollows = "gradle-wrapper.properties"),
        ),
    )
    add(GeneratedFile(project.root / "gradle.properties", FileContent.Text(StaticAssets.generatedGradleProperties())))
}
