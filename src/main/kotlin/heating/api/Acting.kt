package heating.api

import heating.Actor
import heating.auth.Caller
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal

/**
 * Run [block] as the signed-in caller, so the action log can say "by jane" (or "by service" for the
 * service token). [block] must not suspend: [Actor] is per thread.
 */
fun <T> ApplicationCall.acting(block: () -> T): T =
    Actor.runAs(principal<Caller>()?.username ?: "unknown", block)
