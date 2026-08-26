package io.heapy.ktctogradle

import okio.FileSystem

internal expect val systemFileSystem: FileSystem

internal expect fun exitWith(code: Int): Nothing
