package nl.yshott.app

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CalendarExport {
    private fun escaped(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
    fun ics(events: List<Event>): String {
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//YShott//Familieplanner//NL", "CALSCALE:GREGORIAN")
        val format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        for (e in events) {
            val from = LocalDateTime.parse(e.date + "T" + e.time).atZone(ZoneId.of("Europe/Amsterdam"))
            val to = LocalDateTime.parse(e.date + "T" + e.end).atZone(ZoneId.of("Europe/Amsterdam"))
            fun utc(d: java.time.ZonedDateTime) = d.withZoneSameInstant(ZoneId.of("UTC")).format(format)
            lines += listOf("BEGIN:VEVENT", "UID:" + e.id + "@yshott", "DTSTAMP:" + utc(java.time.ZonedDateTime.now()), "DTSTART:" + utc(from), "DTEND:" + utc(to), "SUMMARY:" + escaped(e.title), "LOCATION:" + escaped(e.place), "DESCRIPTION:" + escaped(e.note))
            val freq = mapOf("daily" to "DAILY", "weekly" to "WEEKLY", "monthly" to "MONTHLY")[e.repeat]
            if (freq != null) lines += "RRULE:FREQ=" + freq + if (e.until.isNotEmpty()) ";UNTIL=" + utc(java.time.LocalDate.parse(e.until).atTime(23, 59, 59).atZone(ZoneId.of("Europe/Amsterdam"))) else ""
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") + "\r\n"
    }
}
