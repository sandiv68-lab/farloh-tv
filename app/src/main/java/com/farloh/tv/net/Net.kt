package com.farloh.tv.net

import android.content.Context
import android.os.Build
import android.util.Log
import com.farloh.tv.R
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.ConnectException
import java.net.SocketTimeoutException
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

    /**
     * One shared HTTP client for the whole app. It uses [AppDns] (Settings → Network → DNS),
     * follows redirects (including http <-> https) and trusts the bundled root certificates.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(AppDns)
            .proxySelector(AppProxy)
            .proxyAuthenticator(AppProxy.authenticator)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.header("User-Agent") == null) chain.proceed(req.newBuilder().header("User-Agent", USER_AGENT).build())
                else chain.proceed(req)
            }
            .apply { legacyTls?.let { sslSocketFactory(it.socketFactory, it.trustManager) } }
            .build()
    }

    /** GET [url]; throws [HttpError] for non-2xx answers. The caller must close the response. */
    fun open(url: String, readTimeoutMs: Int = 60_000): Response {
        val c = if (readTimeoutMs == 60_000) client
                else client.newBuilder().readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
        val res = c.newCall(Request.Builder().url(url).header("Accept", "*/*").build()).execute()
        if (!res.isSuccessful) {
            val code = res.code
            res.close()
            throw HttpError(code)
        }
        return res
    }

    /**
     * Size of a remote file in bytes without downloading it, or null if the server won't say.
     * Tries a HEAD request first, then a 1-byte ranged GET (many IPTV servers ignore HEAD).
     */
    fun contentLength(url: String): Long? {
        val quick = client.newBuilder().readTimeout(10, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()
        try {
            quick.newCall(Request.Builder().url(url).head().build()).execute().use { res ->
                if (res.isSuccessful) res.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
            }
        } catch (e: Exception) { /* try ranged GET */ }
        try {
            quick.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { res ->
                if (res.code == 206) {
                    // Content-Range: bytes 0-0/123456789
                    res.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
                } else if (res.isSuccessful) {
                    res.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
                }
            }
        } catch (e: Exception) { /* unknown */ }
        return null
    }

    fun getString(url: String): String = open(url, 30_000).use { it.body?.string().orEmpty() }

    /** Streams the response into [dest] via a temporary file so a failed download never corrupts the cache. */
    fun download(url: String, dest: File) {
        val tmp = File(dest.path + ".part")
        open(url).use { res ->
            val body = res.body ?: throw IOException("Empty response")
            body.byteStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) } }
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
