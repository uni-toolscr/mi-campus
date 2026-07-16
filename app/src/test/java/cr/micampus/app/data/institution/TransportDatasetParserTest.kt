package cr.micampus.app.data.institution

import cr.micampus.app.core.model.TransportDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class TransportDatasetParserTest {
    private val una = """{"version":1,"source":"UNA-STI-CIRC-002-2026","lastVerified":"2026-07-16","institution":"UNA","effectiveFrom":"2026-01-01","effectiveUntil":"2026-12-31","trips":[{"from":"Omar Dengo","to":"Benjamín Núñez","departures":["07:15","08:15"]}]}"""

    @Test fun parsesAndValidatesMetadataAndTimes() {
        val dataset = TransportDatasetParser.parse(una)
        assertEquals("UNA-STI-CIRC-002-2026", dataset.source)
        assertEquals(listOf("07:15", "08:15"), dataset.directions.first().weekdays)
    }

    @Test fun parsesSaturdayCalendar() {
        val json = una.replace(
            "\"departures\":[\"07:15\",\"08:15\"]",
            "\"departures\":[\"07:15\",\"08:15\"],\"saturday\":[\"07:00\"]",
        )
        assertEquals(listOf("07:00"), TransportDatasetParser.parse(json).directions.first().saturday)
    }

    @Test fun actualAssetsExposeMandatedSourcesAndArrays() {
        val unaDataset = TransportDatasetParser.parse(readAsset("una_2026.json"))
        assertEquals(
            "https://www.vidaestudiantil.una.ac.cr/noticias/2653-servicio-de-periferica-para-estudiantes-desde-el-campus-omar-dengo-al-campus-benjamin-nunez-y-viceversa",
            unaDataset.sourceUrl,
        )
        assertEquals(
            listOf("07:15", "08:15", "09:15", "10:45", "12:15", "14:15", "16:30", "19:30", "21:00"),
            unaDataset.directions.first().weekdays,
        )
        assertEquals(
            listOf("07:45", "08:15", "08:45", "10:15", "11:45", "12:45", "13:15", "15:15", "17:00", "20:00", "21:30"),
            unaDataset.directions[1].weekdays,
        )
        assertTrue(unaDataset.stops.any { it.contains("Ciencias Sociales") })
        assertTrue(unaDataset.stops.any { it.contains("Veterinaria") })
        assertTrue(unaDataset.stops.any { it.contains("CINPE") })
        assertTrue(unaDataset.notes.any { it.contains("carné") })

        val ucr = TransportDatasetParser.parse(readAsset("ucr_2026.json"))
        assertEquals("https://www.ucr.ac.cr/acerca-u/campus/bus-externo.html", ucr.sourceUrl)
        assertEquals(listOf("07:00"), ucr.directions.first().saturday)
        assertEquals(listOf("12:00"), ucr.directions[1].saturday)
        assertEquals(10, ucr.overrideDirections.first().weekdays.size)
        assertEquals(9, ucr.overrideDirections[1].weekdays.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateTimes() {
        TransportDatasetParser.validate(
            1,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            listOf(TransportDirection("x", "a", "b", listOf("07:00", "07:00"))),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsortedTimes() {
        TransportDatasetParser.validate(
            1,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            listOf(TransportDirection("x", "a", "b", listOf("08:00", "07:00"))),
        )
    }

    private fun readAsset(name: String): String {
        val candidates = listOf(File("src/main/assets/institutions/$name"), File("app/src/main/assets/institutions/$name"))
        return candidates.firstOrNull(File::exists)?.readText() ?: error("asset missing: $name")
    }
}
