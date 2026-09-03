package example.mixed.core

import okio.Buffer
import org.jetbrains.annotations.NotNull

fun store(@NotNull text: String): Buffer = Buffer().also { it.writeUtf8(text) }
