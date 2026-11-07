package heating.notify

/** Delivers to a person's phones (Firebase) and browsers (Web Push), forgetting any the push service no longer knows. */
class PushTransport(
    private val settings: NotificationSettings,
    private val sender: PushSender,
    private val webPush: WebPush?,
) : DeliveryTransport {
    override fun devices(userId: String): List<DeliveryDevice> {
        val phones = if (sender === PushSender.None) emptyList() else settings.devicesOf(userId).map { DeliveryDevice("phone:$it", "phone …${it.takeLast(6)}") }
        val browsers = webPush?.subscriptionsFor(userId).orEmpty().map { DeliveryDevice("browser:${it.endpoint}", "browser …${it.endpoint.takeLast(6)}") }
        return phones + browsers
    }

    override fun send(userId: String, device: DeliveryDevice, message: WebPushMessage): PushResult {
        if (device.key.startsWith("phone:")) {
            val token = device.key.removePrefix("phone:")
            val result = runCatching { sender.send(token, message.title, message.body) }.getOrDefault(PushResult.FAILED)
            if (result == PushResult.UNREGISTERED) settings.forget(token)
            return result
        }
        val endpoint = device.key.removePrefix("browser:")
        val subscription = webPush?.subscriptionsFor(userId)?.firstOrNull { it.endpoint == endpoint } ?: return PushResult.UNREGISTERED
        return runCatching { webPush!!.deliver(subscription, message) }.getOrDefault(PushResult.FAILED)
    }
}
