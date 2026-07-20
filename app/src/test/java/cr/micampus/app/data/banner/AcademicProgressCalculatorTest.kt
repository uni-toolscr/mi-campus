package cr.micampus.app.data.banner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import cr.micampus.app.core.model.AcademicCycle
import cr.micampus.app.core.model.AcademicProgressFilter
import cr.micampus.app.core.model.UnfinishedCoursePolicy

class AcademicProgressCalculatorTest {
    @Test fun appliesConservativeClassificationAndKeepsRepeats() {
        val result = AcademicProgressCalculator.calculate(listOf(
            AcademicAttempt(3.0, "8.5"), AcademicAttempt(3.0, "reprobado"),
            AcademicAttempt(4.0, "APROBADO"), AcademicAttempt(2.0, "EQ"),
            AcademicAttempt(3.0, "9", "Retiro"), AcademicAttempt(0.0, "10"),
        ), 100L)
        assertEquals(10.0, result.attemptedCredits, 0.0)
        assertEquals(7.0, result.approvedCredits, 0.0)
        assertEquals(2.0, result.unclassifiedCredits, 0.0)
        assertEquals(70.0, result.percentage!!, 0.0001)
        assertEquals(1, result.unclassifiedResults)
    }
    @Test fun zeroClassifiableCreditsHasNoPercentage() {
        assertNull(AcademicProgressCalculator.calculate(listOf(AcademicAttempt(3.0, "", "EN CURSO")), 1).percentage)
    }

    @Test fun supportsDecimalCommaAndExcludesBlankWithdrawalAndAmbiguousGrades() {
        val result = AcademicProgressCalculator.calculate(
            listOf(
                AcademicAttempt(3.0, "7,5"),
                AcademicAttempt(2.0, "6,9"),
                AcademicAttempt(4.0, ""),
                AcademicAttempt(1.0, "W"),
                AcademicAttempt(2.0, "AP"),
            ),
            2,
        )

        assertEquals(5.0, result.attemptedCredits, 0.0)
        assertEquals(3.0, result.approvedCredits, 0.0)
        assertEquals(60.0, result.percentage!!, 0.0)
        assertEquals(1, result.unclassifiedResults)
    }

    @Test fun shortIpCodeDoesNotMatchUnrelatedStatusText() {
        val result = AcademicProgressCalculator.calculate(
            listOf(AcademicAttempt(3.0, "8", "Inscripción completada")),
            3,
        )

        assertEquals(3.0, result.attemptedCredits, 0.0)
    }

    @Test fun excludesNonFinalStatusesEvenWhenAProvisionalGradeExists() {
        val result = AcademicProgressCalculator.calculate(
            listOf(
                AcademicAttempt(3.0, "8", "Pendiente de revisión"),
                AcademicAttempt(4.0, "9", "Registro cancelado"),
            ),
            4,
        )

        assertNull(result.percentage)
        assertEquals(0.0, result.attemptedCredits, 0.0)
    }

    @Test fun derivesTermFilterAndUnfinishedPoliciesLocally() {
        val snapshot = AcademicProgressCalculator.snapshot(
            listOf(
                AcademicAttempt(3.0, "8", termCode = "202501", year = 2025, cycle = AcademicCycle.I),
                AcademicAttempt(2.0, "", "Registered", "202501", 2025, AcademicCycle.I),
                AcademicAttempt(4.0, "6", termCode = "202502", year = 2025, cycle = AcademicCycle.II),
            ), 9L,
        )
        val exclude = AcademicProgressCalculator.derive(snapshot, AcademicProgressFilter(2025, AcademicCycle.I))
        val failed = AcademicProgressCalculator.derive(snapshot, AcademicProgressFilter(2025, AcademicCycle.I, UnfinishedCoursePolicy.AS_FAILED))
        val passed = AcademicProgressCalculator.derive(snapshot, AcademicProgressFilter(2025, AcademicCycle.I, UnfinishedCoursePolicy.AS_PASSED))
        assertEquals(3.0, exclude.attemptedCredits, 0.0)
        assertEquals(5.0, failed.attemptedCredits, 0.0)
        assertEquals(60.0, failed.percentage!!, 0.0)
        assertEquals(100.0, passed.percentage!!, 0.0)
    }

    @Test fun englishActiveBlankGradeIsUnfinished() {
        val snapshot = AcademicProgressCalculator.snapshot(
            listOf(AcademicAttempt(3.5, "", "Active", "202601", 2026, AcademicCycle.I)),
            10L,
        )

        val excluded = AcademicProgressCalculator.derive(snapshot)
        val asFailed = AcademicProgressCalculator.derive(
            snapshot,
            AcademicProgressFilter(unfinishedPolicy = UnfinishedCoursePolicy.AS_FAILED),
        )

        assertNull(excluded.percentage)
        assertEquals(3.5, asFailed.attemptedCredits, 0.0)
        assertEquals(0.0, asFailed.percentage!!, 0.0)
    }
}
