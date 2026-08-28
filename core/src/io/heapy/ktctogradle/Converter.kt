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
     * The golden-snapshot suite drives the converter through this, so that a case is converted the
     * way a real run converts it — through the same [fileSystem] — and only the write stage is left
     * out.
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
 * Kept apart from [Converter] because neither stage may touch a file system: this function is
 * handed no `FileSystem`, so a renderer that wanted one would have to reach for the platform
 * default by hand instead of using what it was passed.
 */
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

/**
 * Every file the build consists of, in the order a conversion reports them.
 *
 * Settings first, then one script per module in project order, then the wrapper and its
 * properties. A caller diffing two runs reads the list top to bottom, and the golden baselines
 * compare it as written.
 */
private fun renderProject(project: GradleProject): List<GeneratedFile> = buildList {
    add(GeneratedFile(project.root / "settings.gradle.kts", FileContent.Text(renderSettings(project))))
    for (module in project.modules) {
        add(GeneratedFile(module.directory / "build.gradle.kts", FileContent.Text(renderModule(module))))
    }
    add(GeneratedFile(project.root / "gradlew", FileContent.Text(GradleWrapperAssets.unixLauncher())))
    add(GeneratedFile(project.root / "gradlew.bat", FileContent.Text(GradleWrapperAssets.windowsLauncher())))
    val wrapperProperties = project.root / "gradle" / "wrapper" / "gradle-wrapper.properties"
    add(GeneratedFile(wrapperProperties, FileContent.Text(StaticAssets.wrapperProperties())))
    // The jar is the wrapper: the two scripts do nothing but run it. It carries no ownership
    // marker of its own, so it points at the properties file beside it instead.
    add(
        GeneratedFile(
            project.root / "gradle" / "wrapper" / "gradle-wrapper.jar",
            FileContent.Binary(GradleWrapperAssets.wrapperJar(), ownershipFollows = wrapperProperties),
        ),
    )
    add(GeneratedFile(project.root / "gradle.properties", FileContent.Text(StaticAssets.generatedGradleProperties())))
}
