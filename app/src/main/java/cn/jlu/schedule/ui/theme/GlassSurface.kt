package cn.jlu.schedule.ui.theme

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils

/** Shared drawable factory for the app's restrained liquid-glass surfaces. */
object GlassSurface {
    enum class Variant { Panel, Strong, Control, Navigation }

    fun drawable(
        context: Context,
        palette: ThemePalette,
        variant: Variant = Variant.Panel,
        radiusDp: Float = when (variant) {
            Variant.Navigation -> 24f
            Variant.Control -> 18f
            else -> 24f
        }
    ): GradientDrawable {
        val base = when (variant) {
            Variant.Strong -> palette.glassSurfaceStrong
            Variant.Control -> palette.buttonBackground
            else -> palette.glassSurface
        }
        val alpha = when (variant) {
            Variant.Strong -> if (palette.isDark) 0xE8 else 0xD9
            Variant.Control -> if (palette.isDark) 0xD8 else 0xC9
            Variant.Navigation -> if (palette.isDark) 0xE0 else 0xD0
            Variant.Panel -> if (palette.isDark) 0xD0 else 0xB8
        }
        val fill = ColorUtils.setAlphaComponent(base, alpha)
        val stroke = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(palette.glassStroke, palette.glassHighlight, 0.35f),
            if (palette.isDark) 0x88 else 0x70
        )
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            setColor(fill)
            setStroke(context.resources.displayMetrics.density.coerceAtLeast(1f).toInt(), stroke)
        }
    }

    fun apply(view: View, palette: ThemePalette, variant: Variant = Variant.Panel, radiusDp: Float? = null) {
        view.background = drawable(view.context, palette, variant, radiusDp ?: when (variant) {
            Variant.Navigation -> 24f
            Variant.Control -> 18f
            else -> 24f
        })
    }
}
