package com.piercingxx.calendar.settings

import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.SigilTier
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * A parsed backup: exactly what §9 restores — the settings store and the
 * sigil map, never events (those are `.ics`, the provider's domain).
 */
data class BackupSnapshot(val settings: Settings, val sigils: Map<CalendarKey, SigilTier>)

sealed interface BackupRead {
    data class Ok(val snapshot: BackupSnapshot) : BackupRead
    data object Corrupt : BackupRead
}

/**
 * The §9 backup format: hand-rolled deterministic JSON of the sixteen
 * survivors plus the filter-fidelity switch (key names are the DataStore
 * preference names, so the file doubles as documentation) and the sigil map
 * as `{calendarId, accountName, tier}` rows with [SigilTier] ordinals.
 *
 * No kotlinx.serialization dependency; no org.json (absent from plain-JVM
 * unit tests). The reader is total: missing keys fall back to defaults,
 * unknown keys are ignored, and structurally corrupt input returns
 * [BackupRead.Corrupt] rather than a half-applied restore.
 *
 * Restore semantics are wholesale replacement: applying an Ok snapshot
 * overwrites every current setting with the file's values and replaces the
 * entire sigil map. A missing or empty `sigils` array therefore restores an
 * empty map — all existing calendar assignments are wiped — so callers must
 * surface that consequence before applying ([BackupRead.Ok] carries the exact
 * counts for the confirmation dialog).
 */
object BackupJson {

    const val APP_TAG = "xx-calendar"
    const val FORMAT_VERSION = 1

    fun write(settings: Settings, sigils: Map<CalendarKey, SigilTier>): String {
        val s = StringBuilder()
        s.append("{\n")
        s.append("  \"app\": \"$APP_TAG\",\n")
        s.append("  \"version\": $FORMAT_VERSION,\n")
        s.append("  \"settings\": {\n")
        val entries = listOf(
            "\"default_view\": \"${jsonEscape(settings.defaultView.name)}\"",
            "\"start_day_of_week\": \"${jsonEscape(settings.startDayOfWeek.name)}\"",
            "\"week_numbers\": ${settings.weekNumbers}",
            "\"show_declined\": ${settings.showDeclined}",
            "\"dim_past\": ${settings.dimPast}",
            "\"density\": \"${jsonEscape(settings.density.name)}\"",
            "\"default_duration_min\": ${settings.defaultDurationMin}",
            "\"default_notification_min\": ${settings.defaultNotificationMin}",
            "\"all_day_notify_hour\": ${settings.allDayNotification.hourOfDay}",
            "\"all_day_notify_days_before\": ${settings.allDayNotification.daysBefore}",
            "\"lock_screen_title\": ${settings.lockScreenTitle}",
            "\"daily_agenda\": ${settings.dailyAgenda}",
            "\"heads_up\": ${settings.headsUp}",
            "\"hide_auto_added\": ${settings.hideAutoAdded}",
            "\"background\": \"${jsonEscape(settings.background.name)}\"",
            "\"font\": \"${jsonEscape(settings.font.name)}\"",
            "\"text_size_scale\": ${floatToJson(settings.textSizeScale)}",
            "\"auto_added_filter_mode\": \"${jsonEscape(settings.autoAddedFilterMode.name)}\"",
        )
        s.append("    ").append(entries.joinToString(",\n    ")).append("\n")
        s.append("  },\n")
        s.append("  \"sigils\": [")
        if (sigils.isEmpty()) {
            s.append("]\n")
        } else {
            s.append("\n")
            val rows = sigils.entries.map { (key, tier) ->
                "    {\"calendarId\": ${key.calendarId}, " +
                    "\"accountName\": \"${jsonEscape(key.accountName)}\", " +
                    "\"tier\": ${tier.ordinal}}"
            }
            s.append(rows.joinToString(",\n")).append("\n  ]\n")
        }
        s.append("}\n")
        return s.toString()
    }

    fun read(json: String): BackupRead = try {
        val root = JsonParser(json.removePrefix("\uFEFF")).parseDocument() as? JsonObject
            ?: fail("root is not an object")
        val settings = readSettings(root.members["settings"] as? JsonObject)
        val sigils = readSigils(root.members["sigils"] as? JsonArray)
        BackupRead.Ok(BackupSnapshot(settings, sigils))
    } catch (_: Exception) {
        BackupRead.Corrupt
    }

    // -------------------------------------------------------------- reading

    private fun fail(reason: String): Nothing = throw IllegalArgumentException(reason)

    private fun readSettings(obj: JsonObject?): Settings = Settings(
        defaultView = obj.enum("default_view", DefaultView.SCHEDULE),
        startDayOfWeek = obj.enum("start_day_of_week", StartDayOfWeek.MONDAY),
        weekNumbers = obj.boolean("week_numbers", false),
        showDeclined = obj.boolean("show_declined", false),
        dimPast = obj.boolean("dim_past", true),
        density = obj.enum("density", Density.COMFORTABLE),
        defaultDurationMin = obj.integer("default_duration_min", 30),
        defaultNotificationMin = obj.integer("default_notification_min", 10),
        allDayNotification = AllDayNotification(
            hourOfDay = obj.integer("all_day_notify_hour", 18),
            daysBefore = obj.integer("all_day_notify_days_before", 1),
        ),
        lockScreenTitle = obj.boolean("lock_screen_title", false),
        dailyAgenda = obj.boolean("daily_agenda", false),
        headsUp = obj.boolean("heads_up", false),
        hideAutoAdded = obj.boolean("hide_auto_added", true),
        background = obj.enum("background", AppBackground.AMOLED_NIGHT),
        font = obj.enum("font", AppFont.JETBRAINS_MONO),
        textSizeScale = obj.fractional("text_size_scale", 1.0f),
        autoAddedFilterMode = obj.enum("auto_added_filter_mode", AutoAddedFilterMode.METADATA),
    )

    private inline fun <reified T : Enum<T>> JsonObject?.enum(key: String, fallback: T): T {
        val stored = (this?.members?.get(key) as? JsonString)?.value ?: return fallback
        return enumValues<T>().firstOrNull { it.name == stored } ?: fallback
    }

    private fun JsonObject?.boolean(key: String, fallback: Boolean): Boolean =
        when (val node = this?.members?.get(key)) {
            is JsonBoolean -> node.value
            else -> fallback
        }

    private fun JsonObject?.integer(key: String, fallback: Int): Int =
        when (val node = this?.members?.get(key)) {
            is JsonNumber -> try {
                node.toLong().toInt()
            } catch (_: RuntimeException) {
                fallback
            }
            else -> fallback
        }

    private fun JsonObject?.fractional(key: String, fallback: Float): Float =
        when (val node = this?.members?.get(key)) {
            is JsonNumber -> try {
                node.toDouble().toFloat()
            } catch (_: RuntimeException) {
                fallback
            }
            else -> fallback
        }

    private fun readSigils(array: JsonArray?): Map<CalendarKey, SigilTier> {
        if (array == null) return emptyMap()
        val out = LinkedHashMap<CalendarKey, SigilTier>()
        for (item in array.items) {
            val row = item as? JsonObject ?: continue
            val id = (row.members["calendarId"] as? JsonNumber)?.toLongOrNull() ?: continue
            val account = (row.members["accountName"] as? JsonString)?.value ?: continue
            val ordinal = (row.members["tier"] as? JsonNumber)?.toLongOrNull() ?: continue
            val tier = SigilTier.entries.getOrNull(ordinal.toInt()) ?: continue
            out[CalendarKey(id, account)] = tier
        }
        return out
    }

    // ------------------------------------------------------------- writing

    private fun floatToJson(value: Float): String {
        if (!value.isFinite()) return "1.0"
        return value.toString() // "1.0", "1.25" — valid JSON numbers
    }

    internal fun jsonEscape(value: String): String = buildString(value.length + 2) {
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else ->
                    if (ch < ' ') {
                        append("\\u").append(String.format(Locale.ROOT, "%04x", ch.code))
                    } else {
                        append(ch)
                    }
            }
        }
    }
}

// --------------------------------------------------------- minimal JSON AST

private sealed interface JsonNode

private class JsonObject(val members: LinkedHashMap<String, JsonNode>) : JsonNode

private class JsonArray(val items: List<JsonNode>) : JsonNode

private class JsonString(val value: String) : JsonNode

private class JsonBoolean(val value: Boolean) : JsonNode

private class JsonNullNode : JsonNode

/**
 * Numbers keep their raw text so integer ids survive without double rounding;
 * conversions happen at the field boundary where the target type is known.
 */
private class JsonNumber(private val raw: String) : JsonNode {
    fun toLong(): Long =
        raw.toLongOrNull() ?: BigDecimal(raw).setScale(0, RoundingMode.HALF_UP).longValueExact()

    fun toLongOrNull(): Long? = raw.toLongOrNull()

    fun toDouble(): Double = raw.toDouble()
}

private class JsonParser(private val source: String) {

    private var position = 0

    companion object {
        private const val MAX_DEPTH = 64
    }

    fun parseDocument(): JsonNode {
        skipWhitespace()
        val node = parseValue(depth = 0)
        skipWhitespace()
        if (position != source.length) fail("trailing content at $position")
        return node
    }

    private fun parseValue(depth: Int): JsonNode {
        if (depth > MAX_DEPTH) fail("nesting too deep")
        return when (peek()) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> JsonString(parseString())
            't' -> parseLiteral("true", JsonBoolean(true))
            'f' -> parseLiteral("false", JsonBoolean(false))
            'n' -> parseLiteral("null", JsonNullNode())
            else -> parseNumber()
        }
    }

    private fun parseObject(depth: Int): JsonObject {
        expect('{')
        val members = LinkedHashMap<String, JsonNode>()
        skipWhitespace()
        if (peek() == '}') {
            advance()
            return JsonObject(members)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("expected string key at $position")
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            members[key] = parseValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> advance()
                '}' -> {
                    advance()
                    return JsonObject(members)
                }
                else -> fail("expected , or } at $position")
            }
        }
    }

    private fun parseArray(depth: Int): JsonArray {
        expect('[')
        val items = mutableListOf<JsonNode>()
        skipWhitespace()
        if (peek() == ']') {
            advance()
            return JsonArray(items)
        }
        while (true) {
            skipWhitespace()
            items += parseValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> advance()
                ']' -> {
                    advance()
                    return JsonArray(items)
                }
                else -> fail("expected , or ] at $position")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val index = position
            if (index >= source.length) fail("unterminated string")
            when (val ch = source[index]) {
                '"' -> {
                    advance()
                    return out.toString()
                }
                '\\' -> {
                    advance()
                    // position now sits on the escape character itself.
                    when (val escaped = peek()) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            repeat(4) { i ->
                                val hexIndex = position + 1 + i
                                if (hexIndex >= source.length ||
                                    Character.digit(source[hexIndex], 16) < 0
                                ) {
                                    fail("bad unicode escape at $position")
                                }
                            }
                            val hex = source.substring(position + 1, position + 5)
                            out.append(hex.toInt(16).toChar())
                            position += 4
                        }
                        else -> fail("bad escape \\$escaped")
                    }
                    advance()
                }
                else -> {
                    if (ch.code < 0x20) fail("raw control character in string")
                    out.append(ch)
                    advance()
                }
            }
        }
    }

    private fun parseNumber(): JsonNumber {
        val start = position
        if (peek() == '-') advance()
        while (hasMore() && source[position].isDigit()) advance()
        if (hasMore() && source[position] == '.') {
            advance()
            while (hasMore() && source[position].isDigit()) advance()
        }
        if (hasMore() && (source[position] == 'e' || source[position] == 'E')) {
            advance()
            if (hasMore() && (source[position] == '+' || source[position] == '-')) advance()
            while (hasMore() && source[position].isDigit()) advance()
        }
        val raw = source.substring(start, position)
        raw.toDoubleOrNull() ?: fail("malformed number \"$raw\"")
        return JsonNumber(raw)
    }

    private fun parseLiteral(word: String, node: JsonNode): JsonNode {
        if (!source.startsWith(word, position)) fail("invalid literal at $position")
        position += word.length
        return node
    }

    private fun skipWhitespace() {
        while (hasMore() && source[position].isWhitespace()) advance()
    }

    private fun hasMore(): Boolean = position < source.length

    private fun peek(): Char {
        if (!hasMore()) fail("unexpected end of input")
        return source[position]
    }

    private fun expect(expected: Char) {
        if (!hasMore() || source[position] != expected) {
            fail("expected '$expected' at $position")
        }
        advance()
    }

    private fun advance() {
        position += 1
    }

    private fun fail(message: String): Nothing =
        throw IllegalArgumentException("backup json: $message")
}
