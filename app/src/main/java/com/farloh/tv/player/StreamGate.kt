package com.farloh.tv.player

import com.farloh.tv.data.Account
import com.farloh.tv.data.Store
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import kotlinx.coroutines.delay

/**
 * "Disconnect stream on exit" (Settings → General): for accounts that allow few connections, the old stream's
 * connection is closed completely before the next one opens, so the server doesn't refuse the new one
 * ("too many connections") until the old one times out.
 *
 * Xtream servers have no "I'm leaving" command: a stream's connection is freed when its network connection
 * closes. HLS (.m3u8) sessions can linger on the server, so Live TV uses the .ts stream while this is on.
 */
object StreamGate {

    /** Closes the network connections kept open for re-use (the stream's own one closes when the player stops). */
    fun closeConnections() = runCatching { Net.client.connectionPool.evictAll() }

    /** Live TV: the .ts stream instead of HLS while disconnecting is on. */
    fun streamUrl(store: Store, p: Playable): String =
        if (p.kind == Kind.LIVE && store.disconnectOn(p.playlistId) && p.url.endsWith(".m3u8", ignoreCase = true))
            p.url.dropLast(5) + ".ts" else p.url

    /**
     * 1-connection subscriptions: waits (up to [maxMs]) until the server shows a free connection.
     * Returns false if it gave up (the next stream is started anyway).
     */
    suspend fun waitUntilFree(store: Store, account: Account, pid: String?, maxMs: Long = 10_000): Boolean {
        if (!store.disconnectOn(pid) || store.maxConnections(pid) != 1) return true
        val until = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < until) {
            val (active, max) = Xtream.connections(account) ?: return true // can't ask: don't hold things up
            if (active < max) return true
            delay(1_000)
        }
        return false
    }
}
