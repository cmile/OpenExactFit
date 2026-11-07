package de.cmile.openexactfit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun quotedFieldsCommasAndCrlf() {
        val text = "a,b,c\r\n1,\"x,y\",\"say \"\"hi\"\"\"\r\n2,,\"two\nlines\"\r\n"
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("1", "x,y", "say \"hi\""), listOf("2", "", "two\nlines")),
            parseCsv(text),
        )
    }

    @Test fun recordsAreKeyedByHeaderAndSkipBlankLines() {
        val records = csvRecords("id,flags\n1,\"body_movement,irregular_pulse\"\n\n2,\n")
        assertEquals(listOf(mapOf("id" to "1", "flags" to "body_movement,irregular_pulse"), mapOf("id" to "2", "flags" to "")), records)
        assertEquals(emptyList<Map<String, String>>(), csvRecords(""))
    }

    @Test fun fieldQuotingRoundTrips() {
        val values = listOf("plain", "a,b", "he said \"no\"", "line\nbreak", "")
        val line = values.joinToString(",") { csvField(it) }
        assertEquals(values, parseCsv(line).single())
    }
}
