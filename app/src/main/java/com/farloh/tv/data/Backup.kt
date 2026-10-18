package com.farloh.tv.data

import android.content.Context
import android.util.Base64
import com.farloh.tv.BuildConfig
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Farloh TV backup file (.json). With a password, the contents are encrypted with AES-256-GCM using a key
 * derived from the password (PBKDF2, 100,000 rounds), so the logins inside can't be read without it.
 */
object Backup {
    private const val FORMAT = "farloh-tv-backup"
    private const val KDF = "PBKDF2WithHmacSHA1" // available on every Android version the app supports
    private const val ROUNDS = 100_000

    class WrongPassword : Exception("Wrong password")
    class NotABackup : Exception("This isn't a Farloh TV backup file")

    class Summary(val playlists: Int, val created: String, val appVersion: String)

    fun create(ctx: Context, password: String): ByteArray {
        val payload = Store.get(ctx).exportForBackup()
            .put("created", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
            .put("appVersion", BuildConfig.VERSION_NAME)
        val outer = JSONObject().put("format", FORMAT).put("version", 1)
        if (password.isEmpty()) {
            outer.put("encrypted", false).put("data", payload)
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
            outer.put("encrypted", true).put("kdf", KDF).put("rounds", ROUNDS)
                .put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(ct))
        }
        return outer.toString(2).toByteArray(Charsets.UTF_8)
    }

    /** True if [bytes] is a backup that needs a password. Throws [NotABackup] for other files. */
    fun needsPassword(bytes: ByteArray): Boolean = outer(bytes).optBoolean("encrypted")

    /** Opens the backup without changing anything (for the "Restore this backup?" question). */
    fun read(bytes: ByteArray, password: String): Pair<JSONObject, Summary> {
        val outer = outer(bytes)
        val payload = if (outer.optBoolean("encrypted")) {
            val salt = unb64(outer.getString("salt"))
            val iv = unb64(outer.getString("iv"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, outer.optInt("rounds", ROUNDS)), GCMParameterSpec(128, iv))
            val plain = try {
                cipher.doFinal(unb64(outer.getString("data")))
            } catch (e: AEADBadTagException) {
                throw WrongPassword()
            } catch (e: javax.crypto.BadPaddingException) {
                throw WrongPassword()
            }
            JSONObject(String(plain, Charsets.UTF_8))
        } else {
            outer.getJSONObject("data")
        }
        val playlists = runCatching {
            org.json.JSONArray(payload.getJSONObject("secrets").getString("playlists")).length()
        }.getOrDefault(0)
        return payload to Summary(playlists, payload.optString("created"), payload.optString("appVersion"))
    }

    fun restore(ctx: Context, payload: JSONObject) {
        Catalog.clearMemory()
        Xtream.clearCache()
        Store.get(ctx).importFromBackup(payload)
    }

    private fun outer(bytes: ByteArray): JSONObject {
        val o = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { throw NotABackup() }
        if (o.optString("format") != FORMAT) throw NotABackup()
        return o
    }

    private fun key(password: String, salt: ByteArray, rounds: Int = ROUNDS): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
        val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
