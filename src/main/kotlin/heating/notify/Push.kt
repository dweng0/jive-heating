package heating.notify

/** What the push service said about one message. */
enum class PushResult { SENT, UNREGISTERED, FAILED }

/** Sends one notification to one phone (a Firebase registration token). Must not throw. */
fun interface PushSender {
    fun send(token: String, title: String, body: String): PushResult

    companion object {
        /** No push service configured: nothing is sent. */
        val None = PushSender { _, _, _ -> PushResult.FAILED }
    }
}
