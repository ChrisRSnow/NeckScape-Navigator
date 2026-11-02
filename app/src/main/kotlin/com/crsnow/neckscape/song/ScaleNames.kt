package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.Scales

/**
 * The scale names payloads may use (published by get_app_info): every scale in
 * the catalogue as snake_case ("dorian", "minor_pentatonic", "ionian_major"),
 * plus everyday aliases ("major", "minor", "blues").
 */
object ScaleNames {
    private val ALIASES = mapOf(
        "major" to "ionian_major", "ionian" to "ionian_major",
        "minor" to "aeolian_natural_minor", "natural_minor" to "aeolian_natural_minor", "aeolian" to "aeolian_natural_minor",
        "melodic_minor" to "melodic_minor_jazz_minor", "jazz_minor" to "melodic_minor_jazz_minor",
        "blues" to "minor_blues", "altered" to "altered_super_locrian", "super_locrian" to "altered_super_locrian",
        "half_whole_diminished" to "diminished_half_whole", "whole_half_diminished" to "diminished_whole_half",
    )

    fun key(name: String): String = name.lowercase()
        .replace("♭", "b").replace("♯", "#")
        .replace("#", "sharp")
        .replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** snake_case name → (family, scale) index; first occurrence wins. */
    val all: Map<String, Pair<Int, Int>> by lazy {
        val out = LinkedHashMap<String, Pair<Int, Int>>()
        Scales.families.forEachIndexed { f, family ->
            family.scales.forEachIndexed { s, scale -> out.putIfAbsent(key(scale.name), f to s) }
        }
        for ((alias, target) in ALIASES) out[target]?.let { out.putIfAbsent(alias, it) }
        out
    }

    fun lookup(name: String): Pair<Int, Int>? = all[key(name)] ?: all[ALIASES[key(name)] ?: ""]
}
