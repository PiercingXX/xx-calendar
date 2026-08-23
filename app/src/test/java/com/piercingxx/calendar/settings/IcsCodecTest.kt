package com.piercingxx.calendar.settings

import com.piercingxx.calendar.settings.IcsCodec.IcsEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS10: the RFC 5545 subset codec — round-trips over the fixture matrix,
 * folding correctness, UID duplicate skipping, escaping torture.
 */
class IcsCodecTest {

    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun at(tz: String, y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.of(tz)).toInstant().toEpochMilli()

    private fun timed(
        eventId: Long = 1L,
        calendarId: Long = 2L,
        title: String? = "standup",
    ) = IcsEvent(
        eventId = eventId,
        calendarId = calendarId,
        title = title,
        startMillis = at("America/New_York", 2026, 3, 2, 9),
        endMillis = at("America/New_York", 2026, 3, 2, 10, 30),
        allDay = false,
        eventTimezone = "America/New_York",
    )

    private fun parse(text: String, existingUids: Set<String> = emptySet()) =
        IcsCodec.parse(text.toByteArray(Charsets.UTF_8), existingUids)

    private fun physicalLines(exported: String): List<String> {
        assertFalse("bare LF must never appear", exported.replace("\r\n", "").contains('\n'))
        return exported.split("\r\n").dropLast(1)
    }

    // ------------------------------------------------------------- wrapper

    @Test
    fun `vcalendar wrapper carries version prodid and calscale`() {
        val out = IcsCodec.exportToString(listOf(timed()))
        val lines = physicalLines(out)
        assertEquals("BEGIN:VCALENDAR", lines[0])
        assertEquals("VERSION:2.0", lines[1])
        assertEquals("PRODID:-//PiercingXX//XX-Calendar//EN", lines[2])
        assertEquals("CALSCALE:GREGORIAN", lines[3])
        assertEquals("END:VCALENDAR", lines.last())
        assertTrue(out.endsWith("\r\n"))
    }

    @Test
    fun `uid is deterministic and preserved through import`() {
        assertEquals("5-7@xx-calendar", IcsCodec.uidOf(5, 7))
        val result = parse(IcsCodec.exportToString(listOf(timed(eventId = 5, calendarId = 7))))
        assertEquals("5-7@xx-calendar", result.events.single().uid)
    }

    // ---------------------------------------------------------- round trip

    @Test
    fun `timed event with timezone round-trips`() {
        val original = timed()
        val parsed = parse(IcsCodec.exportToString(listOf(original))).events.single()

        assertFalse(parsed.allDay)
        assertEquals(original.startMillis, parsed.startMillis)
        assertEquals(original.endMillis, parsed.endMillis)
        assertEquals("America/New_York", parsed.eventTimezone)
        assertNull(parsed.eventEndTimezone)
        assertEquals("standup", parsed.title)
        assertNull(parsed.duration)
        assertTrue(physicalLines(IcsCodec.exportToString(listOf(original)))
            .any { it == "DTSTART;TZID=America/New_York:20260302T090000" })
    }

    @Test
    fun `all-day event uses VALUE=DATE over UTC-midnight storage`() {
        val start = com.piercingxx.calendar.core.TimeMath.allDayDateToStorage(LocalDate.of(2026, 7, 4))
        val end = com.piercingxx.calendar.core.TimeMath.allDayDateToStorage(LocalDate.of(2026, 7, 5))
        val original = IcsEvent(
            eventId = 9,
            calendarId = 1,
            title = "independence day",
            startMillis = start,
            endMillis = end,
            allDay = true,
            eventTimezone = "UTC",
        )
        val exported = IcsCodec.exportToString(listOf(original))
        assertTrue(exported.contains("DTSTART;VALUE=DATE:20260704\r\n"))
        assertTrue(exported.contains("DTEND;VALUE=DATE:20260705\r\n"))

        val parsed = parse(exported).events.single()
        assertTrue(parsed.allDay)
        assertEquals(start, parsed.startMillis)
        assertEquals(end, parsed.endMillis)
        assertEquals("independence day", parsed.title)
    }

    @Test
    fun `multi-day all-day event round-trips its full span`() {
        val start = com.piercingxx.calendar.core.TimeMath.allDayDateToStorage(LocalDate.of(2026, 12, 24))
        val end = com.piercingxx.calendar.core.TimeMath.allDayDateToStorage(LocalDate.of(2026, 12, 27))
        val original = IcsEvent(
            eventId = 4,
            calendarId = 1,
            title = "holiday stretch",
            startMillis = start,
            endMillis = end,
            allDay = true,
            eventTimezone = null,
        )
        val parsed = parse(IcsCodec.exportToString(listOf(original))).events.single()
        assertTrue(parsed.allDay)
        assertEquals(start, parsed.startMillis)
        assertEquals(end, parsed.endMillis)
    }

    @Test
    fun `recurring rrule canonicalises through RRuleModel and round-trips`() {
        val original = IcsEvent(
            eventId = 11,
            calendarId = 3,
            title = "gym",
            startMillis = utc(2026, 2, 3, 7),
            endMillis = utc(2026, 2, 3, 8),
            allDay = false,
            eventTimezone = "UTC",
            rrule = "FREQ=WEEKLY;INTERVAL=1;BYDAY=TU,TH",
        )
        val exported = IcsCodec.exportToString(listOf(original))
        assertTrue(exported.contains("RRULE:FREQ=WEEKLY;BYDAY=TU,TH\r\n"))

        val parsed = parse(exported).events.single()
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", parsed.rrule)
    }

    @Test
    fun `unmodelled rrule passes through verbatim instead of being dropped`() {
        val original = timed().copy(rrule = "FREQ=HOURLY;INTERVAL=3")
        val parsed = parse(IcsCodec.exportToString(listOf(original))).events.single()
        assertEquals("FREQ=HOURLY;INTERVAL=3", parsed.rrule)
    }

    @Test
    fun `duration-based recurring event keeps DURATION and no DTEND`() {
        val original = IcsEvent(
            eventId = 21,
            calendarId = 1,
            title = "water the plants",
            startMillis = utc(2026, 5, 1, 8),
            endMillis = null,
            allDay = false,
            eventTimezone = "UTC",
            rrule = "FREQ=DAILY",
            duration = "PT90M",
        )
        val exported = IcsCodec.exportToString(listOf(original))
        assertTrue(exported.contains("DURATION:PT90M\r\n"))
        assertFalse(exported.contains("DTEND"))

        val parsed = parse(exported).events.single()
        assertEquals("PT90M", parsed.duration)
        assertNull(parsed.endMillis)
        assertEquals(original.startMillis, parsed.startMillis)
    }

    @Test
    fun `exdate and status and availability round-trip`() {
        val original = timed(title = "class").copy(
            exdate = "20260303T140000Z,20260305T140000Z",
            status = 3,
            availability = 1,
        )
        val exported = IcsCodec.exportToString(listOf(original))
        assertTrue(exported.contains("EXDATE:20260303T140000Z,20260305T140000Z\r\n"))
        assertTrue(exported.contains("STATUS:TENTATIVE\r\n"))
        assertTrue(exported.contains("TRANSP:TRANSPARENT\r\n"))

        val parsed = parse(exported).events.single()
        assertEquals("20260303T140000Z,20260305T140000Z", parsed.exdate)
        assertEquals(3, parsed.status)
        assertEquals(1, parsed.availability)
    }

    @Test
    fun `unknown status int omits the STATUS line`() {
        val exported = IcsCodec.exportToString(listOf(timed().copy(status = 42)))
        assertFalse(exported.contains("STATUS"))
    }

    // ------------------------------------------------------------ VALARMs

    @Test
    fun `reminders become DISPLAY valarm blocks with minute triggers`() {
        val exported = IcsCodec.exportToString(listOf(timed().copy(reminderMinutes = listOf(30, 10))))
        val lines = physicalLines(exported)
        val alarmIndex = lines.indexOf("BEGIN:VALARM")
        assertTrue(alarmIndex >= 0)
        assertEquals("ACTION:DISPLAY", lines[alarmIndex + 1])
        assertEquals(listOf("BEGIN:VALARM", "ACTION:DISPLAY"), lines.subList(alarmIndex, alarmIndex + 2))
        assertTrue(lines.contains("TRIGGER:-PT10M"))
        assertTrue(lines.contains("TRIGGER:-PT30M"))
        assertTrue(lines.lastIndexOf("END:VALARM") > alarmIndex)

        val parsed = parse(exported).events.single()
        assertEquals(listOf(10, 30), parsed.reminderMinutes)
    }

    @Test
    fun `zero-minute reminder emits PT0S and after-start stays negative`() {
        val zero = parse(IcsCodec.exportToString(listOf(timed().copy(reminderMinutes = listOf(0)))))
        assertEquals(listOf(0), zero.events.single().reminderMinutes)
        assertTrue(IcsCodec.exportToString(listOf(timed().copy(reminderMinutes = listOf(0))))
            .contains("TRIGGER:PT0S"))

        val after = parse(IcsCodec.exportToString(listOf(timed().copy(reminderMinutes = listOf(-15)))))
        assertEquals(listOf(-15), after.events.single().reminderMinutes)
    }

    @Test
    fun `import parses day-long and hour triggers into minutes`() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//x//y//EN",
            "BEGIN:VEVENT",
            "UID:a@b",
            "DTSTART:20260101T120000Z",
            "DTEND:20260101T130000Z",
            "SUMMARY:t1",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "TRIGGER:-P1D",
            "END:VALARM",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "TRIGGER;-X=1:-PT90S",
            "END:VALARM",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ).joinToString("\r\n")
        val draft = parse(text).events.single()
        assertEquals(listOf(1440, 2), draft.reminderMinutes)
    }

    @Test
    fun `absolute trigger is ignored rather than mis-scheduled`() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:abs@x",
            "DTSTART:20260101T120000Z",
            "SUMMARY:t",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "TRIGGER;VALUE=DATE-TIME:20251231T000000Z",
            "END:VALARM",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ).joinToString("\r\n")
        assertTrue(parse(text).events.single().reminderMinutes.isEmpty())
    }

    // ----------------------------------------------------------- escaping

    @Test
    fun `escaping torture title round-trips exactly`() {
        val nasty = "Tea, coffee; milk\nsugar\\salt \"quotes\" :colons:"
        val original = timed(title = nasty).copy(
            location = "back\\slash,semi;colon",
            description = "line one\nline two; line, three \\ end",
        )
        val exported = IcsCodec.exportToString(listOf(original))
        assertTrue(exported.contains("SUMMARY:Tea\\, coffee\\; milk\\nsugar\\\\salt \"quotes\" :colons:\r\n"))

        val parsed = parse(exported).events.single()
        assertEquals(nasty, parsed.title)
        assertEquals("back\\slash,semi;colon", parsed.location)
        assertEquals("line one\nline two; line, three \\ end", parsed.description)
    }

    // ------------------------------------------------------------ folding

    @Test
    fun `long ascii summary folds every physical line to 75 octets or fewer`() {
        val original = timed(title = "x".repeat(300))
        val exported = IcsCodec.exportToString(listOf(original))
        for (line in physicalLines(exported)) {
            assertTrue("line too long: ${line.length}", line.toByteArray(Charsets.UTF_8).size <= 75)
        }
        // Continuation lines are space-prefixed and unfold restores the property.
        val unfolded = IcsCodec.unfold(exported)
        assertTrue(unfolded.any { it.startsWith("SUMMARY:" + "x".repeat(70)) })
        assertEquals("x".repeat(300), parse(exported).events.single().title)
    }

    @Test
    fun `folding never splits multibyte characters`() {
        val cjk = "会议日程".repeat(40) + "🎉"
        val exported = IcsCodec.exportToString(listOf(timed(title = cjk)))
        for (line in physicalLines(exported)) {
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75)
            // A valid UTF-8 line decodes without replacement glyphs.
            assertEquals(false, String(line.toByteArray(Charsets.UTF_8), Charsets.UTF_8).contains('�'))
        }
        assertEquals(cjk, parse(exported).events.single().title)
    }

    @Test
    fun `unfold handles folded input with tabs and lone LF`() {
        val folded = "SUMMARY:hello\r\n world\r\n\tmore"
        assertEquals(listOf("SUMMARY:helloworldmore"), IcsCodec.unfold(folded))
    }

    // ---------------------------------------------------- property injection

    @Test
    fun `passthrough values containing cr or lf cannot forge properties on export`() {
        val hostile = timed().copy(
            rrule = "FREQ=DAILY\r\nBEGIN:VALARM\r\nACTION:DISPLAY",
            rdate = "20260303T140000Z\nBEGIN:VEVENT",
            exdate = "20260304T140000Z\rX-INJECTED:yes",
            duration = "PT90M\r\nTRIGGER:-PT0S",
        )
        val exported = IcsCodec.exportToString(listOf(hostile))

        // No bare LF anywhere, and the only structural lines are the real ones.
        val lines = physicalLines(exported)
        assertEquals(
            listOf("BEGIN:VCALENDAR", "BEGIN:VEVENT", "END:VEVENT", "END:VCALENDAR"),
            lines.filter { it.startsWith("BEGIN:") || it.startsWith("END:") },
        )
        assertFalse(lines.contains("ACTION:DISPLAY"))
        // The smuggled text may survive flattened inside a value, but never
        // as its own parseable property.
        assertFalse(lines.any { it.startsWith("X-INJECTED") })

        // The neutralised values stay on their own property lines, space-flattened.
        assertTrue(lines.any { it.startsWith("RRULE:FREQ=DAILY") })
        assertTrue(lines.any { it.startsWith("RDATE:20260303T140000Z") })
        assertTrue(lines.any { it.startsWith("EXDATE:20260304T140000Z") })
        assertTrue(lines.any { it.startsWith("DURATION:PT90M") })

        // Re-import still sees exactly one event and no smuggled properties.
        val parsed = parse(exported)
        assertEquals(1, parsed.events.size)
    }

    @Test
    fun `begin vevent text inside description stays escaped and inert`() {
        val original = timed(title = "sneaky")
            .copy(description = "see:\nBEGIN:VEVENT\nEND:VCALENDAR")
        val exported = IcsCodec.exportToString(listOf(original))

        val lines = physicalLines(exported)
        assertEquals(
            listOf("BEGIN:VCALENDAR", "BEGIN:VEVENT", "END:VEVENT", "END:VCALENDAR"),
            lines.filter { it.startsWith("BEGIN:") || it.startsWith("END:") },
        )
        // The smuggled text rides as escaped \n sequences on one property line.
        assertTrue(
            lines.contains("DESCRIPTION:see:\\nBEGIN:VEVENT\\nEND:VCALENDAR"),
        )

        val parsed = parse(exported).events.single()
        assertEquals("see:\nBEGIN:VEVENT\nEND:VCALENDAR", parsed.description)
    }

    @Test
    fun `fold flattens interior crlf instead of emitting it as line breaks`() {
        assertEquals(
            // each of CR and LF becomes one space; CRLF flattens to a pair
            "RRULE:FREQ=DAILY  BEGIN:VALARM ACTION:DISPLAY X",
            IcsCodec.fold("RRULE:FREQ=DAILY\r\nBEGIN:VALARM\rACTION:DISPLAY\nX"),
        )
        // Long input still folds within budget after sanitization.
        val long = IcsCodec.fold("RDATE:" + "x".repeat(40) + "\r\n" + "y".repeat(40))
        for (line in long.split("\r\n")) {
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75)
        }
    }

    @Test
    fun `parsed drafts never carry raw crlf in passthrough values`() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:clean@x",
            "DTSTART:20260101T090000Z",
            "DURATION:PT90M",
            "RRULE:FREQ=DAILY",
            "RDATE:20260102T090000Z",
            "EXDATE:20260103T090000Z",
            "DESCRIPTION:two\\nlines",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ).joinToString("\r\n")
        val draft = parse(text).events.single()
        for (value in listOfNotNull(draft.rrule, draft.rdate, draft.exdate, draft.duration)) {
            assertFalse(value, value.any { it == '\r' || it == '\n' })
        }
        // Escaped \n in TEXT properties is legitimate content, not injection.
        assertEquals("two\nlines", draft.description)
    }

    // -------------------------------------------------- duplicate handling

    @Test
    fun `existing uids are skipped and counted`() {
        val first = timed(eventId = 1)
        val second = timed(eventId = 2, title = "other")
        val exported = IcsCodec.exportToString(listOf(first, second))

        val clean = parse(exported)
        assertEquals(2, clean.events.size)
        assertEquals(0, clean.skippedDuplicateUids)

        val filtered = parse(exported, existingUids = setOf(IcsCodec.uidOf(1, 2)))
        assertEquals(1, filtered.events.size)
        assertEquals("other", filtered.events.single().title)
        assertEquals(1, filtered.skippedDuplicateUids)
    }

    @Test
    fun `duplicates inside one file collapse to the first occurrence`() {
        val body = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:same@x",
            "DTSTART:20260101T090000Z",
            "SUMMARY:first",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "UID:same@x",
            "DTSTART:20260102T090000Z",
            "SUMMARY:second",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ).joinToString("\r\n")
        val result = parse(body)
        assertEquals(listOf("first"), result.events.map { it.title })
        assertEquals(1, result.skippedDuplicateUids)
    }

    @Test
    fun `missing uid still imports and can never collide`() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "DTSTART:20260101T090000Z",
            "SUMMARY:no uid here",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ).joinToString("\r\n")
        val result = parse(text, existingUids = setOf(""))
        assertEquals(1, result.events.size)
        assertEquals("", result.events.single().uid)
    }

    // ------------------------------------------------------- foreign files

    @Test
    fun `unknown properties and nested foreign components are ignored`() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "X-WR-CALNAME:Imported",
            "BEGIN:VTIMEZONE",
            "TZID:Weird/Zone",
            "BEGIN:STANDARD",
            "DTSTART:19701025T030000",
            "TZOFFSETFROM:+0200",
            "END:STANDARD",
            "END:VTIMEZONE",
            "BEGIN:VEVENT",
            "UID:foreign@x",
            "SEQUENCE:3",
            "CLASS:PRIVATE",
            "CREATED:20200101T000000Z",
            "ATTENDEE;CN=Someone:mailto:someone@example.com",
            "DTSTART;TZID=America/New_York:20260601T100000",
            "DTEND;TZID=America/New_York:20260601T110000",
            "SUMMARY:from elsewhere",
            "BEGIN:VALARM",
            "ACTION:AUDIO",
            "TRIGGER:-PT5M",
            "ATTACH;FMTTYPE=audio/basic:http://example.com/beep.wav",
            "END:VALARM",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ).joinToString("\r\n")

        val draft = parse(text).events.single()
        assertEquals("from elsewhere", draft.title)
        assertEquals(at("America/New_York", 2026, 6, 1, 10), draft.startMillis)
        assertEquals(at("America/New_York", 2026, 6, 1, 11), draft.endMillis)
        assertEquals(listOf(5), draft.reminderMinutes)
    }

    @Test
    fun `floating dtstart resolves against the local zone`() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:floating@x",
            "DTSTART:20260401T090000",
            "SUMMARY:floating",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ).joinToString("\r\n")
        val expected = LocalDateTime.of(2026, 4, 1, 9, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, parse(text).events.single().startMillis)
    }

    // ------------------------------------------------------------ tolerance

    @Test
    fun `corrupt bytes yield an empty import instead of throwing`() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02, (0xFF).toByte(), (0xFE).toByte(), 0x69)
        val result = IcsCodec.parse(garbage)
        assertTrue(result.events.isEmpty())

        assertTrue(parse("").events.isEmpty())
        assertTrue(parse("not an ics file at all").events.isEmpty())
    }

    @Test
    fun `event truncated before END - VEVENT is still emitted leniently`() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:truncated@x",
            "DTSTART:20260101T090000Z",
            "SUMMARY:cut short",
        ).joinToString("\r\n")
        val result = parse(text)
        assertEquals(listOf("cut short"), result.events.map { it.title })
    }

    @Test
    fun `vevent without dtstart is dropped`() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:no-start@x",
            "SUMMARY:placeless",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ).joinToString("\r\n")
        assertTrue(parse(text).events.isEmpty())
    }

    @Test
    fun `latin-1 fallback keeps non-utf8 files parseable`() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:l1@x",
            "DTSTART:20260101T090000Z",
            "SUMMARY:caf\u00e9 lunch",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ).joinToString("\r\n")
        val result = IcsCodec.parse(text.toByteArray(Charsets.ISO_8859_1))
        assertEquals("café lunch", result.events.single().title)
    }
}
