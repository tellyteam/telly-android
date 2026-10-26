package io.github.tellyteam.telly.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A whole parcel (version 2): a section for each provider the device has, under its provider tag.
 * Never holds provider logins: a `logins` field from older apps is ignored. Reads version 1 parcels too, as one section for the provider the reader
 * says (its only one: with several it can't tell whose a version 1 parcel is).
 */
data class SyncBundle(
    val at: Long = 0,
    val sections: Map<String, SyncParcel> = emptyMap(),
) {
    fun toJson(): String = buildJsonObject {
        put("v", 2)
        put("at", at)
        put("providers", JsonObject(
            sections.entries.sortedByDescending { it.value.at }.take(MAX_SECTIONS)
                .associate { (tag, section) -> tag to SyncParcel.trim(section, at).sectionObject() },
        ))
    }.toString()

    companion object {
        const val MAX_SECTIONS = 20
        private val TAG = Regex("^[0-9a-f]{16}$")

        fun isTag(tag: String) = TAG.matches(tag)

        /** Another device's parcel. [current] is the tag a version 1 parcel is read as (null: it's ignored). */
        fun parse(text: String, current: String?): SyncBundle? {
            val raw = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val at = SyncParcel.time(raw["at"]) ?: 0
            return when (SyncParcel.time(raw["v"])) {
                1L -> SyncBundle(at, if (current == null) emptyMap() else mapOf(current to SyncParcel.parseSection(raw, at)))
                2L -> {
                    val sections = LinkedHashMap<String, SyncParcel>()
                    (raw["providers"] as? JsonObject)?.forEach { (tag, value) ->
                        val section = value as? JsonObject
                        if (isTag(tag) && section != null && sections.size < MAX_SECTIONS) sections[tag] = SyncParcel.parseSection(section, at)
                    }
                    SyncBundle(at, sections)
                }
                else -> null
            }
        }

        /** [own] combined with others, section by section, for the providers [own] has. */
        fun merge(own: SyncBundle, others: List<SyncBundle>, now: Long): SyncBundle =
            SyncBundle(now, own.sections.mapValues { (tag, section) -> SyncParcel.merge(section, others.mapNotNull { it.sections[tag] }, now) })
    }
}
