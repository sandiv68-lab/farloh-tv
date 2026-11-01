package com.farloh.tv.player

import com.farloh.tv.data.Store
import com.farloh.tv.net.Net
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
}
