package io.heapy.ktctogradle

import okio.FileSystem
import kotlin.system.exitProcess

internal actual val systemFileSystem: FileSystem
    get() = FileSystem.SYSTEM

internal actual fun exitWith(code: Int): Nothing = exitProcess(code)
