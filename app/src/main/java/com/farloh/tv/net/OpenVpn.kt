package com.farloh.tv.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import java.util.concurrent.CopyOnWriteArraySet

/**
 * VPN for Settings → Network → VPN. Uses OpenVPN configuration files (.ovpn) from the user's VPN provider
 * (ExpressVPN, NordVPN, Surfshark, CyberGhost, PIA, ProtonVPN, …) and runs them through the free, open-source
 * app "OpenVPN for Android", controlled from here with its official remote-control interface.
 */
object OpenVpn {
    const val PKG = "de.blinkt.openvpn"
    private const val TAG = "FarlohVpn"
    private val main = Handler(Looper.getMainLooper())

    /** Last state reported by OpenVPN for Android, e.g. CONNECTED, NOPROCESS, AUTH_FAILED. */
    @Volatile var state: String = ""; private set
    @Volatile var message: String = ""; private set
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    private var service: IOpenVPNAPIService? = null
    private var binding = false
    private val waiting = ArrayList<(IOpenVPNAPIService?) -> Unit>()

    val isConnected get() = state == "CONNECTED"
    val isBusy get() = state in setOf("CONNECTING", "WAIT", "AUTH", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES", "RECONNECTING",
        "TCP_CONNECT", "RESOLVE", "CONNECTRETRY", "VPN_GENERATE_CONFIG", "AUTH_PENDING")

    fun installed(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    private val callback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            main.post {
                this@OpenVpn.state = state.orEmpty()
                this@OpenVpn.message = message.orEmpty()
                listeners.forEach { runCatching { it() } }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = IOpenVPNAPIService.Stub.asInterface(binder)
            service = s
            binding = false
            runCatching { s.registerStatusCallback(callback) }.onFailure { Log.w(TAG, "status callback", it) }
            flush(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            state = ""
            listeners.forEach { runCatching { it() } }
        }
    }

    private fun flush(s: IOpenVPNAPIService?) {
        val list = ArrayList(waiting); waiting.clear()
        list.forEach { runCatching { it(s) } }
    }

    /** Runs [block] with the connected remote-control service (null if OpenVPN for Android can't be reached). */
    fun withService(ctx: Context, block: (IOpenVPNAPIService?) -> Unit) {
        service?.let { block(it); return }
        waiting += block
        if (binding) return
        binding = true
        val intent = Intent(IOpenVPNAPIService::class.java.name).setPackage(PKG)
        val ok = runCatching { ctx.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!ok) { binding = false; flush(null) }
        else main.postDelayed({ if (binding) { binding = false; flush(service) } }, 10_000)
    }

    /** Starts listening for the VPN state (cheap; only if OpenVPN for Android is installed). */
    fun watch(ctx: Context) {
        if (installed(ctx)) withService(ctx) { }
    }

    private var autoTried = false

    /** "Connect VPN when the app opens": once per app start, and only if it isn't already connected. */
    fun autoConnect(ctx: Context, connect: () -> Unit) {
        if (autoTried) return
        autoTried = true
        if (!installed(ctx)) return
        withService(ctx) { s ->
            if (s == null) return@withService
            // The current state arrives just after registering; give it a moment.
            main.postDelayed({ if (!isConnected && !isBusy) connect() }, 1500)
        }
    }

    fun disconnect(ctx: Context) = withService(ctx) { s -> runCatching { s?.disconnect() } }

    fun looksLikeProfile(text: String): Boolean {
        val t = text.lowercase()
        return Regex("(?m)^\\s*remote\\s+\\S+").containsMatchIn(t) && (t.contains("client") || t.contains("tls-client"))
    }

    fun needsLogin(config: String): Boolean =
        Regex("(?im)^\\s*(auth-user-pass|<auth-user-pass>)").containsMatchIn(config)

    /** A readable name from the file name, e.g. "my_expressvpn_singapore_-_marina_bay_udp.ovpn" → "Singapore - Marina Bay UDP". */
    fun nameFrom(fileName: String?, config: String): String {
        val base = fileName?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        if (base != null) {
            val words = base.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
                .replace(Regex("(?i)^my expressvpn "), "")
            return words.split(' ').joinToString(" ") { w -> if (w.length <= 3 && w.all { it.isLetter() }) w.uppercase() else w.replaceFirstChar { it.uppercase() } }
        }
        val remote = Regex("(?m)^\\s*remote\\s+(\\S+)").find(config)?.groupValues?.get(1)
        return remote ?: "VPN location"
    }

    /**
     * The provider's .ovpn made ready for OpenVPN for Android: the login is put inside it, and a few
     * old Windows-only or removed options that stop newer OpenVPN versions are taken out or updated.
     */
    fun buildConfig(raw: String, user: String, pass: String): String {
        val out = StringBuilder()
        var inAuthBlock = false
        var cipher: String? = null
        var hasDataCiphers = false
        for (line in raw.lines()) {
            val t = line.trim()
            val lower = t.lowercase()
            if (inAuthBlock) { if (lower.startsWith("</auth-user-pass>")) inAuthBlock = false; continue }
            if (lower.startsWith("<auth-user-pass>")) { inAuthBlock = true; continue }
            when (lower.split(Regex("\\s+"), limit = 2).first()) {
                "auth-user-pass", "keysize", "block-outside-dns", "route-method", "register-dns", "dhcp-renew", "dhcp-release" -> continue
                "ns-cert-type" -> { out.append("remote-cert-tls server\n"); continue }
                "cipher" -> cipher = t.split(Regex("\\s+")).getOrNull(1)
                "data-ciphers", "ncp-ciphers" -> hasDataCiphers = true
            }
            out.append(line.trimEnd('\r')).append('\n')
        }
        if (cipher != null && !hasDataCiphers) out.append("data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:$cipher\n")
        if (needsLogin(raw)) out.append("<auth-user-pass>\n").append(user).append('\n').append(pass).append("\n</auth-user-pass>\n")
        return out.toString()
    }

    /** Plain-words state for the Settings screen. */
    fun describe(): String = when (state) {
        "" , "NOPROCESS", "EXITING" -> "Not connected"
        "CONNECTED" -> "Connected"
        "AUTH_FAILED" -> "Login rejected – check the VPN username/password"
        "USER_VPN_PERMISSION_CANCELLED", "USER_VPN_PERMISSION" -> "VPN permission not given"
        "NONETWORK" -> "Waiting for internet"
        "AUTH_PENDING" -> "Waiting for login"
        else -> if (isBusy) "Connecting…" else state.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
