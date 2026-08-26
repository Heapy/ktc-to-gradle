package io.heapy.ktctogradle

import okio.Path

internal actual fun makeExecutable(path: Path) {
    // Windows has no executable bit; gradlew.bat runs without one.
}
