/*
 * AnkiDroid HALO V28 — estados visuales locales para mazos.
 * Esta función no modifica tarjetas, programación, FSRS ni datos sincronizados.
 */
package com.ichi2.anki.halo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.ichi2.anki.R
import com.ichi2.anki.libanki.DeckId

/** Estados oficiales aprobados para HALO V28. */
enum class HaloDeckStatus(
    val storageKey: String,
    @StringRes val labelRes: Int,
    @ColorInt val accentColor: Int,
) {
    NORMAL("normal", R.string.halo_deck_status_normal, Color.rgb(245, 247, 250)),
    URGENT("urgent", R.string.halo_deck_status_urgent, Color.rgb(229, 57, 53)),
    PENDING("pending", R.string.halo_deck_status_pending, Color.rgb(249, 168, 37)),
    IN_PROGRESS("in_progress", R.string.halo_deck_status_in_progress, Color.rgb(25, 118, 210)),
    LEARNED("learned", R.string.halo_deck_status_learned, Color.rgb(46, 125, 50)),
    SPECIAL_REVIEW("special_review", R.string.halo_deck_status_special_review, Color.rgb(126, 87, 194)),
    PAUSED("paused", R.string.halo_deck_status_paused, Color.rgb(120, 144, 156)),
    ;

    companion object {
        fun fromStorageKey(value: String?): HaloDeckStatus =
            values().firstOrNull { it.storageKey == value } ?: NORMAL
    }
}

/** Persistencia exclusivamente local y separada de la colección Anki. */
class HaloDeckStatusStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun get(deckId: DeckId): HaloDeckStatus =
        HaloDeckStatus.fromStorageKey(preferences.getString(deckKey(deckId), null))

    fun set(
        deckId: DeckId,
        status: HaloDeckStatus,
    ) {
        preferences.edit().putString(deckKey(deckId), status.storageKey).apply()
    }

    private fun deckKey(deckId: DeckId): String = "deck_$deckId"

    companion object {
        private const val PREFERENCES_NAME = "halo_deck_statuses_v1"
    }
}

/** Dibuja fondo tintado, borde tenue y franja lateral sin alterar la vista nativa. */
private class HaloDeckRowDrawable(
    @ColorInt private val accentColor: Int,
    private val selected: Boolean,
    private val density: Float,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var drawableAlpha: Int = 255

    override fun draw(canvas: Canvas) {
        val area = RectF(bounds)
        val radius = 8f * density
        val borderWidth = if (selected) 2f * density else 1f * density
        val stripeWidth = 4f * density

        fillPaint.color = ColorUtils.setAlphaComponent(accentColor, if (selected) 42 else 24)
        borderPaint.color = ColorUtils.setAlphaComponent(accentColor, if (selected) 180 else 90)
        borderPaint.strokeWidth = borderWidth
        stripePaint.color = ColorUtils.setAlphaComponent(accentColor, 235)

        fillPaint.alpha = fillPaint.alpha * drawableAlpha / 255
        borderPaint.alpha = borderPaint.alpha * drawableAlpha / 255
        stripePaint.alpha = stripePaint.alpha * drawableAlpha / 255

        canvas.drawRoundRect(area, radius, radius, fillPaint)
        val inset = borderWidth / 2f
        canvas.drawRoundRect(
            RectF(area.left + inset, area.top + inset, area.right - inset, area.bottom - inset),
            radius,
            radius,
            borderPaint,
        )

        val stripe =
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                RectF(area.right - stripeWidth, area.top, area.right, area.bottom)
            } else {
                RectF(area.left, area.top, area.left + stripeWidth, area.bottom)
            }
        canvas.drawRoundRect(stripe, radius / 2f, radius / 2f, stripePaint)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        stripePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Aplica el estándar visual accesible: color + texto del estado. */
object HaloDeckStatusVisuals {
    fun apply(
        context: Context,
        deckRow: View,
        deckNameView: TextView,
        deckName: String,
        status: HaloDeckStatus,
        selected: Boolean,
        @ColorInt defaultTextColor: Int,
    ) {
        val density = context.resources.displayMetrics.density
        val statusLabel = context.getString(status.labelRes)
        val statusTextColor = if (status == HaloDeckStatus.NORMAL) defaultTextColor else status.accentColor

        val text = SpannableStringBuilder(deckName)
        text.append('\n')
        val statusStart = text.length
        text.append(statusLabel)
        text.setSpan(RelativeSizeSpan(0.62f), statusStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(statusTextColor), statusStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), statusStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        deckNameView.maxLines = 2
        deckNameView.text = text

        val dotSize = (8f * density).toInt().coerceAtLeast(1)
        val dot =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (status == HaloDeckStatus.NORMAL) defaultTextColor else status.accentColor)
                setSize(dotSize, dotSize)
            }
        deckNameView.compoundDrawablePadding = (7f * density).toInt()
        deckNameView.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)

        val content = HaloDeckRowDrawable(status.accentColor, selected, density)
        val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(status.accentColor, 52))
        deckRow.background = RippleDrawable(ripple, content, null)
        deckRow.contentDescription = context.getString(R.string.halo_deck_accessibility_description, deckName, statusLabel)
    }
}
