// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Forced auto-corrections: a user-defined list of trigger -> replacement pairs that are
 * auto-corrected even when auto-correction is off.
 *
 * Matching is exact (after normalization) and whole-word only: the typed word must match a
 * trigger, nothing else. The list is stored as a JSON map in SharedPreferences, following the
 * same pattern as the custom icon names ([customIconNames]).
 */
object ForcedAutocorrection {

    /** Normalize a trigger for lookup: trim, case-insensitive, Arabic Yeh/Kaf -> Persian Yeh/Kaf. */
    @JvmStatic
    fun normalizeTrigger(word: String): String = word.trim()
        .lowercase(Locale.ROOT)
        .replace('\u064A', '\u06CC') // Arabic Yeh -> Persian Yeh
        .replace('\u0643', '\u06A9') // Arabic Kaf -> Persian Kaf

    /** The stored trigger -> replacement map, with normalized triggers and blank entries removed. */
    @JvmStatic
    fun load(prefs: SharedPreferences): Map<String, String> {
        val raw = runCatching {
            Json.decodeFromString<Map<String, String>>(
                prefs.getString(Settings.PREF_FORCED_AUTOCORRECTIONS, Defaults.PREF_FORCED_AUTOCORRECTIONS)!!
            )
        }.getOrElse { return emptyMap() }
        val normalized = LinkedHashMap<String, String>()
        raw.forEach { (trigger, replacement) ->
            val key = normalizeTrigger(trigger)
            if (key.isNotEmpty() && replacement.isNotBlank()) {
                normalized[key] = replacement
            }
        }
        return normalized
    }

    @JvmStatic
    fun save(prefs: SharedPreferences, replacements: Map<String, String>) {
        val normalized = LinkedHashMap<String, String>()
        replacements.forEach { (trigger, replacement) ->
            val key = normalizeTrigger(trigger)
            if (key.isNotEmpty() && replacement.isNotBlank()) {
                normalized[key] = replacement
            }
        }
        prefs.edit { putString(Settings.PREF_FORCED_AUTOCORRECTIONS, Json.encodeToString<Map<String, String>>(normalized)) }
    }

    /** Normalized triggers the user disabled individually (the rest of the list stays enabled). */
    @JvmStatic
    fun disabledTriggers(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(Settings.PREF_FORCED_AUTOCORRECTION_DISABLED, emptySet())?.mapTo(HashSet()) { normalizeTrigger(it) } ?: emptySet()

    @JvmStatic
    fun setTriggerDisabled(prefs: SharedPreferences, trigger: String, disabled: Boolean) {
        val normalized = normalizeTrigger(trigger)
        val current = disabledTriggers(prefs).toMutableSet()
        if (disabled) current.add(normalized) else current.remove(normalized)
        prefs.edit { putStringSet(Settings.PREF_FORCED_AUTOCORRECTION_DISABLED, current) }
    }

    /**
     * Look up the replacement for a typed word, or null if there is none.
     * Returns null when the feature is disabled (master switch or per-entry), when the typed
     * word has no trigger, or when the replacement is identical to the typed word.
     */
    @JvmStatic
    fun lookup(
        enabled: Boolean,
        replacements: Map<String, String>,
        disabled: Set<String>,
        typedWord: String
    ): String? {
        if (!enabled || typedWord.isEmpty()) return null
        val trigger = normalizeTrigger(typedWord)
        if (trigger in disabled) return null
        val replacement = replacements[trigger] ?: return null
        if (replacement == typedWord) return null
        return replacement
    }
}
