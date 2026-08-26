package io.heapy.ktctogradle

import okio.Path
import java.io.File

internal actual fun makeExecutable(path: Path) {
    File(path.toString()).setExecutable(true, false)
}

