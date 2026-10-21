package heating.api

import heating.ApiException
import heating.HeatingApp
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

@Serializable
data class AndroidAppView(val size: Long, val builtAt: String)

@Serializable
data class AppsView(val android: AndroidAppView?)

/** The Android app for signed-in people to install from the browser (sideloaded APK). */
fun Route.appRoutes(heating: HeatingApp) = route("/api/app") {
    fun apk(): File? = heating.config.androidApk?.let(::File)?.takeIf { it.isFile }

    get {
        val file = apk()
        call.respond(AppsView(file?.let { AndroidAppView(it.length(), Instant.ofEpochMilli(it.lastModified()).toString()) }))
    }
    get("/android") {
        val file = apk() ?: throw ApiException.notFound("the Android app is not available on this server")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "heating.apk").toString(),
        )
        call.response.header(HttpHeaders.ContentType, APK.toString())
        call.respondFile(file)
    }
}

private val APK = ContentType("application", "vnd.android.package-archive")
