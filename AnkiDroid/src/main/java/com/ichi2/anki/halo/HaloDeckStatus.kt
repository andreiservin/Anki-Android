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
    val onlyFavorites: Boolean,
    val onlyPinned: Boolean,
    val onlyProtected: Boolean,
)

data class HaloDeckCleanupResult(
    val statusesRemoved: Int,
    val favoritesRemoved: Int,
    val pinnedRemoved: Int,
    val protectedRemoved: Int,
    val lastDeckReferencesRemoved: Int,
)

data class HaloLastStudiedDeck(
    val deckId: DeckId,
    val deckName: String,
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

    fun toggleFavorite(deckId: DeckId): Boolean =
        toggleBooleanFlag(favoriteKey(deckId), isFavorite(deckId))

    fun isPinned(deckId: DeckId): Boolean =
        preferences.getBoolean(pinnedKey(deckId), false)

    fun togglePinned(deckId: DeckId): Boolean =
        toggleBooleanFlag(pinnedKey(deckId), isPinned(deckId))

    fun isProtected(deckId: DeckId): Boolean =
        preferences.getBoolean(protectedKey(deckId), false)

    fun toggleProtected(deckId: DeckId): Boolean =
        toggleBooleanFlag(protectedKey(deckId), isProtected(deckId))

    fun hasProtectedDecks(): Boolean =
        preferences.all.any { (key, value) ->
            key.startsWith(PROTECTED_PREFIX) && value == true
        }

    fun rememberLastStudiedDeck(
        deckId: DeckId,
        deckName: String,
    ) {
        preferences.edit()
            .putLong(LAST_DECK_ID_KEY, deckId)
            .putString(LAST_DECK_NAME_KEY, deckName)
            .commit()
    }

    fun lastStudiedDeck(): HaloLastStudiedDeck? {
        if (!preferences.contains(LAST_DECK_ID_KEY)) return null
        val deckId = preferences.getLong(LAST_DECK_ID_KEY, 0L)
        if (deckId <= 0L) return null
        return HaloLastStudiedDeck(
            deckId = deckId,
            deckName = preferences.getString(LAST_DECK_NAME_KEY, null).orEmpty(),
        )
    }

    fun clearLastStudiedDeck() {
        preferences.edit()
            .remove(LAST_DECK_ID_KEY)
            .remove(LAST_DECK_NAME_KEY)
            .commit()
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
            onlyFavorites = preferences.getBoolean(ONLY_FAVORITES_KEY, false),
            onlyPinned = preferences.getBoolean(ONLY_PINNED_KEY, false),
            onlyProtected = preferences.getBoolean(ONLY_PROTECTED_KEY, false),
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

    fun setSpecialFilters(
        onlyFavorites: Boolean,
        onlyPinned: Boolean,
        onlyProtected: Boolean,
    ) {
        preferences.edit()
            .putBoolean(ONLY_FAVORITES_KEY, onlyFavorites)
            .putBoolean(ONLY_PINNED_KEY, onlyPinned)
            .putBoolean(ONLY_PROTECTED_KEY, onlyProtected)
            .apply()
    }

    fun cleanupOrphans(validDeckIds: Set<DeckId>): HaloDeckCleanupResult {
        val validIds = validDeckIds.map { it.toString() }.toSet()
        fun orphanKeys(prefix: String): List<String> =
            preferences.all.keys.filter { key ->
                key.startsWith(prefix) && key.removePrefix(prefix) !in validIds
            }

        val statusKeys = orphanKeys(DECK_PREFIX)
        val favoriteKeys = orphanKeys(FAVORITE_PREFIX)
        val pinnedKeys = orphanKeys(PINNED_PREFIX)
        val protectedKeys = orphanKeys(PROTECTED_PREFIX)
        val lastDeckId = preferences.getLong(LAST_DECK_ID_KEY, 0L)
        val clearLastDeck = lastDeckId > 0L && lastDeckId.toString() !in validIds
        val editor = preferences.edit()
        statusKeys.forEach(editor::remove)
        favoriteKeys.forEach(editor::remove)
        pinnedKeys.forEach(editor::remove)
        protectedKeys.forEach(editor::remove)
        if (clearLastDeck) {
            editor.remove(LAST_DECK_ID_KEY)
            editor.remove(LAST_DECK_NAME_KEY)
        }
        editor.apply()
        return HaloDeckCleanupResult(
            statusesRemoved = statusKeys.size,
            favoritesRemoved = favoriteKeys.size,
            pinnedRemoved = pinnedKeys.size,
            protectedRemoved = protectedKeys.size,
            lastDeckReferencesRemoved = if (clearLastDeck) 1 else 0,
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
        val pinned = JSONObject()
        val protectedDecks = JSONObject()
        preferences.all.forEach { (key, value) ->
            when {
                key.startsWith(DECK_PREFIX) && value is String ->
                    decks.put(key.removePrefix(DECK_PREFIX), value)
                key.startsWith(FAVORITE_PREFIX) && value == true ->
                    favorites.put(key.removePrefix(FAVORITE_PREFIX), true)
                key.startsWith(PINNED_PREFIX) && value == true ->
                    pinned.put(key.removePrefix(PINNED_PREFIX), true)
                key.startsWith(PROTECTED_PREFIX) && value == true ->
                    protectedDecks.put(key.removePrefix(PROTECTED_PREFIX), true)
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
            put("onlyFavorites", settings.onlyFavorites)
            put("onlyPinned", settings.onlyPinned)
            put("onlyProtected", settings.onlyProtected)
        }
        val lastDeck = JSONObject().apply {
            lastStudiedDeck()?.let { deck ->
                put("id", deck.deckId)
                put("name", deck.deckName)
            }
        }
        return JSONObject().apply {
            put("format", "halo-deck-status-v4")
            put("decks", decks)
            put("favorites", favorites)
            put("pinned", pinned)
            put("protected", protectedDecks)
            put("visual", visual)
            put("organization", organization)
            put("lastStudiedDeck", lastDeck)
        }.toString(2)
    }

    fun importJson(raw: String): Int {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return -1
        val decks = root.optJSONObject("decks") ?: return -1
        val editor = preferences.edit()
        val currentStatusKeys = preferences.all.keys.filter { it.startsWith(DECK_PREFIX) }
        val currentFavoriteKeys = preferences.all.keys.filter { it.startsWith(FAVORITE_PREFIX) }
        val currentPinnedKeys = preferences.all.keys.filter { it.startsWith(PINNED_PREFIX) }
        val currentProtectedKeys = preferences.all.keys.filter { it.startsWith(PROTECTED_PREFIX) }
        saveUndoKeys(currentStatusKeys)
        currentStatusKeys.forEach(editor::remove)
        currentFavoriteKeys.forEach(editor::remove)
        currentPinnedKeys.forEach(editor::remove)
        currentProtectedKeys.forEach(editor::remove)
        var count = 0
        decks.keys().forEach { id ->
            val status = HaloDeckStatus.fromStorageKey(decks.optString(id, ""))
            editor.putString("$DECK_PREFIX$id", status.storageKey)
            count++
        }
        importBooleanDeckMap(root.optJSONObject("favorites"), FAVORITE_PREFIX, editor)
        importBooleanDeckMap(root.optJSONObject("pinned"), PINNED_PREFIX, editor)
        importBooleanDeckMap(root.optJSONObject("protected"), PROTECTED_PREFIX, editor)
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
            editor.putBoolean(
                ONLY_FAVORITES_KEY,
                organization.optBoolean("onlyFavorites", false),
            )
            editor.putBoolean(
                ONLY_PINNED_KEY,
                organization.optBoolean("onlyPinned", false),
            )
            editor.putBoolean(
                ONLY_PROTECTED_KEY,
                organization.optBoolean("onlyProtected", false),
            )
        }
        val lastDeck = root.optJSONObject("lastStudiedDeck")
        val lastDeckId = lastDeck?.optLong("id", 0L) ?: 0L
        if (lastDeckId > 0L) {
            editor.putLong(LAST_DECK_ID_KEY, lastDeckId)
            editor.putString(LAST_DECK_NAME_KEY, lastDeck?.optString("name", "").orEmpty())
        } else {
            editor.remove(LAST_DECK_ID_KEY)
            editor.remove(LAST_DECK_NAME_KEY)
        }
        editor.apply()
        return count
    }

    private fun importBooleanDeckMap(
        json: JSONObject?,
        prefix: String,
        editor: android.content.SharedPreferences.Editor,
    ) {
        json ?: return
        json.keys().forEach { id ->
            if (json.optBoolean(id, false)) editor.putBoolean("$prefix$id", true)
        }
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

    private fun toggleBooleanFlag(
        key: String,
        currentValue: Boolean,
    ): Boolean {
        val newValue = !currentValue
        val editor = preferences.edit()
        if (newValue) editor.putBoolean(key, true) else editor.remove(key)
        editor.commit()
        return newValue
    }

    private fun deckKey(deckId: DeckId): String = "$DECK_PREFIX$deckId"

    private fun favoriteKey(deckId: DeckId): String = "$FAVORITE_PREFIX$deckId"

    private fun pinnedKey(deckId: DeckId): String = "$PINNED_PREFIX$deckId"

    private fun protectedKey(deckId: DeckId): String = "$PROTECTED_PREFIX$deckId"

    companion object {
        private const val PREFERENCES_NAME = "halo_deck_statuses_v2"
        private const val LEGACY_PREFERENCES_NAME = "halo_deck_statuses_v1"
        private const val MIGRATION_V1_KEY = "migration_v1_complete"
        private const val DECK_PREFIX = "deck_"
        private const val FAVORITE_PREFIX = "favorite_"
        private const val PINNED_PREFIX = "pinned_"
        private const val PROTECTED_PREFIX = "protected_"
        private const val LAST_DECK_ID_KEY = "last_studied_deck_id"
        private const val LAST_DECK_NAME_KEY = "last_studied_deck_name"
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
        private const val ONLY_FAVORITES_KEY = "organization_only_favorites"
        private const val ONLY_PINNED_KEY = "organization_only_pinned"
        private const val ONLY_PROTECTED_KEY = "organization_only_protected"
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


private class HaloDeckMarkersDrawable(
    @ColorInt private val statusColor: Int,
    private val showStatusDot: Boolean,
    private val pinned: Boolean,
    private val isProtected: Boolean,
    private val density: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconSize = 10f * density
    private val iconGap = 4f * density
    private val iconCount = listOf(showStatusDot, pinned, isProtected).count { it }
    private var drawableAlpha = 255

    override fun getIntrinsicWidth(): Int =
        if (iconCount == 0) {
            0
        } else {
            (iconCount * iconSize + (iconCount - 1) * iconGap).toInt()
        }

    override fun getIntrinsicHeight(): Int = (12f * density).toInt()

    override fun draw(canvas: Canvas) {
        if (iconCount == 0) return
        var centerX = bounds.left + iconSize / 2f
        val centerY = bounds.exactCenterY()

        fun advance() {
            centerX += iconSize + iconGap
        }

        if (showStatusDot) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(statusColor)
            canvas.drawCircle(centerX, centerY, 4f * density, paint)
            advance()
        }
        if (pinned) {
            drawPin(canvas, centerX, centerY, Color.rgb(38, 198, 218))
            advance()
        }
        if (isProtected) {
            drawLock(canvas, centerX, centerY, Color.rgb(207, 216, 220))
        }
    }

    private fun drawPin(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        @ColorInt color: Int,
    ) {
        paint.color = withAlpha(color)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY - 2.4f * density, 2.6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            centerX,
            centerY,
            centerX,
            centerY + 5f * density,
            paint,
        )
        canvas.drawLine(
            centerX - 2.8f * density,
            centerY + 0.4f * density,
            centerX + 2.8f * density,
            centerY + 0.4f * density,
            paint,
        )
    }

    private fun drawLock(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        @ColorInt color: Int,
    ) {
        paint.color = withAlpha(color)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f * density
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(
            RectF(
                centerX - 3f * density,
                centerY - 5f * density,
                centerX + 3f * density,
                centerY + 1f * density,
            ),
            180f,
            180f,
            false,
            paint,
        )
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            RectF(
                centerX - 4f * density,
                centerY,
                centerX + 4f * density,
                centerY + 5f * density,
            ),
            1.2f * density,
            1.2f * density,
            paint,
        )
    }

    private fun withAlpha(@ColorInt color: Int): Int =
        ColorUtils.setAlphaComponent(color, Color.alpha(color) * drawableAlpha / 255)

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
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
        favorite: Boolean,
        pinned: Boolean,
        isProtected: Boolean,
        selected: Boolean,
        @ColorInt defaultTextColor: Int,
        settings: HaloDeckVisualSettings,
    ) {
        val density = context.resources.displayMetrics.density
        val statusLabel = context.getString(status.labelRes)
        val statusTextColor = if (status == HaloDeckStatus.NORMAL) defaultTextColor else status.accentColor
        val text = SpannableStringBuilder(deckName)
        if (favorite) {
            val favoriteStart = text.length
            text.append(" ★")
            text.setSpan(
                ForegroundColorSpan(Color.rgb(255, 193, 7)),
                favoriteStart,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text.setSpan(
                RelativeSizeSpan(0.82f),
                favoriteStart,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text.setSpan(
                StyleSpan(Typeface.BOLD),
                favoriteStart,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (settings.showStatusText) {
            text.append('\n')
            val start = text.length
            text.append(statusLabel)
            text.setSpan(RelativeSizeSpan(0.62f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(statusTextColor), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            deckNameView.maxLines = 2
        } else {
            deckNameView.maxLines = 1
        }
        deckNameView.text = text
        val statusDotColor =
            if (status == HaloDeckStatus.NORMAL) defaultTextColor else status.accentColor
        val markers =
            HaloDeckMarkersDrawable(
                statusColor = statusDotColor,
                showStatusDot = settings.showDot,
                pinned = pinned,
                isProtected = isProtected,
                density = density,
            )
        if (markers.intrinsicWidth > 0) {
            markers.setBounds(0, 0, markers.intrinsicWidth, markers.intrinsicHeight)
            deckNameView.compoundDrawablePadding = (7f * density).toInt()
            deckNameView.setCompoundDrawablesRelative(markers, null, null, null)
        } else {
            deckNameView.setCompoundDrawablesRelative(null, null, null, null)
        }
        val fillAlpha = if (settings.showTint) settings.tintAlpha else 0
        val content = HaloDeckRowDrawable(status.accentColor, selected, density, fillAlpha)
        val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(status.accentColor, 52))
        deckRow.background = RippleDrawable(ripple, content, null)
        val markerLabels =
            buildList {
                if (favorite) add(context.getString(R.string.halo_marker_favorite))
                if (pinned) add(context.getString(R.string.halo_marker_pinned))
                if (isProtected) add(context.getString(R.string.halo_marker_protected))
            }.joinToString(", ")
        deckRow.contentDescription =
            if (markerLabels.isEmpty()) {
                context.getString(
                    R.string.halo_deck_accessibility_description,
                    deckName,
                    statusLabel,
                )
            } else {
                context.getString(
                    R.string.halo_deck_accessibility_description_with_markers,
                    deckName,
                    statusLabel,
                    markerLabels,
                )
            }
    }
}
