package com.farloh.tv.net

import android.content.Context
import android.os.Build
import android.util.Log
import com.farloh.tv.R
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class HttpError(val code: Int) : IOException("Server responded with HTTP $code")

object Net {
    const val USER_AGENT = "FarlohTV/1.0 (Linux; Android)"
    private const val TAG = "FarlohNet"

    class LegacyTls(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

    /** Non-null only on Android < 7.0, where we add our bundled root certificates at runtime. */
    var legacyTls: LegacyTls? = null
        private set

    fun init(context: Context) {
        // Android 7.0+ reads the same bundled roots from res/xml/network_security_config.xml.
        if (Build.VERSION.SDK_INT >= 24) return
        try {
            val extra = context.resources.openRawResource(R.raw.extra_roots).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificates(input)
                    .filterIsInstance<X509Certificate>()
            }
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            extra.forEachIndexed { i, cert -> keyStore.setCertificateEntry("farloh_root_$i", cert) }

            val trustManager = CompositeTrustManager(trustManagerFor(null), trustManagerFor(keyStore))
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(trustManager), null)
            legacyTls = LegacyTls(ssl.socketFactory, trustManager)
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
        } catch (e: Exception) {
            Log.w(TAG, "Could not install bundled root certificates", e)
        }
    }

    private fun trustManagerFor(keyStore: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Opens a GET connection, following redirects (including http <-> https) up to 5 times. */
    fun open(url: String, readTimeoutMs: Int = 60_000): HttpURLConnection {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399 && code != 304) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || ++redirects > 5) throw IOException("Too many redirects")
                current = URL(current, location)
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw HttpError(code)
            }
            return conn
        }
    }

    fun getString(url: String): String {
        val conn = open(url, 30_000)
        try {
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /** Streams the response into [dest] via a temporary file so a failed download never corrupts the cache. */
    fun download(url: String, dest: File) {
        val tmp = File(dest.path + ".part")
        val conn = open(url)
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        } finally {
            conn.disconnect()
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Could not save downloaded data")
    }

    fun friendlyError(e: Throwable): String = when (e) {
        is UnknownHostException -> "Can't find the server. Check the server address and your internet connection."
        is SocketTimeoutException -> "The server took too long to respond. Try again."
        is ConnectException -> "Couldn't connect to the server. Check the address and port."
        is SSLException -> "Secure connection failed (certificate problem). If your server uses its own certificate it needs to be added to the app, or try http:// instead."
        is HttpError -> when (e.code) {
            401, 403 -> "The server refused the login (HTTP ${e.code}). Check your username and password."
            404 -> "The server address looks wrong (HTTP 404)."
            else -> "Server error (HTTP ${e.code}). Try again later."
        }
        else -> e.message ?: e.javaClass.simpleName
    }
}

/** Trusts a certificate if either the system store or our bundled roots trust it. Never trusts everything. */
private class CompositeTrustManager(
    private val system: X509TrustManager,
    private val bundled: X509TrustManager,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            bundled.checkServerTrusted(chain, authType)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        system.acceptedIssuers + bundled.acceptedIssuers
}
