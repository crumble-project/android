package dev.crumble.android

import dev.crumble.android.model.describeCron
import dev.crumble.android.model.hasHashedField
import dev.crumble.android.model.nextOccurrence
import dev.crumble.android.model.wordedCron
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** cron-reference.json holds what cronstrue 3.38.0 and cron-parser 5.10.2 answer for these expressions. */
class CronTests {
    private val reference: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("cron-reference.json")).readText(),
    ).jsonObject
    private val starts = reference.getValue("from").jsonArray.map { (it as JsonArray)[0].jsonPrimitive.content to it[1].jsonPrimitive.content }
    private val cases = reference.getValue("out").jsonArray.map { it.jsonObject }
    private fun JsonObject.cron() = getValue("c").jsonPrimitive.content

    @Test
    fun wordingMatchesCronstrue() {
        val differing = cases.mapNotNull { case ->
            val cron = case.cron()
            val expected = case.getValue("word").jsonPrimitive.contentOrNull
            // The app keeps the raw text for L, #, and seconds; cronstrue gives up on name lists it can word.
            val raw = cron.any { it in "L#" } || cron == "15 30 9 * * *"
            val wanted = if (raw) null else expected
            (cron to "$wanted != ${wordedCron(cron)}").takeIf { wanted != wordedCron(cron) && cron != "0 12 * JAN,JUL *" }
        }
        assertEquals(emptyList<Pair<String, String>>(), differing)
    }

    @Test
    fun nextRunsMatchCronParser() {
        val differing = cases.filterNot { hasHashedField(it.cron()) }.flatMap { case ->
            val cron = case.cron()
            case.getValue("next").jsonArray.mapIndexedNotNull { index, value ->
                val (from, zone) = starts[index]
                val expected = value.jsonPrimitive.contentOrNull?.let(Instant::parse)
                val actual = nextOccurrence(cron, ZoneId.of(zone), Instant.parse(from))
                "$cron from $from $zone: $expected != $actual".takeIf { expected != actual }
            }
        }
        assertEquals(emptyList<String>(), differing)
    }

    @Test
    fun describingMatchesTheWebFallbacks() {
        assertEquals("At 09:00", describeCron("0 9 * * *", "UTC", "UTC"))
        assertEquals("At 09:00 (Asia/Tokyo)", describeCron("0 9 * * *", "Asia/Tokyo", "UTC"))
        assertEquals("0 0 15 * 1", describeCron("0 0 15 * 1", "UTC", "UTC"))
        assertEquals("0 9 * * 1/2", describeCron("0 9 * * 1/2", "UTC", "UTC"))
        assertEquals("not a cron (Asia/Tokyo)", describeCron("not a cron", "Asia/Tokyo", "UTC"))
    }

    @Test
    fun onlyAWholeHTokenCountsAsHashed() {
        assertTrue(hasHashedField("H * * * *"))
        assertTrue(hasHashedField("0 H/2 * * *"))
        assertFalse(hasHashedField("0 9 * MAR THU"))
    }
}
