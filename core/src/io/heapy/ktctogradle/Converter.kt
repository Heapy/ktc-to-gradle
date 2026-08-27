package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.ProjectInterpreter
import io.heapy.ktctogradle.load.ProjectLoader
import io.heapy.ktctogradle.load.ToolchainProject
import io.heapy.ktctogradle.model.GeneratedFile
import io.heapy.ktctogradle.model.GradleProject
import io.heapy.ktctogradle.render.StaticAssets
import io.heapy.ktctogradle.render.renderModule
import io.heapy.ktctogradle.render.renderSettings
import io.heapy.ktctogradle.write.FileWriter
import okio.FileSystem
import okio.Path

/**
 * Runs the conversion as four stages: load, interpret, render, write.
 *
 * The file system reaches only the first and the last of them; interpreting and rendering are pure
 * functions of what the load stage recorded.
 */
class Converter(private val fileSystem: FileSystem = systemFileSystem) {
    /**
     * Renders the whole build without touching the destination tree.
     *
     * The signature is fixed: the golden-snapshot suite drives the converter through it, so it must
     * stay a member of [Converter] (the file system is private) and keep this shape.
     */
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

/**
 * Stages 2 and 3: interprets a loaded project and renders every file of the Gradle build.
 *
 * Kept apart from [Converter] because neither stage may touch a file system, and a top-level
 * function is the shape that makes that impossible rather than merely true.
 */
internal fun generateBuild(project: ToolchainProject): GenerationResult {
    val diagnostics = DiagnosticCollector()
    val gradle = ProjectInterpreter.interpret(project, diagnostics)
    return GenerationResult(renderProject(gradle), diagnostics.drain())
}

/**
 * Every file the build consists of, in the order a conversion reports them.
 *
 * The module scripts come before the wrapper because that is the order the written-file list has
 * always had, and a caller diffing two runs reads it top to bottom.
 */
private fun renderProject(project: GradleProject): List<GeneratedFile> = buildList {
    add(GeneratedFile(project.root / "settings.gradle.kts", renderSettings(project)))
    for (module in project.modules) {
        add(GeneratedFile(module.directory / "build.gradle.kts", renderModule(module)))
    }
    add(GeneratedFile(project.root / "gradlew", StaticAssets.unixGradleLauncher()))
    add(GeneratedFile(project.root / "gradlew.bat", StaticAssets.windowsGradleLauncher()))
    add(
        GeneratedFile(
            project.root / "gradle" / "wrapper" / "gradle-wrapper.properties",
            StaticAssets.wrapperProperties(),
        ),
    )
    add(GeneratedFile(project.root / "gradle.properties", StaticAssets.generatedGradleProperties()))
}
