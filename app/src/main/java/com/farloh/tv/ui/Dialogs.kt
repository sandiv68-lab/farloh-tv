package com.farloh.tv.ui

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.farloh.tv.R
import com.farloh.tv.data.Store

/** Small reusable dialogs: single choice, text input, PIN entry. */
object Dialogs {

    fun <T> choose(activity: Activity, title: String, labels: List<String>, values: List<T>, current: T, onPick: (T) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(current)) { d, which ->
                d.dismiss()
                onPick(values[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun input(
        activity: Activity, title: String, initial: String = "", hint: String = "",
        password: Boolean = false, numeric: Boolean = false, maxLength: Int = 0,
        onDone: (String) -> Unit,
    ) {
        val field = EditText(activity).apply {
            setText(initial)
            setHint(hint)
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(activity, R.color.text))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text2))
            inputType = when {
                numeric && password -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT
            }
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            if (maxLength > 0) filters = arrayOf(InputFilter.LengthFilter(maxLength))
            setSelection(text.length)
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(field) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onDone(field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.dismiss(); onDone(field.text.toString()); true
            } else false
        }
        dialog.show()
        field.requestFocus()
    }

    /** Runs [onOk] right away if no parental PIN is set, otherwise after the correct PIN is entered. */
    fun requirePin(activity: Activity, onOk: () -> Unit) {
        val store = Store.get(activity)
        if (!store.hasPin) { onOk(); return }
        input(activity, "Enter parental PIN", password = true, numeric = true, maxLength = 8) { pin ->
            if (store.checkPin(pin)) onOk()
            else Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show()
        }
    }

    fun confirm(activity: Activity, title: String, message: String, yes: String = "Yes", onYes: () -> Unit) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
