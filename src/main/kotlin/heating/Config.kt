package heating

import heating.auth.Passwords
import java.time.Duration
import java.time.ZoneId

/** Service configuration. Every value comes from the environment; nothing is hard-coded. */
data class Config(
    val apiToken: String,
    val mqttUrl: String = "tcp://localhost:1883",
    val z2mBaseTopic: String = "zigbee2mqtt",
    val timezone: ZoneId = ZoneId.of("Europe/London"),
    val dbPath: String = "heating.db",
    val port: Int = 8080,
    val kumaPushUrl: String? = null,
    val passwordIterations: Int = Passwords.PRODUCTION_ITERATIONS,
    /** Built web app to serve at /, or null for API only. */
    val webDir: String? = null,
    /** Android app (APK) offered for download to signed-in people, or null for none. */
    val androidApk: String? = null,
    /** Once the boiler fires it stays on at least this long, so it never short-cycles. */
    val boilerMinRun: Duration = Duration.ofMinutes(4),
    /** Once the boiler stops it stays off at least this long. */
    val boilerMinRest: Duration = Duration.ofMinutes(5),
    /** Firebase service-account key file for phone notifications; none means no notifications. */
    val fcmServiceAccountFile: String? = null,
    /** mailto: or https: address push services can reach the owner at; none means no Web Push to browsers. */
    val webPushContact: String? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val token = env["HEATING_API_TOKEN"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("HEATING_API_TOKEN must be set")
            return Config(
                apiToken = token,
                mqttUrl = env["MQTT_URL"] ?: "tcp://localhost:1883",
                z2mBaseTopic = env["Z2M_BASE_TOPIC"] ?: "zigbee2mqtt",
                timezone = ZoneId.of(env["HEATING_TIMEZONE"] ?: "Europe/London"),
                dbPath = env["HEATING_DB_PATH"] ?: "heating.db",
                port = env["PORT"]?.toInt() ?: 8080,
                kumaPushUrl = env["KUMA_PUSH_URL"]?.takeIf { it.isNotBlank() },
                webDir = env["WEB_DIR"]?.takeIf { it.isNotBlank() },
                androidApk = env["ANDROID_APK"]?.takeIf { it.isNotBlank() },
                boilerMinRun = Duration.ofMinutes(env["BOILER_MIN_RUN_MINUTES"]?.toLong() ?: 4),
                boilerMinRest = Duration.ofMinutes(env["BOILER_MIN_REST_MINUTES"]?.toLong() ?: 5),
                fcmServiceAccountFile = env["FCM_SERVICE_ACCOUNT_FILE"]?.takeIf { it.isNotBlank() },
                webPushContact = env["WEBPUSH_CONTACT"]?.takeIf { it.isNotBlank() }?.also {
                    check(it.startsWith("mailto:") || it.startsWith("https://")) { "WEBPUSH_CONTACT must be a mailto: or https: address" }
                },
            )
        }
    }
}
