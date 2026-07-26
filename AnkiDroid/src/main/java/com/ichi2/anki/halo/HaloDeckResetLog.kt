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
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class HaloDeckResetLog(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun append(
        deckId: Long,
        deckName: String,
        includeSubdecks: Boolean,
        subdeckCount: Int,
        cardCount: Int,
    ) {
        val history =
            runCatching {
                JSONArray(preferences.getString(KEY_HISTORY, "[]") ?: "[]")
            }.getOrDefault(JSONArray())

        history.put(
            JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("deckId", deckId)
                .put("deckName", deckName)
                .put("includeSubdecks", includeSubdecks)
                .put("subdeckCount", subdeckCount)
                .put("cardCount", cardCount)
                .put("backupCreated", true)
                .put("restoreOriginalPosition", true)
                .put("resetRepetitionAndLapseCounts", true),
        )

        while (history.length() > MAX_RECORDS) {
            history.remove(0)
        }

        preferences.edit {
            putString(KEY_HISTORY, history.toString())
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "halo_deck_reset_history"
        const val KEY_HISTORY = "history"
        const val MAX_RECORDS = 50
    }
}
