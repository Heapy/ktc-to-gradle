package example.mixed.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Message(val text: String)

expect val platformName: String

fun encode(message: Message): String = Json.encodeToString(message)

fun decode(text: String): Message = Json.decodeFromString(text)
