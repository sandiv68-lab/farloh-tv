package com.farloh.tv.player

import com.farloh.tv.data.Account
import com.farloh.tv.data.Store
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import kotlinx.coroutines.delay
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * "Disconnect stream on exit" (Settings → General): for accounts that allow few connections, the old stream's
 * connection is closed completely before the next one opens, so the server doesn't refuse the new one
 * ("too many connections") until the old one times out.
 *
 * Xtream servers have no "I'm leaving" command: a stream's connection is freed when its network connection
 * closes. HLS (.m3u8) sessions can linger on the server, so Live TV uses the .ts stream while this is on.
 */
object StreamGate {

    /** Each player's own network client, so its stream connections can be shut without touching other players. */
    private val clients = java.util.WeakHashMap<Any, OkHttpClient>()

    /** A network client for one player: its own connection pool and request queue. */
    fun newClient(): OkHttpClient = Net.client.newBuilder()
        .connectionPool(ConnectionPool())
        .dispatcher(Dispatcher())
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun register(player: Any, client: OkHttpClient) { clients[player] = client }

    /**
     * Shuts every network connection of [player] straight away: requests still running are cancelled
     * (their sockets close) and connections kept for re-use are closed.
     */
    @Synchronized
    fun close(player: Any?) {
        val c = clients[player ?: return] ?: return
        runCatching { c.dispatcher.cancelAll() }
        runCatching { c.connectionPool.evictAll() }
    }

    /** Live TV: the .ts stream instead of HLS while disconnecting is on. */
    fun streamUrl(store: Store, p: Playable): String =
        if (p.kind == Kind.LIVE && store.disconnectOn(p.playlistId) && p.url.endsWith(".m3u8", ignoreCase = true))
            p.url.dropLast(5) + ".ts" else p.url

    /**
     * 1-connection subscriptions: waits (up to [maxMs]) until the server shows a free connection.
     * Returns false if it gave up (the next stream is started anyway).
     */
    suspend fun waitUntilFree(
        store: Store, account: Account, pid: String?, maxMs: Long = 10_000,
        progress: (active: Int, max: Int, seconds: Int) -> Unit = { _, _, _ -> },
    ): Boolean {
        if (!store.disconnectOn(pid) || store.maxConnections(pid) != 1) return true
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < maxMs) {
            val (active, max) = Xtream.connections(account) ?: return true // can't ask: don't hold things up
            if (active < max) return true
            progress(active, max, ((System.currentTimeMillis() - started) / 1000).toInt())
            delay(1_000)
        }
        return false
    }
}
