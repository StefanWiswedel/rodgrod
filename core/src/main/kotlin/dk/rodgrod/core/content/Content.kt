package dk.rodgrod.core.content

import org.json.JSONArray
import org.json.JSONObject

enum class ItemType(val json: String) {
    WORD("word"), MINIMAL_PAIR("minimal_pair"), PHRASE("phrase");

    companion object {
        fun parse(s: String): ItemType? = entries.firstOrNull { it.json == s }
    }
}

data class PairMember(val danish: String, val english: String)

data class Item(
    val id: String,
    val danish: String,
    val english: String,
    val targetSounds: List<String>,
    val level: Int,
    val type: ItemType,
    val tipId: String,
    val audioOverride: String? = null,
    /** Only for minimal pairs: exactly two members. */
    val pair: List<PairMember>? = null,
    val category: String? = null,
) {
    val isProduction get() = type != ItemType.MINIMAL_PAIR

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("danish", danish).put("english", english)
        .put("target_sounds", JSONArray(targetSounds)).put("level", level).put("type", type.json)
        .put("tip_id", tipId)
        .apply {
            audioOverride?.let { put("audio_override", it) }
            category?.let { put("category", it) }
            pair?.let { p -> put("pair", JSONArray(p.map { JSONObject().put("danish", it.danish).put("english", it.english) })) }
        }
}

data class Tip(val sound: String, val tipId: String, val name: String, val text: String)

data class Content(val deckId: String, val deckVersion: Int, val items: List<Item>, val tips: Map<String, Tip>) {
    val byId: Map<String, Item> = items.associateBy { it.id }
    fun tipForSound(sound: String): Tip? = tips[sound]
    fun soundName(sound: String): String = tips[sound]?.name ?: sound.replace('_', ' ')
}

class ContentException(val errors: List<String>) : Exception("Invalid content:\n" + errors.joinToString("\n"))

object ContentLoader {
    const val MAX_TIP_WORDS = 26
    const val MAX_LEVEL = 3
    private val ID_RE = Regex("[a-z0-9_]+")
    private val AUDIO_RE = Regex("[A-Za-z0-9_./-]+\\.(mp3|wav|ogg|m4a)")

    fun parseTips(json: String): Map<String, Tip> {
        val sounds = JSONObject(json).getJSONObject("sounds")
        return sounds.keys().asSequence().associateWith { k ->
            val o = sounds.getJSONObject(k)
            Tip(k, o.optString("tip_id"), o.optString("name"), o.optString("text"))
        }
    }

    /** Parses a deck leniently, collecting errors instead of throwing, so one bad item can be reported precisely. */
    fun parseDeck(json: String, errors: MutableList<String>): Triple<String, Int, List<Item>> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("items")
        val items = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { errors += "items[$i]: not an object"; continue }
            val id = o.optString("id", "")
            val where = "item '${id.ifEmpty { "#$i" }}'"
            val type = ItemType.parse(o.optString("type"))
            if (type == null) { errors += "$where: invalid type '${o.optString("type")}'"; continue }
            val soundsArr = o.optJSONArray("target_sounds")
            val sounds = if (soundsArr == null) emptyList() else (0 until soundsArr.length()).map { soundsArr.optString(it) }
            val pairArr = o.optJSONArray("pair")
            val pair = pairArr?.let { p ->
                (0 until p.length()).mapNotNull { j -> p.optJSONObject(j)?.let { PairMember(it.optString("danish"), it.optString("english")) } }
            }
            val level = o.opt("level")
            if (level !is Int) { errors += "$where: level must be an integer" }
            items += Item(
                id = id,
                danish = o.optString("danish", ""),
                english = o.optString("english", ""),
                targetSounds = sounds,
                level = (level as? Int) ?: 0,
                type = type,
                tipId = o.optString("tip_id", ""),
                audioOverride = o.optString("audio_override", "").ifBlank { null },
                pair = pair,
                category = o.optString("category", "").ifBlank { null },
            )
        }
        return Triple(root.optString("deck_id", "deck"), root.optInt("version", 1), items)
    }

    fun validate(items: List<Item>, tips: Map<String, Tip>): List<String> {
        val errors = mutableListOf<String>()
        val tipIds = tips.values.associateBy { it.tipId }
        for ((sound, tip) in tips) {
            if (tip.tipId.isBlank()) errors += "tip '$sound': missing tip_id"
            if (tip.name.isBlank()) errors += "tip '$sound': missing name"
            val words = tip.text.split(Regex("\\s+")).count { it.isNotBlank() }
            if (words == 0) errors += "tip '$sound': empty text"
            if (words > MAX_TIP_WORDS) errors += "tip '$sound': $words words (max $MAX_TIP_WORDS, to stay under ~10 s spoken)"
        }
        val seen = HashSet<String>()
        for (it in items) {
            val w = "item '${it.id}'"
            if (!ID_RE.matches(it.id)) errors += "$w: id must match ${ID_RE.pattern}"
            if (!seen.add(it.id)) errors += "$w: duplicate id"
            if (it.danish.isBlank()) errors += "$w: danish is empty"
            if (it.english.isBlank()) errors += "$w: english is empty"
            if (it.level !in 1..MAX_LEVEL) errors += "$w: level ${it.level} not in 1..$MAX_LEVEL"
            if (it.targetSounds.isEmpty()) errors += "$w: no target_sounds"
            if (it.targetSounds.toSet().size != it.targetSounds.size) errors += "$w: duplicate target_sounds"
            for (s in it.targetSounds) if (s !in tips) errors += "$w: unknown target sound '$s'"
            val tip = tipIds[it.tipId]
            if (tip == null) errors += "$w: unknown tip_id '${it.tipId}'"
            else if (tip.sound !in it.targetSounds) errors += "$w: tip_id '${it.tipId}' is for '${tip.sound}', which is not one of its target sounds"
            it.audioOverride?.let { a ->
                if (!AUDIO_RE.matches(a) || a.contains("..") || a.startsWith("/")) errors += "$w: audio_override '$a' must be a relative audio file path"
            }
            if (it.type == ItemType.MINIMAL_PAIR) {
                val p = it.pair
                if (p == null || p.size != 2) errors += "$w: minimal_pair needs exactly 2 pair members"
                else {
                    if (p.any { m -> m.danish.isBlank() || m.english.isBlank() }) errors += "$w: pair members need danish and english"
                    if (p[0].danish.equals(p[1].danish, ignoreCase = true)) errors += "$w: pair members are identical"
                    if (it.targetSounds.size != 1) errors += "$w: minimal_pair should target exactly one sound contrast"
                }
            } else if (it.pair != null) errors += "$w: only minimal_pair items may have 'pair'"
        }
        return errors
    }

    /** Parse and validate; throws [ContentException] listing every problem. */
    fun load(deckJson: String, tipsJson: String): Content {
        val errors = mutableListOf<String>()
        val tips = parseTips(tipsJson)
        val (deckId, version, items) = parseDeck(deckJson, errors)
        errors += validate(items, tips)
        if (errors.isNotEmpty()) throw ContentException(errors)
        return Content(deckId, version, items, tips)
    }
}
