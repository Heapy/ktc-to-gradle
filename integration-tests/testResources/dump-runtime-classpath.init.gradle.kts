import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

val runtimeClasspathConfigurations = setOf(
    "runtimeClasspath",
    "testRuntimeClasspath",
    "jvmRuntimeClasspath",
    "jvmTestRuntimeClasspath",
)

allprojects {
    // Configurations only exist once the generated build script has been evaluated.
    afterEvaluate {
        val roots = configurations
            .matching { it.isCanBeResolved && it.name in runtimeClasspathConfigurations }
            .associate { it.name to it.incoming.resolutionResult.rootComponent }
        if (roots.isEmpty()) return@afterEvaluate
        val directory = layout.buildDirectory.dir("ktc-to-gradle-classpath")
        tasks.register("dumpRuntimeClasspath") {
            val capturedRoots = roots
            val capturedDirectory = directory
            outputs.upToDateWhen { false }
            doLast {
                val target = capturedDirectory.get().asFile
                target.mkdirs()
                capturedRoots.forEach { (name, root) ->
                    val coordinates = sortedSetOf<String>()
                    fun walk(component: ResolvedComponentResult) {
                        component.dependencies
                            .filterIsInstance<ResolvedDependencyResult>()
                            .forEach { if (coordinates.add(it.selected.id.displayName)) walk(it.selected) }
                    }
                    walk(root.get())
                    target.resolve("$name.txt").writeText(coordinates.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }
}
