package io.heapy.ktctogradle

import okio.Path.Companion.toPath

fun runCli(args: Array<String>) {
    val options = try {
        Options.parse(args)
    } catch (error: ConversionException) {
        println("ktc-to-gradle: ${error.message}")
        println("Try 'ktc-to-gradle --help'.")
        exitWith(2)
    }
    if (options.help) {
        println(helpText())
        return
    }
    if (options.version) {
        println("ktc-to-gradle ${Versions.CONVERTER} (Gradle ${Versions.GRADLE})")
        return
    }
    try {
        val result = Converter().convert(options.path.toPath(), options.force, options.dryRun)
        val action = if (options.dryRun) "Would generate" else "Generated"
        println("$action ${result.writtenFiles.size} file(s) in ${result.root}")
        result.writtenFiles.forEach { println("  $it") }
        result.diagnostics.forEach { diagnostic ->
            println("${diagnostic.severity.name.lowercase()}: ${diagnostic.message}")
        }
        if (result.writtenFiles.isEmpty()) println("Gradle files are already up to date.")
        // A run that dropped part of the project still writes what it could, and must not report
        // that as a success: the exit code is the only thing a script reads.
        if (result.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) exitWith(1)
    } catch (error: ConversionException) {
        error.diagnostics.forEach { diagnostic ->
            println("${diagnostic.severity.name.lowercase()}: ${diagnostic.message}")
        }
        println("ktc-to-gradle: ${error.message}")
        exitWith(1)
    } catch (error: Exception) {
        println("ktc-to-gradle: unexpected failure: ${error.message}")
        exitWith(1)
    }
}

private data class Options(
    val path: String,
    val force: Boolean,
    val dryRun: Boolean,
    val help: Boolean,
    val version: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var path = "."
            var force = false
            var dryRun = false
            var help = false
            var version = false
            var pathSeen = false
            for (argument in args) {
                when (argument) {
                    "convert" -> Unit
                    "--force", "-f" -> force = true
                    "--dry-run" -> dryRun = true
                    "--help", "-h" -> help = true
                    "--version", "-V" -> version = true
                    else -> {
                        if (argument.startsWith("-")) throw ConversionException("Unknown option '$argument'")
                        if (pathSeen) throw ConversionException("Only one project path may be specified")
                        path = argument
                        pathSeen = true
                    }
                }
            }
            return Options(path, force, dryRun, help, version)
        }
    }
}

private fun helpText(): String = """
    Convert a Kotlin Toolchain 0.12 project to Gradle Kotlin DSL.

    Usage: ktc-to-gradle [convert] [project-directory] [options]

      -f, --force    overwrite existing, non-generated Gradle files
          --dry-run  validate and show files without writing them
      -V, --version  print converter and pinned Gradle versions
      -h, --help     show this help

    The generated gradlew and gradlew.bat bootstrap Gradle ${Versions.GRADLE} automatically.
""".trimIndent()
