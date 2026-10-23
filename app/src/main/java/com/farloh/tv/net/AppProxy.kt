package com.farloh.tv.net

import android.content.Context
import com.farloh.tv.data.Store
import okhttp3.Authenticator
import okhttp3.Credentials
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Optional proxy for everything inside Farloh TV (Settings → Network → Proxy).
 * Works with VPN providers that offer SOCKS5 or HTTP proxy servers (e.g. NordVPN, Surfshark, PIA, IPVanish).
 * Read live from the settings, so changes apply without restarting.
 */
object AppProxy : ProxySelector() {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        // SOCKS5 username/password goes through Java's standard Authenticator.
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val s = store() ?: return null
                if (s.proxyType != "socks" || s.proxyUser.isBlank()) return null
                if (!requestingHost.equals(s.proxyHost, true) && requestingSite?.hostAddress != s.proxyHost) return null
                return PasswordAuthentication(s.proxyUser, s.proxyPassword.toCharArray())
            }
        })
    }

    private fun store(): Store? = if (::app.isInitialized) Store.get(app) else null

    fun describe(s: Store): String = when (s.proxyType) {
        "socks" -> "SOCKS5 ${s.proxyHost}:${s.proxyPort}"
        "http" -> "HTTP ${s.proxyHost}:${s.proxyPort}"
        else -> "Off"
    }

    /** Call after the proxy setting changes so open connections aren't reused. */
    fun reset() {
        runCatching { Net.client.connectionPool.evictAll() }
    }

    override fun select(uri: URI?): List<Proxy> {
        val s = store() ?: return listOf(Proxy.NO_PROXY)
        if (s.proxyHost.isBlank() || s.proxyPort !in 1..65535) return listOf(Proxy.NO_PROXY)
        val address = InetSocketAddress.createUnresolved(s.proxyHost, s.proxyPort)
        return when (s.proxyType) {
            "socks" -> listOf(Proxy(Proxy.Type.SOCKS, address))
            "http" -> listOf(Proxy(Proxy.Type.HTTP, address))
            else -> listOf(Proxy.NO_PROXY)
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    /** HTTP proxies that ask for a login. */
    val authenticator = Authenticator { _, response ->
        val s = store() ?: return@Authenticator null
        if (s.proxyType != "http" || s.proxyUser.isBlank()) return@Authenticator null
        if (response.request.header("Proxy-Authorization") != null) return@Authenticator null // already tried
        response.request.newBuilder()
            .header("Proxy-Authorization", Credentials.basic(s.proxyUser, s.proxyPassword))
            .build()
    }
}
