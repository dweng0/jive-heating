package heating.testing

import heating.Config
import heating.HeatingApp
import heating.api.heatingModule
import heating.heatingJson
import heating.mqtt.InMemoryMqttBus
import heating.notify.PushResult
import heating.notify.PushSender
import heating.notify.WebPushHttp
import heating.store.SqliteStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

const val TEST_TOKEN = "test-token"

/** One notification the service sent to a phone. */
data class Pushed(val token: String, val title: String, val body: String)

/** One request the service sent to a browser's push service. */
class WebPushed(val endpoint: String, val headers: Map<String, String>, val body: ByteArray)

/**
 * A whole service for one test: in-memory MQTT, a temp SQLite file and a [MutableClock].
 *
 * Typical use:
 * ```
 * val t = TestHeating()
 * t.discover("Lounge TRV 1")
 * t.api { client -> client.post("/api/rooms") { ... } }
 * ```
 * [restart] builds a fresh [HeatingApp] on the same database, bus and clock.
 */
class TestHeating(
    val clock: MutableClock = MutableClock(Instant.parse("2026-10-12T09:00:00Z")),
    val timezone: ZoneId = ZoneId.of("Europe/London"),
    webDir: String? = null,
    androidApk: String? = null,
    kumaPushUrl: String? = null,
    configure: (Config) -> Config = { it },
) {
    val bus = InMemoryMqttBus()
    val dbPath: String = Files.createTempFile("heating-test", ".db").toString()
    // Few hash iterations: tests sign in a lot, and the cost is not what they check.
    val config = configure(Config(apiToken = TEST_TOKEN, timezone = timezone, dbPath = dbPath, passwordIterations = 1_000, webDir = webDir, androidApk = androidApk, kumaPushUrl = kumaPushUrl))

    /** Notifications sent to phones, in order. Sending is synchronous in tests. */
    val pushed = mutableListOf<Pushed>()

    /** Tokens the fake push service reports as unregistered (the notification still counts as sent to it). */
    val unregistered = mutableSetOf<String>()

    /** When true the fake push service is unreachable. */
    var pushFails = false

    private val push = PushSender { token, title, body ->
        when {
            pushFails -> throw java.io.IOException("push service unreachable")
            else -> {
                pushed += Pushed(token, title, body)
                if (token in unregistered) PushResult.UNREGISTERED else PushResult.SENT
            }
        }
    }

    var app: HeatingApp = build()
        private set

    init {
        app.start()
    }

    fun restart(): HeatingApp {
        app = build()
        app.start()
        return app
    }

    /** URLs the service fetched (Uptime Kuma pushes). */
    val httpSent = mutableListOf<String>()

    /** Requests the service sent to browsers' push services, in order. */
    val webPushed = mutableListOf<WebPushed>()

    /** The fake push services' answers per endpoint; 201 Created when not listed. */
    val webPushStatus = mutableMapOf<String, Int>()

    /** When true every browser push service is unreachable. */
    var webPushFails = false

    private fun webPushPost(url: String, headers: Map<String, String>, body: ByteArray): Int {
        if (webPushFails) throw java.net.http.HttpTimeoutException("push service timed out")
        webPushed += WebPushed(url, headers, body)
        return webPushStatus[url] ?: 201
    }

    private fun build() = HeatingApp(
        config, bus, SqliteStore(dbPath), clock, httpGet = { httpSent += it }, push = push, pushExecutor = { it.run() },
        webPushHttp = WebPushHttp(::webPushPost),
    )

    /**
     * Publish a retained bridge device list containing these TRVZB valves, plus [sensors] (Zigbee
     * smoke alarms that expose temperature), [meters] (electricity clamps that expose energy
     * and power), [others] as name to model (no exposes) and [exposing] as name to the properties
     * it exposes, such as a plug that switches but has no energy counter (`listOf("state", "power")`).
     */
    fun discover(
        vararg valves: String,
        others: Map<String, String> = emptyMap(),
        sensors: List<String> = emptyList(),
        meters: List<String> = emptyList(),
        exposing: Map<String, List<String>> = emptyMap(),
    ) {
        val devices: JsonArray = buildJsonArray {
            valves.forEach { name -> add(device(name, "TRVZB")) }
            sensors.forEach { name -> add(sensorDevice(name)) }
            meters.forEach { name -> add(meterDevice(name)) }
            others.forEach { (name, model) -> add(device(name, model)) }
            exposing.forEach { (name, properties) -> add(exposingDevice(name, properties)) }
        }
        bus.receive("zigbee2mqtt/bridge/devices", devices.toString(), retained = true)
    }

    /** Simulate Zigbee2MQTT publishing valve state, e.g. `valveState("A", """{"local_temperature":19.5}""")`. */
    fun valveState(valve: String, json: String) = bus.receive("zigbee2mqtt/$valve", json)

    /** Simulate a sensor publishing state, e.g. `sensorState("Hall Smoke", """{"temperature":20.9}""")`. */
    fun sensorState(sensor: String, json: String) = bus.receive("zigbee2mqtt/$sensor", json)

    /** Simulate an electricity meter publishing state, e.g. `meterState("Main Clamp", """{"energy":1523.4,"power":412}""")`. */
    fun meterState(meter: String, json: String) = bus.receive("zigbee2mqtt/$meter", json)

    fun availability(valve: String, online: Boolean) =
        bus.receive("zigbee2mqtt/$valve/availability", """{"state":"${if (online) "online" else "offline"}"}""")

    /** Commands the service sent to a valve's `/set` topic, oldest first. */
    fun sentTo(valve: String) = bus.sentTo("zigbee2mqtt/$valve/set")

    /**
     * Run [block] against the HTTP API. `client` sends the correct bearer token;
     * use `anonymous` to send none.
     */
    fun api(block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit) = testApplication {
        application { heatingModule(app) }
        val client = createClient {
            install(ContentNegotiation) { json(heatingJson) }
            defaultRequest { bearerAuth(TEST_TOKEN) }
        }
        block(client)
    }

    private fun device(name: String, model: String) = buildJsonObject {
        put("friendly_name", name)
        put("type", "EndDevice")
        put("definition", buildJsonObject { put("model", JsonPrimitive(model)) })
    }

    /** A smart plug that exposes only [properties]. */
    private fun exposingDevice(name: String, properties: List<String>) = buildJsonObject {
        put("friendly_name", name)
        put("type", "Router")
        put("definition", buildJsonObject {
            put("model", JsonPrimitive("TS011F"))
            put("vendor", JsonPrimitive("Tuya"))
            put("exposes", buildJsonArray {
                properties.forEach { property -> add(buildJsonObject { put("property", property) }) }
            })
        })
    }

    /** An electricity clamp as Zigbee2MQTT lists it: energy (kWh, a running total), power (W), current and voltage. */
    private fun meterDevice(name: String) = buildJsonObject {
        put("friendly_name", name)
        put("type", "Router")
        put("definition", buildJsonObject {
            put("model", JsonPrimitive("PJ-1203A"))
            put("vendor", JsonPrimitive("Tuya"))
            put("exposes", buildJsonArray {
                listOf("energy" to "kWh", "power" to "W", "current" to "A", "voltage" to "V").forEach { (property, unit) ->
                    add(buildJsonObject {
                        put("type", "numeric")
                        put("name", property)
                        put("property", property)
                        put("unit", unit)
                    })
                }
            })
        })
    }

    /** A smoke alarm as Zigbee2MQTT lists it: temperature is exposed alongside the alarm features. */
    private fun sensorDevice(name: String) = buildJsonObject {
        put("friendly_name", name)
        put("type", "EndDevice")
        put("definition", buildJsonObject {
            put("model", JsonPrimitive("SENSOR-1"))
            put("vendor", JsonPrimitive("Acme"))
            put("exposes", buildJsonArray {
                add(buildJsonObject {
                    put("type", "binary")
                    put("property", "smoke")
                })
                add(buildJsonObject {
                    put("type", "numeric")
                    put("name", "temperature")
                    put("property", "temperature")
                    put("unit", "°C")
                })
                add(buildJsonObject {
                    put("type", "numeric")
                    put("property", "battery")
                })
            })
        })
    }
}

val ApplicationTestBuilder.anonymous: HttpClient
    get() = createClient { install(ContentNegotiation) { json(heatingJson) } }
