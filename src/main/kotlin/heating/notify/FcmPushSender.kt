package heating.notify

import heating.heatingJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** The parts of a Firebase service-account key file the sender needs. */
@Serializable
data class ServiceAccount(
    val project_id: String,
    val client_email: String,
    val private_key: String,
    val token_uri: String = "https://oauth2.googleapis.com/token",
)

/** An HTTP POST: url, headers, body to status and response body. */
fun interface HttpPost {
    fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String>
}

/**
 * Sends through Firebase Cloud Messaging (HTTP v1), signing in with a service account: a signed JWT
 * is swapped for an access token, kept until shortly before it expires.
 */
class FcmPushSender(
    private val account: ServiceAccount,
    private val http: HttpPost = javaHttp(),
    private val now: () -> Instant = Instant::now,
) : PushSender {
    private var accessToken: String? = null
    private var expires: Instant = Instant.EPOCH

    override fun send(token: String, title: String, body: String): PushResult = try {
        val message = buildJsonObject {
            put("message", buildJsonObject {
                put("token", token)
                put("notification", buildJsonObject { put("title", title); put("body", body) })
                put("android", buildJsonObject {
                    put("priority", "high")
                    put("notification", buildJsonObject { put("channel_id", CHANNEL) })
                })
            })
        }
        val (status, response) = http.post(
            "https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send",
            mapOf("Authorization" to "Bearer ${accessToken()}", "Content-Type" to "application/json"),
            message.toString(),
        )
        when {
            status in 200..299 -> PushResult.SENT
            status == 404 || "UNREGISTERED" in response || (status == 400 && "registration token" in response) -> PushResult.UNREGISTERED
            else -> {
                log.warn("FCM refused a message: {} {}", status, response.take(300))
                PushResult.FAILED
            }
        }
    } catch (e: Exception) {
        log.warn("FCM unreachable: {}", e.message)
        PushResult.FAILED
    }

    @Synchronized
    private fun accessToken(): String {
        accessToken?.takeIf { now() < expires }?.let { return it }
        val (status, response) = http.post(
            account.token_uri,
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) + "&assertion=" + jwt(),
        )
        check(status in 200..299) { "token exchange failed: $status ${response.take(200)}" }
        val json = heatingJson.parseToJsonElement(response).jsonObject
        val seconds = json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
        expires = now().plusSeconds(seconds).minus(Duration.ofMinutes(5))
        return json.getValue("access_token").jsonPrimitive.content.also { accessToken = it }
    }

    /** A JWT asserting the service account, for the messaging scope, valid for an hour. */
    internal fun jwt(): String {
        val issued = now().epochSecond
        val header = b64("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64(
            buildJsonObject {
                put("iss", account.client_email)
                put("scope", SCOPE)
                put("aud", account.token_uri)
                put("iat", JsonPrimitive(issued))
                put("exp", JsonPrimitive(issued + 3600))
            }.toString().toByteArray(),
        )
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey())
        signer.update("$header.$claims".toByteArray())
        return "$header.$claims.${b64(signer.sign())}"
    }

    private fun privateKey() = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                account.private_key.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""),
            ),
        ),
    )

    companion object {
        /** The Android app's notification channel. */
        const val CHANNEL = "heating"
        private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        private val log = LoggerFactory.getLogger(FcmPushSender::class.java)

        private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun fromJson(json: String) = FcmPushSender(heatingJson.decodeFromString(ServiceAccount.serializer(), json))

        private fun javaHttp(): HttpPost {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            return HttpPost { url, headers, body ->
                val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                client.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
            }
        }
    }
}
