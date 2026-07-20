package cr.micampus.app.feature.chat

import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ChatEventSkillTest {
    private val zone = ZoneId.of("America/Costa_Rica")
    private val clock = Clock.fixed(Instant.parse("2026-07-18T18:00:00Z"), zone)
    private val now = ZonedDateTime.now(clock)

    @Test fun naturalSpanishAndEnglishStatementsRouteToEventSkill() {
        assertTrue(ChatEventIntentDetector.isEventIntent("tengo examen mañana a las 5pm"))
        assertTrue(ChatEventIntentDetector.isEventIntent("I have a test tomorrow at 5pm"))
        assertTrue(ChatEventIntentDetector.isEventIntent("/crear-evento reunión mañana 10am"))
        assertTrue(ChatEventIntentDetector.isEventIntent("/create-event quiz tomorrow 9am"))
    }

    @Test fun ordinaryQuestionsDoNotCreateEvents() {
        assertFalse(ChatEventIntentDetector.isEventIntent("¿Cuándo es el examen?"))
        assertFalse(ChatEventIntentDetector.isEventIntent("How do I register for the exam?"))
        assertFalse(ChatEventIntentDetector.isEventIntent("¿Cómo me registro mañana?"))
    }

    @Test fun parserUsesSingleInstitutionAndInfersOneHourEnd() {
        val result = ChatEventParser.parse(
            raw = """{"title":"Examen","kind":"EXAM","date":"2026-07-19","start":"17:00"}""",
            sourceText = "tengo examen mañana a las 5",
            selectedInstitutions = setOf(Institution.UNA),
            now = now,
        ).getOrThrow()

        assertEquals(Institution.UNA, result.institution)
        assertEquals(EventKind.EXAM, result.kind)
        assertEquals(LocalTime.of(18, 0), result.endTime)
        assertTrue(result.inferredEnd)
        assertTrue(result.missingFields.isEmpty())
    }

    @Test fun multipleInstitutionsRequireReviewSelection() {
        val result = ChatEventParser.parse(
            raw = """{"title":"Quiz","date":"2026-07-20","start":"09:00"}""",
            sourceText = "tengo quiz el lunes",
            selectedInstitutions = setOf(Institution.UCR, Institution.UNA),
            now = now,
        ).getOrThrow()

        assertNull(result.institution)
        assertTrue("la institución" in result.missingFields)
        assertNull(result.toCampusEvent())
    }

    @Test fun partialProposalIsCompletedByFollowingTurn() {
        val partial = ChatEventParser.parse(
            raw = """{"title":"Entrega final","date":"2026-07-21"}""",
            sourceText = "tengo una entrega el martes",
            selectedInstitutions = setOf(Institution.UCR),
            now = now,
        ).getOrThrow()
        val complete = ChatEventParser.parse(
            raw = """{"start":"14:30"}""",
            sourceText = "a las 2:30pm",
            selectedInstitutions = setOf(Institution.UCR),
            now = now,
            previous = partial,
        ).getOrThrow()

        assertEquals("Entrega final", complete.title)
        assertEquals(LocalDate.of(2026, 7, 21), complete.date)
        assertEquals(LocalTime.of(14, 30), complete.startTime)
        assertTrue(complete.missingFields.isEmpty())
    }

    @Test fun pastEventsAreRejected() {
        val result = ChatEventParser.parse(
            raw = """{"title":"Viejo","date":"2026-07-17","start":"09:00"}""",
            sourceText = "evento ayer",
            selectedInstitutions = setOf(Institution.UNA),
            now = now,
        )

        assertTrue(result.isFailure)
        assertEquals("past_event", result.exceptionOrNull()?.message)
    }

    @Test fun hiddenEventPromptIncludesAllShortNamesButRawMessageStaysSeparate() {
        val prompt = ChatEventPrompt.build("tengo examen mañana", listOf("UCR", "UNA"), now)

        assertTrue(prompt.contains("Estudiante de: UNA, UCR"))
        assertTrue(prompt.contains("FECHA=2026-07-18"))
        assertTrue(prompt.contains("DIA=sábado"))
        assertTrue(prompt.contains("HORA=12:00:00"))
        assertTrue(prompt.contains("ZONA=America/Costa_Rica"))
        assertTrue(prompt.contains("FECHA_RESUELTA_POR_APP=2026-07-19"))
        assertTrue(prompt.contains("HORA_PROPORCIONADA=false"))
        assertTrue(prompt.endsWith("tengo examen mañana"))
        assertFalse(prompt.contains("course,groups,weeks"))
    }

    @Test fun resolverHandlesRelativeSpanishAndEnglishDates() {
        assertEquals(LocalDate.of(2026, 7, 19), EventTemporalResolver.resolve("mañana", now).resolvedDate)
        assertEquals(LocalDate.of(2026, 7, 19), EventTemporalResolver.resolve("manana", now).resolvedDate)
        assertEquals(LocalDate.of(2026, 7, 19), EventTemporalResolver.resolve("tomorrow", now).resolvedDate)
        assertEquals(LocalDate.of(2026, 7, 20), EventTemporalResolver.resolve("pasado mañana", now).resolvedDate)
        assertEquals(LocalDate.of(2026, 7, 20), EventTemporalResolver.resolve("day after tomorrow", now).resolvedDate)
        assertEquals(LocalDate.of(2026, 7, 21), EventTemporalResolver.resolve("este martes", now).resolvedDate)
        assertEquals(LocalDate.of(2026, 7, 21), EventTemporalResolver.resolve("this Tuesday", now).resolvedDate)
        assertEquals(LocalDate.of(2026, 8, 3), EventTemporalResolver.resolve("el 03/08/2026", now).resolvedDate)
        assertEquals(LocalDate.of(2026, 8, 4), EventTemporalResolver.resolve("2026-08-04", now).resolvedDate)
    }

    @Test fun weekdayOnTheSameDayAlwaysMeansTheFollowingWeek() {
        val tuesday = ZonedDateTime.of(2026, 7, 21, 9, 0, 0, 0, zone)

        assertEquals(LocalDate.of(2026, 7, 28), EventTemporalResolver.resolve("este martes", tuesday).resolvedDate)
        assertEquals(LocalDate.of(2026, 7, 28), EventTemporalResolver.resolve("next Tuesday", tuesday).resolvedDate)
    }

    @Test fun deterministicRelativeDateOverridesPastModelDateAndRejectsInventedTime() {
        val result = ChatEventParser.parse(
            raw = """{"title":"Examen","date":"2026-07-10","start":"08:00","end":"09:00"}""",
            sourceText = "tengo un examen mañana",
            selectedInstitutions = setOf(Institution.UNA),
            now = now,
        ).getOrThrow()

        assertEquals(LocalDate.of(2026, 7, 19), result.date)
        assertNull(result.startTime)
        assertNull(result.endTime)
        assertEquals(listOf("la hora de inicio"), result.missingFields)
    }

    @Test fun absolutePastDatesAndPassedTimesTodayRemainRejected() {
        val explicitPast = ChatEventParser.parse(
            raw = """{"title":"Examen","date":"2026-07-17","start":"09:00"}""",
            sourceText = "examen el 17/07/2026 a las 9am",
            selectedInstitutions = setOf(Institution.UNA),
            now = now,
        )
        val passedToday = ChatEventParser.parse(
            raw = """{"title":"Examen","date":"2026-07-18","start":"10:00"}""",
            sourceText = "examen hoy a las 10am",
            selectedInstitutions = setOf(Institution.UNA),
            now = now,
        )

        assertEquals("past_event", explicitPast.exceptionOrNull()?.message)
        assertEquals("past_event", passedToday.exceptionOrNull()?.message)
    }

    @Test fun impossibleExplicitDateIsARecoverableValidationFailure() {
        val temporal = EventTemporalResolver.resolve("examen el 31/02/2027 a las 9am", now)
        val result = ChatEventParser.parse(
            raw = """{"title":"Examen","date":"2027-03-03","start":"09:00"}""",
            sourceText = "examen el 31/02/2027 a las 9am",
            selectedInstitutions = setOf(Institution.UNA),
            now = now,
            temporalInput = temporal,
        )

        assertTrue(temporal.invalidExplicitDate)
        assertEquals("invalid_date", result.exceptionOrNull()?.message)
    }

    @Test fun partialFollowUpPreservesResolvedDateAndUsesOnlySuppliedTime() {
        val partial = ChatEventParser.parse(
            raw = """{"title":"Examen","date":"2020-01-01","start":"08:00"}""",
            sourceText = "tengo un examen este martes",
            selectedInstitutions = setOf(Institution.UCR),
            now = now,
        ).getOrThrow()
        val complete = ChatEventParser.parse(
            raw = """{"start":"03:15","end":"04:45"}""",
            sourceText = "a las 3:15pm",
            selectedInstitutions = setOf(Institution.UCR),
            now = now,
            previous = partial,
        ).getOrThrow()

        assertEquals(LocalDate.of(2026, 7, 21), complete.date)
        assertEquals(LocalTime.of(15, 15), complete.startTime)
        assertEquals(LocalTime.of(16, 15), complete.endTime)
        assertTrue(complete.inferredEnd)
    }
}
