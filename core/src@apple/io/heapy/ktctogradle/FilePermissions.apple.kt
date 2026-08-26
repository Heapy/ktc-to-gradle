@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.ktctogradle

import kotlinx.cinterop.convert
import okio.Path
import platform.posix.chmod

internal actual fun makeExecutable(path: Path) {
    chmod(path.toString(), 493.convert())
}
