package com.farloh.tv.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.farloh.tv.R
import com.farloh.tv.record.RecordScheduler

/**
 * Recording setup (Settings → Recordings → Recording setup): the three things Android must allow so recordings
 * work while the app isn't open and the TV is off, each with its status and a way to fix it.
 * It also pops up when a recording is set up and something isn't ready.
 */
object RecordSetup {

    private class Check(val title: String, val ok: Boolean, val explain: String, val fix: (BaseActivity) -> Unit)

    private fun checks(a: BaseActivity): List<Check> {
        val tv = a.tvMode
        val alarms = Check(
            "Alarms & reminders", RecordScheduler.canExact(a),
            "So recordings start exactly on time." +
                if (tv) " Settings → Privacy → Special app access → Alarms & reminders → Farloh TV → On." else "",
        ) { act -> openAlarms(act) }
        val background = Check(
            "Background activity", !backgroundRestricted(a),
            "So Android doesn't stop Farloh while you're not using it." +
                if (tv) " Settings → Apps → Farloh TV (background activity / restrictions)." else " App info → Battery → Allow background usage.",
        ) { act -> openAppInfo(act) }
        val energy = Check(
            if (tv) "Energy optimisation off" else "Battery optimisation off", !optimised(a),
            "So the box doesn't put Farloh to sleep during a recording." +
                if (tv) " Settings → Privacy → Special app access → Energy optimisation → Farloh TV → Off." else " Set Farloh TV to Unrestricted / Don't optimise.",
        ) { act -> openEnergy(act) }
        return listOf(alarms, background, energy)
    }

    /** "3 of 3 ready" etc. for the Settings row. */
    fun summary(a: BaseActivity): String {
        val c = checks(a)
        val ok = c.count { it.ok }
        return if (ok == c.size) "Ready ✓" else "${c.size - ok} to fix"
    }

    fun allReady(a: BaseActivity) = checks(a).all { it.ok }

    /** The checklist. Statuses update when you come back from Android's settings. [then] runs when it's closed. */
    fun show(a: BaseActivity, then: () -> Unit = {}) {
        val d = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            list.removeAllViews()
            for (c in checks(a)) {
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.focus_bg)
                    isFocusable = true; isClickable = true
                    setPadding(px(14), px(8), px(14), px(8))
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(a).apply {
                            text = if (c.ok) "✓" else "✗"
                            textSize = 18f
                            setTextColor(ContextCompat.getColor(a, if (c.ok) R.color.text else R.color.danger))
                            minWidth = px(26)
                        })
                        addView(TextView(a).apply {
                            text = c.title
                            textSize = 16f
                            setTextColor(ContextCompat.getColor(a, R.color.text))
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(a).apply {
                            text = if (c.ok) "Allowed" else "Fix ›"
                            textSize = 14f
                            setTextColor(ContextCompat.getColor(a, if (c.ok) R.color.text2 else R.color.accent))
                        })
                    })
                    addView(TextView(a).apply {
                        text = c.explain
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(a, R.color.text2))
                        setPadding(px(26), px(2), 0, 0)
                    })
                    setOnClickListener { c.fix(a) }
                }
                list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = px(4)
                })
            }
            list.addView(TextView(a).apply {
                text = "The TV screen can be off, but the " + (if (a.tvMode) "box" else "phone") + " must stay plugged in, " +
                    "switched on (standby is fine) and connected to the internet." +
                    if (a.tvMode) " If the box has Settings → System → Power & energy → Energy modes, don't choose \"Low energy\"." else ""
                textSize = 13f
                setTextColor(ContextCompat.getColor(a, R.color.text2))
                setPadding(px(14), px(8), px(14), 0)
            })
        }
        fill()
        val box = android.widget.ScrollView(a).apply {
            setPadding(px(10), px(4), px(10), 0)
            addView(list)
        }
        val dialog = AlertDialog.Builder(a)
            .setTitle("Recording setup")
            .setView(box)
            .setPositiveButton("Done", null)
            .create()
        dialog.setOnDismissListener { then() }
        dialog.setOnShowListener {
            // Back from Android's settings: show the new statuses.
            dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { focused -> if (focused) fill() }
            list.getChildAt(checks(a).indexOfFirst { !it.ok }.coerceAtLeast(0))?.requestFocus()
        }
        dialog.show()
    }

    // ---------------- What Android reports ----------------

    private fun backgroundRestricted(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 && (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted

    private fun optimised(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 23 && !(c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

    // ---------------- Opening the right settings ----------------

    private fun pkg(a: BaseActivity) = Uri.parse("package:${a.packageName}")

    /** Starts [i] only if something on the device really handles it. */
    private fun open(a: BaseActivity, i: Intent): Boolean {
        if (a.packageManager.resolveActivity(i, 0) == null) return false
        return try { a.startActivity(i); true } catch (e: Exception) { false }
    }

    /** Android TV / Google TV keep these under Settings → Privacy → Special app access, with no direct link. */
    private fun openSpecialAccess(a: BaseActivity, item: String) {
        a.toast("Settings → Privacy → Special app access → $item → Farloh TV", long = true)
        for (i in listOf(Intent("android.settings.SPECIAL_APP_ACCESS_SETTINGS"), Intent(Settings.ACTION_PRIVACY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS))) if (open(a, i)) return
    }

    private fun openAlarms(a: BaseActivity) {
        if (Build.VERSION.SDK_INT < 31) return
        if (a.tvMode) { openSpecialAccess(a, "Alarms & reminders"); return }
        if (!open(a, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(a)))) openAppInfo(a)
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun openEnergy(a: BaseActivity) {
        if (Build.VERSION.SDK_INT < 23) return
        if (a.tvMode) { openSpecialAccess(a, "Energy optimisation"); return }
        // Phones: Android's own "Allow / Don't optimise" prompt, else its battery optimisation list.
        if (open(a, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(a)))) return
        if (open(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppInfo(a)
    }

    private fun openAppInfo(a: BaseActivity) {
        if (!open(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(a)))) a.toast("Open the device's Settings → Apps → Farloh TV", long = true)
    }
}
