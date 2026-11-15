package heating.api

import heating.ApiException
import heating.HeatingApp
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

@Serializable
data class AndroidAppView(val size: Long, val builtAt: String, val versionCode: Int? = null, val versionName: String? = null)

@Serializable
data class AppsView(val android: AndroidAppView?)

/** The Android app for signed-in people to install from the browser (sideloaded APK). */
fun Route.appRoutes(heating: HeatingApp) = route("/api/app") {
    fun apk(): File? = heating.config.androidApk?.let(::File)?.takeIf { it.isFile }

    get {
        val file = apk()
        call.respond(AppsView(file?.let {
            val version = versionOf(it)
            AndroidAppView(it.length(), Instant.ofEpochMilli(it.lastModified()).toString(), version?.first, version?.second)
        }))
    }
    get("/android") {
        val file = apk() ?: throw ApiException.notFound("the Android app is not available on this server")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "heating.apk").toString(),
        )
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        val size = file.length()
        val range = call.request.headers[HttpHeaders.Range]?.let { byteRange(it, size) }
        when {
            range == null -> call.respondFile(file)
            range.isEmpty() -> {
                call.response.header(HttpHeaders.ContentRange, "bytes */$size")
                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
            }
            // The rest of the APK after an interrupted download, so the phone carries on rather than starting again.
            else -> {
                call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$size")
                call.respond(object : OutgoingContent.WriteChannelContent() {
                    override val status = HttpStatusCode.PartialContent
                    override val contentType = APK
                    override val contentLength = range.last - range.first + 1
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        RandomAccessFile(file, "r").use { f ->
                            f.seek(range.first)
                            val buffer = ByteArray(64 * 1024)
                            var left = contentLength
                            while (left > 0) {
                                val n = f.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                if (n < 0) break
                                channel.writeFully(buffer, 0, n)
                                left -= n
                            }
                        }
                    }
                })
            }
        }
    }
}

/**
 * The one byte range a Range header asks for ("bytes=N-" or "bytes=N-M"), clipped to [size]; empty when it starts past
 * the end; null for anything else (several ranges, suffix ranges, nonsense), which gets the whole file.
 */
private fun byteRange(header: String, size: Long): LongRange? {
    val m = Regex("""bytes=(\d+)-(\d*)""").matchEntire(header.trim()) ?: return null
    val from = m.groupValues[1].toLongOrNull() ?: return null
    if (from >= size) return LongRange.EMPTY
    val to = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
    return if (to < from) null else from..to
}

private val APK = ContentType("application", "vnd.android.package-archive")

/**
 * The APK's version code and name, from the output-metadata.json Gradle writes beside a built APK; null when that file
 * is missing or unreadable, so the app offers no update.
 */
private fun versionOf(apk: File): Pair<Int, String>? = runCatching {
    val meta = File(apk.parentFile, "output-metadata.json").takeIf { it.isFile } ?: return null
    val element = Json.parseToJsonElement(meta.readText()).jsonObject["elements"]!!.jsonArray.first().jsonObject
    element["versionCode"]!!.jsonPrimitive.int to element["versionName"]!!.jsonPrimitive.content
}.getOrNull()
