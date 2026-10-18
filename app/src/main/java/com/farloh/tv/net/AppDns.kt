package com.farloh.tv.net

import android.content.Context
import android.util.Log
import com.farloh.tv.data.Store
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * DNS used by everything inside Farloh TV (logins, playlists, guide, pictures, the built-in player).
 * Settings → Network → DNS picks: device default, an encrypted (DNS-over-HTTPS) preset, or a custom
 * server (plain IP like 8.8.8.8, or an https:// DNS-over-HTTPS address).
 * If the chosen DNS fails, it falls back to the device's own DNS so the app keeps working.
 */
object AppDns : Dns {
    private const val TAG = "FarlohDns"

    class Preset(val key: String, val label: String, val url: String, val bootstrap: List<String>)

    val PRESETS = listOf(
        Preset("google", "Google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
        Preset("cloudflare", "Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        Preset("quad9", "Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
        Preset("adguard", "AdGuard", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
    )

    private lateinit var app: Context
    @Volatile private var resolver: Dns? = null
    @Volatile private var resolverFor: String? = null
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val CACHE_MS = 5 * 60_000L

    /** Plain client (device DNS) used only to reach the DNS-over-HTTPS server itself. */
    private val bootstrapClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .apply { Net.legacyTls?.let { sslSocketFactory(it.socketFactory, it.trustManager) } }
            .build()
    }

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Call after the DNS setting changes. */
    fun reset() {
        resolver = null
        resolverFor = null
        cache.clear()
    }

    fun describe(store: Store): String = when (store.dnsMode) {
        "system" -> "Device default"
        "custom" -> store.dnsCustom.ifBlank { "Custom (not set)" }
        else -> PRESETS.firstOrNull { it.key == store.dnsMode }?.label ?: "Device default"
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // IP addresses and local names never need DNS.
        if (hostname.isEmpty() || hostname[0].isDigit() && hostname.all { it.isDigit() || it == '.' } || ':' in hostname) {
            return Dns.SYSTEM.lookup(hostname)
        }
        val dns = current() ?: return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (now - it.first < CACHE_MS) return it.second }
        return try {
            val result = dns.lookup(hostname)
            if (result.isEmpty()) throw UnknownHostException(hostname)
            cache[hostname] = now to result
            result
        } catch (e: Exception) {
            Log.w(TAG, "Custom DNS failed for $hostname, using device DNS", e)
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /** Resolves [hostname] with the chosen DNS only (no fallback). Used by "Test DNS". */
    fun test(hostname: String): List<InetAddress> {
        val dns = current() ?: Dns.SYSTEM
        return dns.lookup(hostname)
    }

    private fun current(): Dns? {
        if (!::app.isInitialized) return null
        val store = Store.get(app)
        val mode = store.dnsMode
        val key = "$mode|${store.dnsCustom}"
        if (key == resolverFor) return resolver
        val built: Dns? = try {
            when (mode) {
                "system" -> null
                "custom" -> buildCustom(store.dnsCustom.trim())
                else -> PRESETS.firstOrNull { it.key == mode }?.let { buildDoh(it.url, it.bootstrap) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad DNS setting", e)
            null
        }
        resolver = built
        resolverFor = key
        return built
    }

    private fun buildCustom(value: String): Dns? = when {
        value.isEmpty() -> null
        value.startsWith("https://", true) -> buildDoh(value, emptyList())
        else -> UdpDns(value)
    }

    private fun buildDoh(url: String, bootstrap: List<String>): Dns {
        val b = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(url.toHttpUrl())
            .includeIPv6(false)
            .resolvePrivateAddresses(true)
        if (bootstrap.isNotEmpty()) b.bootstrapDnsHosts(bootstrap.map { InetAddress.getByName(it) })
        return b.build()
    }

    /** Classic DNS (UDP port 53) to a server the user typed, e.g. 8.8.8.8 or 192.168.1.1:5353. */
    private class UdpDns(server: String) : Dns {
        private val address: InetSocketAddress = run {
            val host = server.substringBefore(':')
            val port = server.substringAfter(':', "53").toIntOrNull() ?: 53
            InetSocketAddress(InetAddress.getByName(host), port)
        }

        override fun lookup(hostname: String): List<InetAddress> {
            val id = Random.nextInt(0, 0xFFFF)
            val query = buildQuery(id, hostname)
            DatagramSocket().use { socket ->
                socket.soTimeout = 4000
                repeat(2) { attempt ->
                    try {
                        socket.send(DatagramPacket(query, query.size, address))
                        val buf = ByteArray(1500)
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val result = parse(buf, packet.length, id)
                        if (result.isNotEmpty()) return result
                    } catch (e: java.net.SocketTimeoutException) {
                        if (attempt == 1) throw UnknownHostException("DNS server $address did not answer")
                    }
                }
            }
            throw UnknownHostException("No address for $hostname from $address")
        }

        private fun buildQuery(id: Int, host: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(id shr 8); out.write(id and 0xFF)
            out.write(0x01); out.write(0x00) // recursion desired
            out.write(0); out.write(1)       // 1 question
            repeat(6) { out.write(0) }       // no answers / authority / additional
            for (label in host.trimEnd('.').split('.')) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                out.write(bytes.size); out.write(bytes)
            }
            out.write(0)
            out.write(0); out.write(1) // type A
            out.write(0); out.write(1) // class IN
            return out.toByteArray()
        }

        private fun parse(b: ByteArray, len: Int, id: Int): List<InetAddress> {
            fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            if (len < 12 || u16(0) != id) return emptyList()
            val rcode = b[3].toInt() and 0x0F
            if (rcode != 0) throw UnknownHostException("DNS error code $rcode")
            val qd = u16(4); val an = u16(6)
            var p = 12
            fun skipName() {
                while (p < len) {
                    val l = b[p].toInt() and 0xFF
                    if (l == 0) { p++; return }
                    if (l and 0xC0 == 0xC0) { p += 2; return }
                    p += l + 1
                }
            }
            repeat(qd) { skipName(); p += 4 }
            val out = ArrayList<InetAddress>()
            repeat(an) {
                if (p >= len) return out
                skipName()
                if (p + 10 > len) return out
                val type = u16(p); val rdLen = u16(p + 8)
                p += 10
                if (type == 1 && rdLen == 4 && p + 4 <= len) {
                    out.add(InetAddress.getByAddress(b.copyOfRange(p, p + 4)))
                }
                p += rdLen
            }
            return out
        }
    }
}
