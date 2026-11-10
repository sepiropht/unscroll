package app.unscroll.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

internal data class HttpReply(
    val status: Int,
    val body: String,
    val raw: ByteArray,
    val etag: String?,
)

/**
 * GET ou PUT vers un blob. Une redirection n'est pas suivie : le jeton ne quitte pas cette origine.
 * `ifMatch` est l'etag nu (`sha256-…`) ; il est envoyé entre guillemets.
 */
internal class SyncHttp {
    /** `POST /v1/accounts` : pas de jeton, pas de corps, pas de redirection. */
    fun postAnonymous(url: String): HttpReply = exchange(
        method = "POST",
        url = url,
        token = "",
        body = null,
        readBody = true,
        anonymous = true,
        maxBytes = ACCOUNT_BYTES,
    )

    fun exchange(
        method: String,
        url: String,
        token: String,
        body: ByteArray?,
        readBody: Boolean,
        ifMatch: String? = null,
        ifNoneMatch: String? = null,
        anonymous: Boolean = false,
        maxBytes: Int = SyncRules.MAX_HTTP_BYTES,
    ): HttpReply {
        if (!anonymous && !SyncRules.validToken(token)) throw SyncStop("invalid")
        val connection = (URL(url).openConnection() as HttpURLConnection)
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (!anonymous) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.setRequestProperty("User-Agent", "Unscroll")
            if (ifMatch != null) {
                connection.setRequestProperty("If-Match", SyncRules.quotedEtag(ifMatch))
            }
            if (ifNoneMatch != null) {
                connection.setRequestProperty("If-None-Match", ifNoneMatch)
            }
            if (anonymous) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
            } else if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status in 300..399) throw SyncStop("network")
            val header = SyncRules.strongEtag(connection.getHeaderField("ETag"))
            if (!readBody || status !in 200..299) return HttpReply(status, "", ByteArray(0), header)
            val stream = connection.inputStream ?: return HttpReply(status, "", ByteArray(0), header)
            val payload = stream.use { input ->
                val buffer = ByteArray(8 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw SyncStop("too-large")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            if (payload.size > maxBytes) throw SyncStop("too-large")
            return HttpReply(status, String(payload, Charsets.UTF_8), payload, header)
        } catch (stop: SyncStop) {
            throw stop
        } catch (_: SocketTimeoutException) {
            throw SyncStop("network")
        } catch (_: IOException) {
            throw SyncStop("network")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val ACCOUNT_BYTES = 8192
    }
}
