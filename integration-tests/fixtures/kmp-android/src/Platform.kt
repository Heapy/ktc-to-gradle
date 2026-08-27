package io.heapy.ktctogradle.fixture

expect fun platformName(): String

fun greeting(): String = "hello from " + platformName()
