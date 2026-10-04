package cn.jlu.schedule.ui.theme

import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.snackbar.Snackbar

object UiFeedback {
    fun showMessage(anchor: View?, message: String, palette: ThemePalette) {
        if (anchor == null) return
        Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(palette.panelBackground)
            .setTextColor(palette.textPrimary)
            .setActionTextColor(palette.iconTint)
            .setAction("知道了") { }
            .show()
    }

    fun stylePrimaryButton(button: Button, palette: ThemePalette) {
        button.backgroundTintList = null
        button.background = GlassSurface.drawable(button.context, palette, GlassSurface.Variant.Control, 18f)
        button.setTextColor(palette.buttonText)
        button.isAllCaps = false
        button.minHeight = 0
        button.minimumHeight = 0
        button.setPadding(30, 18, 30, 18)
    }

    fun styleSecondaryButton(button: Button, palette: ThemePalette) {
        button.backgroundTintList = null
        button.background = GlassSurface.drawable(button.context, palette, GlassSurface.Variant.Panel, 18f)
        button.setTextColor(palette.textSecondary)
        button.isAllCaps = false
        button.minHeight = 0
        button.minimumHeight = 0
        button.setPadding(28, 16, 28, 16)
    }

    fun styleDangerButton(button: Button, palette: ThemePalette) {
        button.backgroundTintList = null
        val danger = if (palette.isDark) {
            ColorUtils.blendARGB(0xFF8B2525.toInt(), palette.panelBackground, 0.45f)
        } else {
            ColorUtils.blendARGB(0xFFD35454.toInt(), palette.buttonBackground, 0.45f)
        }
        val textColor = if (palette.isDark) 0xFFEF9A9A.toInt() else 0xFFFFFFFF.toInt()
        button.background = GlassSurface.drawable(button.context, palette, GlassSurface.Variant.Control, 18f).apply {
            setColor(danger)
            setStroke(1, ColorUtils.setAlphaComponent(palette.textPrimary, 0x55))
        }
        button.setTextColor(textColor)
        button.isAllCaps = false
        button.minHeight = 0
        button.minimumHeight = 0
        button.setPadding(28, 16, 28, 16)
    }

    fun styleInput(input: android.widget.EditText, palette: ThemePalette) {
        input.background = GlassSurface.drawable(input.context, palette, GlassSurface.Variant.Control, 18f)
        input.setTextColor(palette.textPrimary)
        input.setHintTextColor(palette.textSecondary)
    }

    fun styleDialogSurface(dialog: AlertDialog, palette: ThemePalette) {
        dialog.window?.setBackgroundDrawable(
            GlassSurface.drawable(dialog.context, palette, GlassSurface.Variant.Strong, 28f)
        )
    }
}
