package org.openvoyah.controls.data.updates

import org.openvoyah.controls.core.updates.ReleasePolicy
import java.io.Closeable
import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal interface ReleaseHttp {
    fun open(url: String): Response

    interface Response : Closeable {
        val status: Int
        val length: Long
        fun input(): InputStream
    }
}

/** Public GitHub requests need no account or token. Redirects never downgrade HTTPS. */
internal class GitHubReleaseHttp(private val version: String) : ReleaseHttp {
    override fun open(url: String): ReleaseHttp.Response {
        val catalog = url == ReleasePolicy.CATALOG_URL
        require(catalog || url.startsWith(ReleasePolicy.DOWNLOAD_PREFIX))
        var next = URL(url)
        repeat(6) { redirect ->
            val connection = (next.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "OpenVoyahControls/$version")
                setRequestProperty("Accept", if (catalog) "application/vnd.github+json" else "application/octet-stream")
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECTS) {
                    val location = connection.getHeaderField("Location")
                    val target = location?.let { URI(next.toString()).resolve(it) }
                    if (catalog || redirect == 5 || target == null || !trustedRedirect(target)) {
                        throw UpdateException(UpdateProblem.UNTRUSTED_DOWNLOAD)
                    }
                    next = target.toURL()
                    connection.disconnect()
                } else {
                    return object : ReleaseHttp.Response {
                        override val status = status
                        override val length = connection.getHeaderFieldLong("Content-Length", -1)
                        override fun input(): InputStream = connection.inputStream
                        override fun close() { connection.disconnect() }
                    }
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw UpdateException(UpdateProblem.UNTRUSTED_DOWNLOAD)
    }

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val ASSET_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

        internal fun trustedRedirect(uri: URI): Boolean = uri.scheme == "https" &&
            uri.host in ASSET_HOSTS && (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null && uri.rawFragment == null
    }
}
