package cr.micampus.app.data.ai

import cr.micampus.app.core.model.ImportIssue
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StrictJsonAiParserTest {
    @Test fun nullableFieldsAreIssues() {
        val draft = StrictJsonAiParser().parseDrafts("{\"events\":[{\"title\":\"Clase\",\"date\":null,\"start\":null}]}").single()
        assertTrue(ImportIssue.MISSING_DATE in draft.issues); assertTrue(ImportIssue.MISSING_TIME in draft.issues)
    }
    @Test fun invalidRangeAndPastAreFlagged() {
        val draft = StrictJsonAiParser().parseDrafts("{\"events\":[{\"title\":\"X\",\"date\":\"2020-01-01\",\"start\":\"10:00\",\"end\":\"09:00\"}]}", LocalDate.of(2026, 1, 1)).single()
        assertTrue(ImportIssue.PAST in draft.issues); assertTrue(ImportIssue.INVALID_RANGE in draft.issues)
    }
    @Test fun fullContractAndWrongTypesAreSafe() {
        val draft = StrictJsonAiParser().parseDrafts("{\"events\":[{\"title\":\"Clase\",\"date\":\"2026-08-01\",\"originalDateText\":\"viernes 1\",\"start\":\"08:00\",\"end\":\"09:00\",\"location\":null,\"courseHint\":\"MAT-1\",\"sourcePage\":2,\"evidence\":\"página\"}]}", LocalDate.of(2026, 1, 1)).single()
        assertEquals("viernes 1", draft.originalDateText); assertEquals(2, draft.sourcePage); assertEquals("MAT-1", draft.course?.code); assertNull(draft.location)
    }
    @Test fun malformedJsonReturnsActionableInvalidResult() { assertTrue(StrictJsonAiParser().parseStrictJson("not-json").warnings.isNotEmpty()) }

    @Test fun syllabusFieldsParseFromCartaShapedResponse() {
        val json = """
            {"course":{"name":"Fundamentos de Informática","code":"EIF200","institution":"UNA","period":"I Ciclo del 2025"},
             "groups":[{"label":"01","days":["LUNES","JUEVES"],"start":"08:00","end":"09:40","instructor":"Karol Leitón Arrieta"},
                       {"label":"06","days":["MARTES","VIERNES"],"start":"08:00","end":"09:40","instructor":"Irene Hernández Ruiz"}],
             "weeks":[{"week":1,"from":"2025-02-17","to":"2025-02-23","topic":"Presentación del curso y Elementos básicos de Computación"},
                      {"week":9,"from":"2025-04-14","to":"2025-04-20","topic":"Semana Santa"}],
             "holidays":[{"date":"2025-04-11","title":"Feriado Batalla de Rivas"}],
             "events":[{"title":"Prueba de ejecución 1","date":"2025-04-06","start":"09:00","category":"EXAM"}]}
        """.trimIndent()
        val parse = StrictJsonAiParser().parseSyllabus(json, LocalDate.of(2025, 1, 1))
        assertEquals("EIF200", parse.syllabus.course?.code)
        assertEquals(cr.micampus.app.core.model.Institution.UNA, parse.syllabus.institution)
        assertEquals(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY), parse.syllabus.groups.first().days)
        assertEquals(2, parse.syllabus.weeks.size)
        assertEquals(listOf(LocalDate.of(2025, 4, 11)), parse.syllabus.holidays)
        assertTrue(parse.syllabus.canExpandClasses)
        assertEquals(cr.micampus.app.core.model.EventCategory.EXAM, parse.drafts.single().category)
    }

    @Test fun quizAndTareaCategoriesParse() {
        val drafts = StrictJsonAiParser().parseDrafts("{\"events\":[{\"title\":\"Quiz 1\",\"category\":\"QUIZ\"},{\"title\":\"Tarea 1\",\"category\":\"TAREA\"}]}")
        assertEquals(cr.micampus.app.core.model.EventCategory.QUIZ, drafts[0].category)
        assertEquals(cr.micampus.app.core.model.EventCategory.TAREA, drafts[1].category)
    }

    @Test fun legacyFlatEventsResponseYieldsEmptySyllabus() {
        val parse = StrictJsonAiParser().parseSyllabus("{\"events\":[{\"title\":\"Clase\"}]}")
        assertFalse(parse.syllabus.canExpandClasses)
        assertNull(parse.syllabus.course)
        assertEquals(1, parse.drafts.size)
    }
}
