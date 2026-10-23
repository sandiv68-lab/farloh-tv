package com.farloh.tv.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.farloh.tv.data.Store
import com.farloh.tv.net.Net
import com.farloh.tv.net.OpenVpn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * VPN screens: add .ovpn locations, VPN login, connect / disconnect.
 * Create one per activity as a property (it registers the pickers and permission screens).
 */
class VpnFlow(private val activity: BaseActivity, private val onChanged: () -> Unit = {}) {

    private val store get() = Store.get(activity)
    private var afterPermission: (() -> Unit)? = null

    private val permission = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val next = afterPermission
        afterPermission = null
        if (res.resultCode == Activity.RESULT_OK) next?.invoke()
        else activity.toast("The VPN needs that permission to connect.", long = true)
    }

    private val picker = FilePicker(activity, listOf("ovpn", "conf"), ".ovpn file") { uri -> importUri(uri) }

    // ======================= Connect =======================

    /** Connects to the chosen location. [quiet] = automatic connect on start (no "set it up" prompts). */
    fun connect(quiet: Boolean = false) {
        val profile = store.vpnProfile
        if (profile == null) { if (!quiet) addLocation(); return }
        if (!OpenVpn.installed(activity)) { if (!quiet) askInstall(); return }
        if (OpenVpn.needsLogin(profile.config) && store.vpnUser.isBlank()) {
            if (!quiet) editLogin { connect() }
            return
        }
        OpenVpn.withService(activity) { s ->
            if (s == null) {
                if (!quiet) activity.toast("Couldn't reach OpenVPN for Android. Open it once, then try again.", long = true)
                return@withService
            }
            try {
                // 1) OpenVPN for Android asks once whether Farloh TV may control it.
                s.prepare(activity.packageName)?.let { launch(it) { connect(quiet) }; return@withService }
                // 2) Android asks once whether a VPN may be set up.
                s.prepareVPNService()?.let { launch(it) { connect(quiet) }; return@withService }
                s.startVPN(OpenVpn.buildConfig(profile.config, store.vpnUser, store.vpnPassword))
                if (!quiet) activity.toast("Connecting VPN: ${profile.name}…")
            } catch (e: Exception) {
                activity.toast("VPN: ${e.message ?: "couldn't start"}", long = true)
            }
        }
    }

    fun disconnect() {
        OpenVpn.disconnect(activity)
        activity.toast("VPN disconnected")
    }

    private fun launch(intent: Intent, then: () -> Unit) {
        afterPermission = then
        try {
            permission.launch(intent)
        } catch (e: Exception) {
            afterPermission = null
            activity.toast("Couldn't open the permission screen: ${e.message}", long = true)
        }
    }

    private fun askInstall() {
        AlertDialog.Builder(activity)
            .setTitle("Install OpenVPN for Android")
            .setMessage("Farloh TV runs your VPN through the free, open-source app \"OpenVPN for Android\" (by Arne Schwabe). " +
                "Install it once from the Play Store, then come back and press Connect.\n\n" +
                "You don't need to set anything up inside it – Farloh TV sends it your location and login.")
            .setPositiveButton("Open Play Store") { _, _ -> openStore() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${OpenVpn.PKG}"))
        try {
            activity.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${OpenVpn.PKG}")))
            } catch (e2: ActivityNotFoundException) {
                activity.toast("Search for \"OpenVPN for Android\" in your app store.", long = true)
            }
        }
    }

    // ======================= Locations =======================

    fun chooseLocation() {
        val list = store.vpnProfiles
        val active = store.vpnProfile?.id
        val labels = list.map { (if (it.id == active) "✓  " else "     ") + it.name } +
            listOf("+  Add a location from a file (.ovpn)", "+  Add a location from a web link") +
            (if (list.isNotEmpty()) listOf("–  Remove a location") else emptyList())
        AlertDialog.Builder(activity).setTitle("VPN location")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < list.size -> {
                        val wasOn = OpenVpn.isConnected || OpenVpn.isBusy
                        store.vpnActiveId = list[which].id
                        onChanged()
                        if (wasOn) connect() // switch to the new location now
                    }
                    which == list.size -> pickFile()
                    which == list.size + 1 -> addFromLink()
                    else -> removeLocation()
                }
            }
            .setNegativeButton("Close", null).show()
    }

    fun addLocation() {
        AlertDialog.Builder(activity).setTitle("Add a VPN location")
            .setMessage("Use an OpenVPN file (.ovpn) from your VPN provider – one file per location.\n\n" +
                "Tip: you can also open the .ovpn file in a file manager and choose Farloh TV.")
            .setPositiveButton("From a file") { _, _ -> pickFile() }
            .setNeutralButton("From a web link") { _, _ -> addFromLink() }
            .setNegativeButton("How to get the file") { _, _ -> showHelp() }
            .show()
    }

    private fun pickFile() = picker.pick()

    private fun addFromLink() {
        Dialogs.input(activity, "Web link to the .ovpn file", "https://") { url ->
            val u = url.trim()
            if (!u.startsWith("http")) { activity.toast("Enter a link starting with https://"); return@input }
            activity.lifecycleScope.launch {
                val text = runCatching { withContext(Dispatchers.IO) { Net.getString(u) } }
                text.onSuccess { importText(it, Uri.parse(u).lastPathSegment) }
                text.onFailure { activity.toast("Couldn't download it: ${Net.friendlyError(it)}", long = true) }
            }
        }
    }

    private fun removeLocation() {
        val list = store.vpnProfiles
        AlertDialog.Builder(activity).setTitle("Remove which location?")
            .setItems(list.map { it.name }.toTypedArray()) { _, which ->
                val gone = list[which]
                store.vpnProfiles = list.filter { it.id != gone.id }
                if (store.vpnActiveId == gone.id) store.vpnActiveId = store.vpnProfiles.firstOrNull()?.id.orEmpty()
                activity.toast("Removed ${gone.name}")
                onChanged()
            }
            .setNegativeButton("Cancel", null).show()
    }

    fun importUri(uri: Uri) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = runCatching {
                        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("empty")
                    if (bytes.size > 512 * 1024) error("That file is too big for an .ovpn file")
                    name to String(bytes, Charsets.UTF_8)
                }
            }
            result.onSuccess { (name, text) -> importText(text, name) }
            result.onFailure { if (it is SecurityException || it is java.io.IOException) picker.cantRead() else activity.toast("Couldn't open that file: ${it.message}", long = true) }
        }
    }

    /** Saves an .ovpn as a location (and makes it the one in use). Returns false if it isn't an OpenVPN file. */
    fun importText(text: String, fileName: String?): Boolean {
        if (!OpenVpn.looksLikeProfile(text)) {
            activity.toast("That isn't an OpenVPN (.ovpn) file.", long = true)
            return false
        }
        val name = OpenVpn.nameFrom(fileName, text)
        val existing = store.vpnProfiles
        val id = existing.firstOrNull { it.name == name }?.id ?: UUID.randomUUID().toString()
        store.vpnProfiles = existing.filter { it.id != id } + Store.VpnProfile(id, name, text)
        store.vpnActiveId = id
        onChanged()
        val needLogin = OpenVpn.needsLogin(text) && store.vpnUser.isBlank()
        AlertDialog.Builder(activity).setTitle("VPN location added")
            .setMessage("\"$name\" is ready." + if (needLogin) "\n\nNext, enter the VPN login from your provider's manual-setup page." else "")
            .setPositiveButton(if (needLogin) "Enter login" else "Connect now") { _, _ ->
                if (needLogin) editLogin { connect() } else connect()
            }
            .setNegativeButton("Later", null).show()
        return true
    }

    // ======================= Login & help =======================

    fun editLogin(then: (() -> Unit)? = null) {
        Dialogs.input(activity, "VPN username (manual-setup username, not your email)", store.vpnUser) { u ->
            store.vpnUser = u.trim()
            Dialogs.input(activity, "VPN password (manual-setup password)", password = true) { pw ->
                store.vpnPassword = pw
                onChanged()
                if (then != null) then()
                else if (OpenVpn.isConnected) connect() // reconnect with the new login
            }
        }
    }

    fun showHelp() {
        AlertDialog.Builder(activity).setTitle("Setting up your VPN")
            .setMessage(
                "Farloh TV uses your VPN provider's OpenVPN files. You need two things from the provider's website: " +
                    "a file (.ovpn) for each location you want, and the manual-setup username and password.\n\n" +
                    "• ExpressVPN: sign in on expressvpn.com → Set up other devices → Manual configuration → OpenVPN. " +
                    "Copy the username and password shown there, then download the file for a location.\n\n" +
                    "• NordVPN: Nord Account → NordVPN → Manual setup (service credentials), and download an OpenVPN UDP file " +
                    "from nordvpn.com → Servers.\n\n" +
                    "• Surfshark, CyberGhost, PIA, ProtonVPN, IPVanish and most others: look for \"Manual setup\" / \"OpenVPN\" " +
                    "in your account.\n\n" +
                    "Then: Settings → Network → VPN location → Add from a file, or open the .ovpn file in a file manager and choose " +
                    "Farloh TV. You can also put the file online (e.g. a share link) and use \"From a web link\".\n\n" +
                    "The connection is run by the free app \"OpenVPN for Android\" (Farloh TV asks you to install it once). " +
                    "While connected, the VPN covers the whole device."
            )
            .setPositiveButton("OK", null).show()
    }
}
