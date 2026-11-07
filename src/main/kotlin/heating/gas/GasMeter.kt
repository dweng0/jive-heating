package heating.gas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The house's smart gas meter, read half-hourly. Each half-hour is keyed by the instant it starts. */
interface GasMeter {
    /** The starts of the first and last half-hours the meter has reported; null while it has reported none. */
    fun reported(): ClosedRange<Instant>?

    /** kWh used in each reported half-hour starting from [from] to [to], both included. At most ten days at once. */
    fun readings(from: Instant, to: Instant): Map<Instant, Double>
}

/**
 * The gas meter as the Bright app (Hildebrand) sees it, through the Glowmarkt API. Signs in with the Bright account
 * and keeps the token until it runs out or is refused.
 */
class GlowmarktGasMeter(
    private val username: String,
    private val password: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val base: String = "https://api.glowmarkt.com/api/v0-1",
    private val now: () -> Instant = Instant::now,
) : GasMeter {
    private var token: String? = null
    private var tokenExpires: Instant = Instant.EPOCH
    private var resource: String? = null

    @Synchronized
    override fun reported(): ClosedRange<Instant>? {
        val id = resource() ?: return null
        val first = get("/resource/$id/first-time")["data"]?.jsonObject?.get("firstTs")?.jsonPrimitive?.longOrNull
        val last = get("/resource/$id/last-time")["data"]?.jsonObject?.get("lastTs")?.jsonPrimitive?.longOrNull
        if (first == null || last == null || last < first) return null
        return Instant.ofEpochSecond(first)..Instant.ofEpochSecond(last)
    }

    @Synchronized
    override fun readings(from: Instant, to: Instant): Map<Instant, Double> {
        val id = resource() ?: return emptyMap()
        // offset=0: times are UTC both ways. "to" is the last second of the last half-hour wanted.
        val path = "/resource/$id/readings?period=PT30M&function=sum&offset=0" +
            "&from=${UTC.format(from)}&to=${UTC.format(to.plus(HALF_HOUR).minusSeconds(1))}"
        return get(path)["data"]!!.jsonArray.mapNotNull { row ->
            val pair = row.jsonArray
            val at = Instant.ofEpochSecond(pair[0].jsonPrimitive.long)
            val kwh = pair[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            if (at < from || at > to) null else at to kwh
        }.toMap()
    }

    /** The meter's gas.consumption resource, once the account's smart meter has been linked. */
    private fun resource(): String? {
        resource?.let { return it }
        val entities = getArray("/virtualentity")
        for (entity in entities) {
            val veId = entity.jsonObject["veId"]!!.jsonPrimitive.content
            val resources = get("/virtualentity/$veId/resources")["resources"]?.jsonArray ?: continue
            val gas = resources.map { it.jsonObject }.firstOrNull { it["classifier"]?.jsonPrimitive?.content == "gas.consumption" }
            if (gas != null) return gas["resourceId"]!!.jsonPrimitive.content.also { resource = it }
        }
        return null
    }

    private fun get(path: String): JsonObject = json.parseToJsonElement(send(path)).jsonObject

    private fun getArray(path: String) = json.parseToJsonElement(send(path)).jsonArray

    private fun send(path: String, retried: Boolean = false): String {
        val request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
            .header("applicationId", APPLICATION_ID).header("token", token()).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 401 && !retried) {
            token = null
            return send(path, retried = true)
        }
        if (response.statusCode() != 200) throw IOException("Glowmarkt ${response.statusCode()} for ${path.substringBefore('?')}")
        return response.body()
    }

    private fun token(): String {
        token?.takeIf { now() < tokenExpires }?.let { return it }
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
        }.toString()
        val request = HttpRequest.newBuilder(URI.create("$base/auth")).timeout(Duration.ofSeconds(30))
            .header("applicationId", APPLICATION_ID).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        // Never put the response in the message: it may echo the account.
        if (response.statusCode() != 200) throw IOException("Glowmarkt sign-in refused (${response.statusCode()})")
        val auth = json.parseToJsonElement(response.body()).jsonObject
        val signedIn = auth["token"]?.jsonPrimitive?.content ?: throw IOException("Glowmarkt sign-in gave no token")
        // Renew a day before it runs out; if it gives no expiry, renew daily.
        val exp = auth["exp"]?.jsonPrimitive?.longOrNull?.let(Instant::ofEpochSecond) ?: now().plus(Duration.ofDays(2))
        token = signedIn
        tokenExpires = exp.minus(Duration.ofDays(1))
        return signedIn
    }

    private companion object {
        /** The Bright app's application id, which Glowmarkt asks every request to carry. */
        const val APPLICATION_ID = "b0f1b774-a586-4f72-9edd-27ead8aa7a8d"
        val HALF_HOUR: Duration = Duration.ofMinutes(30)
        val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
        val json = Json { ignoreUnknownKeys = true }
    }
}
