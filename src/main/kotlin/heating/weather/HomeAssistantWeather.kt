package heating.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Weather from a Home Assistant weather entity (GET /api/states/<entity> with a long-lived access token). Home
 * Assistant's conditions are mapped onto [Condition]; one it has no mapping for keeps the last known condition.
 */
class HomeAssistantWeather(
    private val baseUrl: String,
    private val token: String,
    private val entity: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : WeatherSource {
    @Volatile private var last: Condition? = null

    override fun current(): WeatherReading? {
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/api/states/" + entity))
            .header("Authorization", "Bearer $token")
            .timeout(Duration.ofSeconds(15))
            .GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw java.io.IOException("Home Assistant answered ${response.statusCode()} for $entity")
        val body = Json.parseToJsonElement(response.body()).jsonObject
        val condition = CONDITIONS[body["state"]?.jsonPrimitive?.content] ?: last ?: return null
        last = condition
        val attributes = body["attributes"]?.jsonObject
        return WeatherReading(
            condition = condition,
            cloudCover = attributes?.get("cloud_coverage")?.jsonPrimitive?.intOrNull,
            temperatureC = attributes?.get("temperature")?.jsonPrimitive?.doubleOrNull,
            observedAt = body["last_updated"]?.jsonPrimitive?.content?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() },
        )
    }

    companion object {
        private val CONDITIONS = mapOf(
            "sunny" to Condition.CLEAR, "clear-night" to Condition.CLEAR,
            "partlycloudy" to Condition.PARTLY_CLOUDY,
            "cloudy" to Condition.CLOUDY, "fog" to Condition.CLOUDY,
            "rainy" to Condition.RAIN, "pouring" to Condition.RAIN, "lightning-rainy" to Condition.RAIN, "hail" to Condition.RAIN,
            "snowy" to Condition.SNOW, "snowy-rainy" to Condition.SNOW,
        )
    }
}
