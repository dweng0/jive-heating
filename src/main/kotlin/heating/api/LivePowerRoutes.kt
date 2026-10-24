package heating.api

import heating.Change
import heating.HeatingApp
import heating.heatingJson
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.Channel

/**
 * The house's power, streamed to the open Dashboard on its own channel: readings arrive every few seconds,
 * which /ws (change events for everyone) should not carry. Sends the latest power on connect.
 */
fun Route.livePowerRoutes(heating: HeatingApp) {
    get("/api/power") { call.respond(heating.livePower.summary()) }

    webSocket("/ws/power") {
        // Latest power wins: a slow client skips readings instead of falling behind.
        val queue = Channel<String>(Channel.CONFLATED)
        val unsubscribe = heating.changes.subscribe { change ->
            if (change is Change.LivePowerChanged) {
                runCatching { queue.trySend(heatingJson.encodeToString(heating.livePower.now())) }
            }
        }
        try {
            outgoing.send(Frame.Text(heatingJson.encodeToString(heating.livePower.now())))
            for (msg in queue) outgoing.send(Frame.Text(msg))
        } finally {
            unsubscribe()
            queue.close()
        }
    }
}
