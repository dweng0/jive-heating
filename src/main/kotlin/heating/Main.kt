package heating

import heating.api.heatingModule
import heating.mqtt.PahoMqttBus
import heating.notify.FcmPushSender
import heating.notify.PushSender
import heating.store.SqliteStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
    val config = Config.fromEnv()
    val http = HttpClient.newHttpClient()
    val heating = HeatingApp(
        config = config,
        bus = PahoMqttBus(config.mqttUrl, username = config.mqttUsername, password = config.mqttPassword),
        store = SqliteStore(config.dbPath),
        clock = Clock.systemUTC(),
        httpGet = { url ->
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build()
            http.send(request, HttpResponse.BodyHandlers.discarding())
        },
        push = config.fcmServiceAccountFile?.let { FcmPushSender.fromJson(File(it).readText()) } ?: PushSender.None,
    )
    heating.start()

    Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
        { runCatching { heating.tick() } },
        5, 5, TimeUnit.SECONDS,
    )

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { heatingModule(heating) }.start(wait = true)
}
