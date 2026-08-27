package io.heapy.ktctogradle.render

import kotlin.test.Test
import kotlin.test.assertEquals

class KtsWriterTest {
    @Test
    fun flatLinesAreEmittedWithoutIndentation() {
        val text = KtsWriter().apply {
            line("rootProject.name = \"demo\"")
            line("include(\":app\")")
        }.build()
        assertEquals("rootProject.name = \"demo\"\ninclude(\":app\")\n", text)
    }

    @Test
    fun oneNestedBlockIndentsItsBodyByFourSpaces() {
        val text = KtsWriter().apply {
            block("plugins") {
                line("kotlin(\"jvm\")")
            }
        }.build()
        assertEquals(
            """
            plugins {
                kotlin("jvm")
            }

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun twoNestedBlocksIndentCumulatively() {
        val text = KtsWriter().apply {
            block("pluginManagement") {
                block("repositories") {
                    line("mavenCentral()")
                }
            }
        }.build()
        assertEquals(
            """
            pluginManagement {
                repositories {
                    mavenCentral()
                }
            }

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun threeNestedBlocksMatchTheMultiplatformSourceSetShape() {
        val text = KtsWriter().apply {
            block("kotlin") {
                block("sourceSets") {
                    block("maybeCreate(\"jvmMain\").apply") {
                        line("dependsOn(getByName(\"commonMain\"))")
                        block("dependencies") {
                            line("implementation(\"com.squareup.okio:okio:3.17.0\")")
                        }
                    }
                }
            }
        }.build()
        assertEquals(
            """
            kotlin {
                sourceSets {
                    maybeCreate("jvmMain").apply {
                        dependsOn(getByName("commonMain"))
                        dependencies {
                            implementation("com.squareup.okio:okio:3.17.0")
                        }
                    }
                }
            }

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun blankLinesCarryNoIndentationAtAnyDepth() {
        val text = KtsWriter().apply {
            blank()
            block("kotlin") {
                line("jvmToolchain(25)")
                blank()
                line("explicitApi()")
            }
        }.build()
        assertEquals("\nkotlin {\n    jvmToolchain(25)\n\n    explicitApi()\n}\n", text)
    }

    @Test
    fun indentationReturnsToTheOuterLevelAfterABlock() {
        val text = KtsWriter().apply {
            block("java") {
                line("inside()")
            }
            line("outside()")
        }.build()
        assertEquals("java {\n    inside()\n}\noutside()\n", text)
    }

    @Test
    fun quoteEscapesBackslashesDoubleQuotesAndDollars() {
        assertEquals("\"C:\\\\tools\"", quote("C:\\tools"))
        assertEquals("\"say \\\"hi\\\"\"", quote("say \"hi\""))
        assertEquals("\"\\\$libs.okio\"", quote("\$libs.okio"))
        assertEquals("\"\\\\\\\$\\\"\"", quote("\\\$\""))
    }
}
