package heating.notify

import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** NIST P-256 (secp256r1), the curve Web Push uses for VAPID and message encryption, with the JDK's own crypto. */
object P256 {
    val params: ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)

    /** The 65-byte uncompressed form (0x04 || X || Y) browsers and push services exchange. */
    fun uncompressed(key: ECPublicKey): ByteArray =
        byteArrayOf(4) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

    /** The public key for a 65-byte uncompressed point, or null if [bytes] is not one on this curve. */
    fun publicKey(bytes: ByteArray): ECPublicKey? {
        if (bytes.size != 65 || bytes[0] != 4.toByte()) return null
        val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
        if (!onCurve(point)) return null
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    }

    private fun onCurve(point: ECPoint): Boolean {
        val curve = params.curve
        val p = (curve.field as java.security.spec.ECFieldFp).p
        val x = point.affineX
        val y = point.affineY
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        return y.modPow(BigInteger.TWO, p) == x.modPow(BigInteger.valueOf(3), p).add(curve.a.multiply(x)).add(curve.b).mod(p)
    }

    private fun fixed32(n: BigInteger): ByteArray {
        val bytes = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }
}

internal val base64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

@Serializable
private data class VapidDocument(
    /** Uncompressed public point, base64url. */
    val publicKey: String,
    /** PKCS#8 private key, base64url. */
    val privateKey: String,
)

/**
 * The service's VAPID key pair (RFC 8292): made on first start and kept in the store, so
 * browser subscriptions made against the public key keep working after restarts.
 */
class VapidKeys private constructor(val publicKey: ECPublicKey, val privateKey: ECPrivateKey) {

    /** The public key as browsers pass it to `pushManager.subscribe` (applicationServerKey). */
    val publicKeyBase64Url: String get() = base64Url.encodeToString(P256.uncompressed(publicKey))

    companion object {
        private const val KEY = "webpush-vapid"

        fun loadOrCreate(store: Store): VapidKeys {
            store.get(KEY)?.let { stored ->
                val doc = heatingJson.decodeFromString(VapidDocument.serializer(), stored)
                val decoder = Base64.getUrlDecoder()
                val public = P256.publicKey(decoder.decode(doc.publicKey)) ?: error("stored VAPID public key is not a P-256 point")
                val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(doc.privateKey))) as ECPrivateKey
                return VapidKeys(public, private)
            }
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val keys = VapidKeys(pair.public as ECPublicKey, pair.private as ECPrivateKey)
            val doc = VapidDocument(keys.publicKeyBase64Url, base64Url.encodeToString(keys.privateKey.encoded))
            store.put(KEY, heatingJson.encodeToString(VapidDocument.serializer(), doc))
            return keys
        }
    }
}
