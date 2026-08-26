package example.multiplatform

expect fun platformName(): String

fun platformGreeting(name: String): String = "Hello from ${platformName()}, $name"
