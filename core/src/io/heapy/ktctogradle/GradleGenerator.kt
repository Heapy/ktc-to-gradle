package io.heapy.ktctogradle

/**
 * What is left of the generator: an entry point [GradleGeneratorTest] still drives.
 *
 * Every job this class used to do now belongs to a stage of the pipeline [generateBuild] runs. The
 * class and its last test go away together.
 */
internal class GradleGenerator {
    fun generate(project: ToolchainProject): GenerationResult = generateBuild(project)

    companion object {
        @Suppress("unused")
        private val qualifiedKotlinOptionKeys = setOf(
            "languageVersion", "apiVersion", "allWarningsAsErrors", "progressiveMode", "freeCompilerArgs", "optIns",
        )
    }
}
