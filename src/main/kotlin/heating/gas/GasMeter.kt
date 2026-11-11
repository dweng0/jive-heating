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

    /** The account's current gas and electricity prices; null when it has none to give. */
    fun tariff(): BrightTariff? = null
}

/** Prices in pence (including VAT) as the Bright account's tariff gives them. Electricity is null when it has no electricity tariff. */
data class BrightTariff(
    val gasPencePerKwh: Double,
    val gasStandingPencePerDay: Double,
    val electricityPencePerKwh: Double?,
    val electricityStandingPencePerDay: Double?,
)

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

    @Synchronized
    override fun tariff(): BrightTariff? {
        val gas = resource()?.let(::rates) ?: return null
        val electricity = resource("electricity.consumption")?.let { runCatching { rates(it) }.getOrNull() }
        return BrightTariff(gas.first, gas.second, electricity?.first, electricity?.second)
    }

    /** A resource's current unit rate and standing charge in pence, from its tariff. */
    private fun rates(id: String): Pair<Double, Double>? {
        val data = get("/resource/$id/tariff")["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        data["currentRates"]?.jsonObject?.let { current ->
            val rate = current["rate"]?.jsonPrimitive?.doubleOrNull
            val standing = current["standingCharge"]?.jsonPrimitive?.doubleOrNull
            if (rate != null && standing != null) return rate to standing
        }
        // The older shape: a list of plan details, one with "standing" and one with "rate".
        val details = data["structure"]?.jsonArray?.firstOrNull()?.jsonObject?.get("planDetail")?.jsonArray?.map { it.jsonObject } ?: return null
        val rate = details.firstNotNullOfOrNull { it["rate"]?.jsonPrimitive?.doubleOrNull } ?: return null
        val standing = details.firstNotNullOfOrNull { it["standing"]?.jsonPrimitive?.doubleOrNull } ?: return null
        return rate to standing
    }

    /** The meter's gas.consumption resource, once the account's smart meter has been linked. */
    private fun resource(): String? = resource ?: resource("gas.consumption")?.also { resource = it }

    /** The id of the account's resource with [classifier], such as "electricity.consumption"; null without one. */
    private fun resource(classifier: String): String? {
        val entities = getArray("/virtualentity")
        for (entity in entities) {
            val veId = entity.jsonObject["veId"]!!.jsonPrimitive.content
            val resources = get("/virtualentity/$veId/resources")["resources"]?.jsonArray ?: continue
            val found = resources.map { it.jsonObject }.firstOrNull { it["classifier"]?.jsonPrimitive?.content == classifier }
            if (found != null) return found["resourceId"]!!.jsonPrimitive.content
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
