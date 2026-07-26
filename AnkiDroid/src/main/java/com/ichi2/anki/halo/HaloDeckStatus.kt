/*
 * Copyright (c) 2026 AnkiDroid HALO contributors
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.ichi2.anki.R
import com.ichi2.anki.libanki.DeckId
import org.json.JSONObject

enum class HaloDeckStatus(
    val storageKey: String,
    @StringRes val labelRes: Int,
    @ColorInt val accentColor: Int,
    val priority: Int,
) {
    NORMAL("normal", R.string.halo_deck_status_normal, Color.rgb(245, 247, 250), 0),
    URGENT("urgent", R.string.halo_deck_status_urgent, Color.rgb(229, 57, 53), 60),
    PENDING("pending", R.string.halo_deck_status_pending, Color.rgb(249, 168, 37), 50),
    IN_PROGRESS("in_progress", R.string.halo_deck_status_in_progress, Color.rgb(25, 118, 210), 40),
    SPECIAL_REVIEW("special_review", R.string.halo_deck_status_special_review, Color.rgb(126, 87, 194), 30),
    LEARNED("learned", R.string.halo_deck_status_learned, Color.rgb(46, 125, 50), 20),
    PAUSED("paused", R.string.halo_deck_status_paused, Color.rgb(120, 144, 156), 10),
    ;

    companion object {
        fun fromStorageKeyOrNull(value: String?): HaloDeckStatus? =
            entries.firstOrNull { it.storageKey == value }

        fun fromStorageKey(value: String?): HaloDeckStatus =
            fromStorageKeyOrNull(value) ?: NORMAL
    }
}

data class HaloDeckVisualSettings(
    val showStatusText: Boolean,
    val showDot: Boolean,
    val showTint: Boolean,
    val tintAlpha: Int,
)

enum class HaloDeckSortMode(val storageKey: String) {
    ORIGINAL("original"),
    NAME("name"),
    PRIORITY("priority"),
    PENDING("pending"),
    ;

    companion object {
        fun fromStorageKey(value: String?): HaloDeckSortMode =
            entries.firstOrNull { it.storageKey == value } ?: ORIGINAL
    }
}

data class HaloDeckOrganizationSettings(
    val statusFilter: HaloDeckStatus?,
    val sortMode: HaloDeckSortMode,
    val hideLearned: Boolean,
    val hidePaused: Boolean,
    val favoritesFirst: Boolean,
)

data class HaloDeckCleanupResult(
    val statusesRemoved: Int,
    val favoritesRemoved: Int,
)

class HaloDeckStatusStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyStatuses()
    }

    fun get(deckId: DeckId): HaloDeckStatus =
        HaloDeckStatus.fromStorageKey(preferences.getString(deckKey(deckId), null))

    fun isFavorite(deckId: DeckId): Boolean =
        preferences.getBoolean(favoriteKey(deckId), false)

    fun toggleFavorite(deckId: DeckId): Boolean {
        val favorite = !isFavorite(deckId)
        val editor = preferences.edit()
        if (favorite) {
            editor.putBoolean(favoriteKey(deckId), true)
        } else {
            editor.remove(favoriteKey(deckId))
        }
        editor.apply()
        return favorite
    }

    fun organizationSettings(): HaloDeckOrganizationSettings =
        HaloDeckOrganizationSettings(
            statusFilter =
                HaloDeckStatus.fromStorageKeyOrNull(
                    preferences.getString(FILTER_STATUS_KEY, null),
                ),
            sortMode =
                HaloDeckSortMode.fromStorageKey(
                    preferences.getString(SORT_MODE_KEY, null),
                ),
            hideLearned = preferences.getBoolean(HIDE_LEARNED_KEY, false),
            hidePaused = preferences.getBoolean(HIDE_PAUSED_KEY, false),
            favoritesFirst = preferences.getBoolean(FAVORITES_FIRST_KEY, true),
        )

    fun setStatusFilter(status: HaloDeckStatus?) {
        val editor = preferences.edit()
        if (status == null) {
            editor.remove(FILTER_STATUS_KEY)
        } else {
            editor.putString(FILTER_STATUS_KEY, status.storageKey)
        }
        editor.apply()
    }

    fun setSortMode(mode: HaloDeckSortMode) {
        preferences.edit().putString(SORT_MODE_KEY, mode.storageKey).apply()
    }

    fun setFunctionalVisibility(
        hideLearned: Boolean,
        hidePaused: Boolean,
        favoritesFirst: Boolean,
    ) {
        preferences.edit()
            .putBoolean(HIDE_LEARNED_KEY, hideLearned)
            .putBoolean(HIDE_PAUSED_KEY, hidePaused)
            .putBoolean(FAVORITES_FIRST_KEY, favoritesFirst)
            .apply()
    }

    fun cleanupOrphans(validDeckIds: Set<DeckId>): HaloDeckCleanupResult {
        val validIds = validDeckIds.map { it.toString() }.toSet()
        val statusKeys =
            preferences.all.keys.filter { key ->
                key.startsWith(DECK_PREFIX) &&
                    key.removePrefix(DECK_PREFIX) !in validIds
            }
        val favoriteKeys =
            preferences.all.keys.filter { key ->
                key.startsWith(FAVORITE_PREFIX) &&
                    key.removePrefix(FAVORITE_PREFIX) !in validIds
            }
        val editor = preferences.edit()
        statusKeys.forEach(editor::remove)
        favoriteKeys.forEach(editor::remove)
        editor.apply()
        return HaloDeckCleanupResult(
            statusesRemoved = statusKeys.size,
            favoritesRemoved = favoriteKeys.size,
        )
    }

    fun set(deckId: DeckId, status: HaloDeckStatus) = setMany(listOf(deckId), status)

    fun setMany(deckIds: Collection<DeckId>, status: HaloDeckStatus): Int {
        val ids = deckIds.distinct()
        if (ids.isEmpty()) return 0
        saveUndo(ids)
        val editor = preferences.edit()
        ids.forEach { editor.putString(deckKey(it), status.storageKey) }
        editor.apply()
        return ids.size
    }

    fun clearAllStatuses(): Int {
        val keys = preferences.all.keys.filter { it.startsWith(DECK_PREFIX) }
        if (keys.isEmpty()) return 0
        saveUndoKeys(keys)
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        editor.apply()
        return keys.size
    }

    fun undoLast(): Int {
        val raw = preferences.getString(UNDO_KEY, null) ?: return 0
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return 0
        val editor = preferences.edit()
        var changed = 0
        json.keys().forEach { key ->
            val value = json.optString(key, MISSING_VALUE)
            if (value == MISSING_VALUE) editor.remove(key) else editor.putString(key, value)
            changed++
        }
        editor.remove(UNDO_KEY).apply()
        return changed
    }

    fun exportJson(): String {
        val decks = JSONObject()
        val favorites = JSONObject()
        preferences.all.forEach { (key, value) ->
            when {
                key.startsWith(DECK_PREFIX) && value is String ->
                    decks.put(key.removePrefix(DECK_PREFIX), value)
                key.startsWith(FAVORITE_PREFIX) && value == true ->
                    favorites.put(key.removePrefix(FAVORITE_PREFIX), true)
            }
        }
        val visual = JSONObject().apply {
            val settings = visualSettings()
            put("showStatusText", settings.showStatusText)
            put("showDot", settings.showDot)
            put("showTint", settings.showTint)
            put("tintAlpha", settings.tintAlpha)
        }
        val organization = JSONObject().apply {
            val settings = organizationSettings()
            put("statusFilter", settings.statusFilter?.storageKey ?: "")
            put("sortMode", settings.sortMode.storageKey)
            put("hideLearned", settings.hideLearned)
            put("hidePaused", settings.hidePaused)
            put("favoritesFirst", settings.favoritesFirst)
        }
        return JSONObject().apply {
            put("format", "halo-deck-status-v3")
            put("decks", decks)
            put("favorites", favorites)
            put("visual", visual)
            put("organization", organization)
        }.toString(2)
    }

    fun importJson(raw: String): Int {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return -1
        val decks = root.optJSONObject("decks") ?: return -1
        val editor = preferences.edit()
        val currentStatusKeys = preferences.all.keys.filter { it.startsWith(DECK_PREFIX) }
        val currentFavoriteKeys = preferences.all.keys.filter { it.startsWith(FAVORITE_PREFIX) }
        saveUndoKeys(currentStatusKeys)
        currentStatusKeys.forEach(editor::remove)
        currentFavoriteKeys.forEach(editor::remove)
        var count = 0
        decks.keys().forEach { id ->
            val status = HaloDeckStatus.fromStorageKey(decks.optString(id, ""))
            editor.putString("$DECK_PREFIX$id", status.storageKey)
            count++
        }
        root.optJSONObject("favorites")?.let { favorites ->
            favorites.keys().forEach { id ->
                if (favorites.optBoolean(id, false)) {
                    editor.putBoolean("$FAVORITE_PREFIX$id", true)
                }
            }
        }
        root.optJSONObject("visual")?.let { visual ->
            editor.putBoolean(SHOW_TEXT_KEY, visual.optBoolean("showStatusText", true))
            editor.putBoolean(SHOW_DOT_KEY, visual.optBoolean("showDot", true))
            editor.putBoolean(SHOW_TINT_KEY, visual.optBoolean("showTint", true))
            editor.putInt(
                TINT_ALPHA_KEY,
                visual.optInt("tintAlpha", DEFAULT_TINT_ALPHA).coerceIn(0, 72),
            )
        }
        root.optJSONObject("organization")?.let { organization ->
            val statusFilter =
                HaloDeckStatus.fromStorageKeyOrNull(
                    organization.optString("statusFilter", ""),
                )
            if (statusFilter == null) {
                editor.remove(FILTER_STATUS_KEY)
            } else {
                editor.putString(FILTER_STATUS_KEY, statusFilter.storageKey)
            }
            editor.putString(
                SORT_MODE_KEY,
                HaloDeckSortMode
                    .fromStorageKey(organization.optString("sortMode", ""))
                    .storageKey,
            )
            editor.putBoolean(
                HIDE_LEARNED_KEY,
                organization.optBoolean("hideLearned", false),
            )
            editor.putBoolean(
                HIDE_PAUSED_KEY,
                organization.optBoolean("hidePaused", false),
            )
            editor.putBoolean(
                FAVORITES_FIRST_KEY,
                organization.optBoolean("favoritesFirst", true),
            )
        }
        editor.apply()
        return count
    }

    fun visualSettings(): HaloDeckVisualSettings =
        HaloDeckVisualSettings(
            showStatusText = preferences.getBoolean(SHOW_TEXT_KEY, true),
            showDot = preferences.getBoolean(SHOW_DOT_KEY, true),
            showTint = preferences.getBoolean(SHOW_TINT_KEY, true),
            tintAlpha = preferences.getInt(TINT_ALPHA_KEY, DEFAULT_TINT_ALPHA).coerceIn(0, 72),
        )

    fun setVisualSetting(key: String, enabled: Boolean) {
        val preferenceKey =
            when (key) {
                "text" -> SHOW_TEXT_KEY
                "dot" -> SHOW_DOT_KEY
                "tint" -> SHOW_TINT_KEY
                else -> return
            }
        preferences.edit().putBoolean(preferenceKey, enabled).apply()
    }

    fun setTintAlpha(alpha: Int) {
        preferences.edit().putInt(TINT_ALPHA_KEY, alpha.coerceIn(0, 72)).apply()
    }

    private fun migrateLegacyStatuses() {
        if (preferences.getBoolean(MIGRATION_V1_KEY, false)) return
        val legacy = appContext.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        legacy.all.forEach { (key, value) ->
            if (key.startsWith(DECK_PREFIX) && value is String && !preferences.contains(key)) {
                editor.putString(key, HaloDeckStatus.fromStorageKey(value).storageKey)
            }
        }
        editor.putBoolean(MIGRATION_V1_KEY, true).apply()
    }

    private fun saveUndo(deckIds: Collection<DeckId>) = saveUndoKeys(deckIds.map(::deckKey))

    private fun saveUndoKeys(keys: Collection<String>) {
        val json = JSONObject()
        keys.distinct().forEach { key ->
            json.put(key, preferences.getString(key, null) ?: MISSING_VALUE)
        }
        preferences.edit().putString(UNDO_KEY, json.toString()).apply()
    }

    private fun deckKey(deckId: DeckId): String = "$DECK_PREFIX$deckId"

    private fun favoriteKey(deckId: DeckId): String = "$FAVORITE_PREFIX$deckId"

    companion object {
        private const val PREFERENCES_NAME = "halo_deck_statuses_v2"
        private const val LEGACY_PREFERENCES_NAME = "halo_deck_statuses_v1"
        private const val MIGRATION_V1_KEY = "migration_v1_complete"
        private const val DECK_PREFIX = "deck_"
        private const val FAVORITE_PREFIX = "favorite_"
        private const val UNDO_KEY = "last_undo"
        private const val MISSING_VALUE = "__HALO_MISSING__"
        private const val SHOW_TEXT_KEY = "visual_show_text"
        private const val SHOW_DOT_KEY = "visual_show_dot"
        private const val SHOW_TINT_KEY = "visual_show_tint"
        private const val TINT_ALPHA_KEY = "visual_tint_alpha"
        private const val FILTER_STATUS_KEY = "organization_status_filter"
        private const val SORT_MODE_KEY = "organization_sort_mode"
        private const val HIDE_LEARNED_KEY = "organization_hide_learned"
        private const val HIDE_PAUSED_KEY = "organization_hide_paused"
        private const val FAVORITES_FIRST_KEY = "organization_favorites_first"
        private const val DEFAULT_TINT_ALPHA = 24
    }
}

private class HaloDeckRowDrawable(
    @ColorInt private val accentColor: Int,
    private val selected: Boolean,
    private val density: Float,
    private val fillAlpha: Int,
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
        fillPaint.color = ColorUtils.setAlphaComponent(accentColor, if (selected) (fillAlpha + 16).coerceAtMost(88) else fillAlpha)
        borderPaint.color = ColorUtils.setAlphaComponent(accentColor, if (selected) 180 else 90)
        borderPaint.strokeWidth = borderWidth
        stripePaint.color = ColorUtils.setAlphaComponent(accentColor, 235)
        fillPaint.alpha = fillPaint.alpha * drawableAlpha / 255
        borderPaint.alpha = borderPaint.alpha * drawableAlpha / 255
        stripePaint.alpha = stripePaint.alpha * drawableAlpha / 255
        canvas.drawRoundRect(area, radius, radius, fillPaint)
        val inset = borderWidth / 2f
        canvas.drawRoundRect(RectF(area.left + inset, area.top + inset, area.right - inset, area.bottom - inset), radius, radius, borderPaint)
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

object HaloDeckStatusVisuals {
    fun apply(
        context: Context,
        deckRow: View,
        deckNameView: TextView,
        deckName: String,
        status: HaloDeckStatus,
        selected: Boolean,
        @ColorInt defaultTextColor: Int,
        settings: HaloDeckVisualSettings,
    ) {
        val density = context.resources.displayMetrics.density
        val statusLabel = context.getString(status.labelRes)
        val statusTextColor = if (status == HaloDeckStatus.NORMAL) defaultTextColor else status.accentColor
        if (settings.showStatusText) {
            val text = SpannableStringBuilder(deckName)
            text.append('\n')
            val start = text.length
            text.append(statusLabel)
            text.setSpan(RelativeSizeSpan(0.62f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(statusTextColor), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            deckNameView.maxLines = 2
            deckNameView.text = text
        } else {
            deckNameView.maxLines = 1
            deckNameView.text = deckName
        }
        if (settings.showDot) {
            val dotSize = (8f * density).toInt().coerceAtLeast(1)
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (status == HaloDeckStatus.NORMAL) defaultTextColor else status.accentColor)
                setSize(dotSize, dotSize)
            }
            deckNameView.compoundDrawablePadding = (7f * density).toInt()
            deckNameView.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        } else {
            deckNameView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        }
        val fillAlpha = if (settings.showTint) settings.tintAlpha else 0
        val content = HaloDeckRowDrawable(status.accentColor, selected, density, fillAlpha)
        val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(status.accentColor, 52))
        deckRow.background = RippleDrawable(ripple, content, null)
        deckRow.contentDescription = context.getString(R.string.halo_deck_accessibility_description, deckName, statusLabel)
    }
}
