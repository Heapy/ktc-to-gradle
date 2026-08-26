package example.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Payload(val message: String)

val encodedPayload: String = Json.encodeToString(Payload("converted"))
