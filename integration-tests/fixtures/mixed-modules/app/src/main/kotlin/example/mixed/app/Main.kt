package example.mixed.app

import example.mixed.core.store
import example.mixed.shared.Message
import example.mixed.shared.encode
import okio.Buffer

fun report(): String = encode(Message(store("mixed").readUtf8()))

fun buffered(): Buffer = store(report())

fun main() {
    println(report())
}
