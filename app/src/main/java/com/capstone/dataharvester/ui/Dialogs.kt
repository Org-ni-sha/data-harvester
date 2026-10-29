package com.capstone.dataharvester.ui

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.capstone.dataharvester.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Every dialog in the app is built here, so they all share one look: the rounded blue
 * panel (drawable/bg_dialog), the text and button styles and the dimmed/blurred screen
 * from ThemeOverlay.DataHarvester.Dialog (set as materialAlertDialogTheme).
 */
object Dialogs {

    /** A dialog builder with the app's dialog look; use like AlertDialog.Builder. */
    fun builder(context: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(context)
            .setBackground(ContextCompat.getDrawable(context, R.drawable.bg_dialog))

    /** Paint the primary button red, for actions that delete data. Call after show(). */
    fun AlertDialog.withDangerButton(): AlertDialog = apply {
        getButton(DialogInterface.BUTTON_POSITIVE)?.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.danger_red))
    }

    /** A shown, non-cancelable progress dialog (see layout/dialog_progress). */
    class Progress internal constructor(
        val dialog: AlertDialog,
        private val indicator: LinearProgressIndicator,
        private val percent: TextView
    ) {
        /** Switch to a determinate bar and show [value] (0-100) as a percentage. */
        fun setProgress(value: Int) {
            if (indicator.isIndeterminate) {
                // An indicator must be hidden while switching modes
                indicator.visibility = View.INVISIBLE
                indicator.isIndeterminate = false
                indicator.visibility = View.VISIBLE
                percent.visibility = View.VISIBLE
            }
            indicator.setProgressCompat(value, true)
            percent.text = "$value%"
        }

        fun dismiss() = dialog.dismiss()
    }

    /** Show a progress dialog; indeterminate until [Progress.setProgress] is called. */
    fun progress(context: Context, title: String, message: String): Progress {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null)
        view.findViewById<TextView>(R.id.progressMessage).text = message
        val indicator = view.findViewById<LinearProgressIndicator>(R.id.progressIndicator).apply {
            isIndeterminate = true
        }
        val dialog = builder(context)
            .setTitle(title)
            .setView(view)
            .setCancelable(false)
            .show()
        return Progress(dialog, indicator, view.findViewById(R.id.progressPercent))
    }
}
