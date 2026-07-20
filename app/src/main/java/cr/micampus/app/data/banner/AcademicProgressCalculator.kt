package cr.micampus.app.data.banner

import cr.micampus.app.core.model.AcademicCycle
import cr.micampus.app.core.model.AcademicProgress
import cr.micampus.app.core.model.AcademicProgressFilter
import cr.micampus.app.core.model.AcademicProgressSnapshot
import cr.micampus.app.core.model.AcademicTermProgress
import cr.micampus.app.core.model.UnfinishedCoursePolicy

data class AcademicAttempt(
    val credits: Double,
    val grade: String?,
    val status: String? = null,
    val termCode: String = "unknown",
    val year: Int? = null,
    val cycle: AcademicCycle? = null,
)

/** Conservative, local-only aggregation. Repeated courses deliberately remain separate attempts. */
object AcademicProgressCalculator {
    fun snapshot(attempts: Iterable<AcademicAttempt>, updatedAtEpoch: Long): AcademicProgressSnapshot {
        val terms = attempts.groupBy { it.termCode }.map { (termCode, entries) ->
            val sample = entries.first()
            var approved = 0.0; var failed = 0.0; var unfinished = 0.0; var unclassified = 0.0; var count = 0
            entries.forEach { attempt ->
                if (attempt.credits <= 0.0 || attempt.status.isExcludedStatus()) return@forEach
                val grade = attempt.grade?.trim().orEmpty()
                if (grade.isBlank()) {
                    if (attempt.status.isUnfinishedStatus()) unfinished += attempt.credits
                    return@forEach
                }
                if (grade.isWithdrawal()) return@forEach
                when (val result = grade.result()) {
                    true -> approved += attempt.credits
                    false -> failed += attempt.credits
                    null -> { unclassified += attempt.credits; count++ }
                }
            }
            AcademicTermProgress(termCode, sample.year, sample.cycle, approved, failed, unfinished, unclassified, count, updatedAtEpoch)
        }
        return AcademicProgressSnapshot(terms, updatedAtEpoch)
    }

    fun derive(snapshot: AcademicProgressSnapshot, filter: AcademicProgressFilter = AcademicProgressFilter()): AcademicProgress {
        val terms = snapshot.terms.filter { term ->
            (filter.year == null || term.year == filter.year) && (filter.cycle == null || term.cycle == filter.cycle)
        }
        val approved = terms.sumOf { it.approvedCredits }
        val failed = terms.sumOf { it.failedCredits }
        val unfinished = terms.sumOf { it.unfinishedCredits }
        val unclassified = terms.sumOf { it.unclassifiedCredits }
        val count = terms.sumOf { it.unclassifiedResults }
        val attempted = approved + failed + when (filter.unfinishedPolicy) {
            UnfinishedCoursePolicy.EXCLUDE -> 0.0
            UnfinishedCoursePolicy.AS_FAILED, UnfinishedCoursePolicy.AS_PASSED -> unfinished
        }
        val numerator = approved + if (filter.unfinishedPolicy == UnfinishedCoursePolicy.AS_PASSED) unfinished else 0.0
        return AcademicProgress(
            attemptedCredits = attempted,
            approvedCredits = numerator,
            unclassifiedCredits = unclassified,
            unclassifiedResults = count,
            percentage = attempted.takeIf { it > 0.0 }?.let { (numerator / it * 100.0).coerceIn(0.0, 100.0) },
            updatedAtEpoch = snapshot.updatedAtEpoch,
            failedCredits = failed,
            unfinishedCredits = unfinished,
        )
    }

    /** Kept for callers that only need the default all-terms view. */
    fun calculate(attempts: Iterable<AcademicAttempt>, updatedAtEpoch: Long): AcademicProgress =
        derive(snapshot(attempts, updatedAtEpoch))

    private fun String?.isExcludedStatus(): Boolean {
        val value = this?.normalized().orEmpty()
        return (WITHDRAWALS + NON_FINAL).any(value::contains)
    }
    private fun String?.isUnfinishedStatus(): Boolean {
        val value = this?.normalized().orEmpty()
        return value in CURRENT_CODES || CURRENT_PHRASES.any(value::contains)
    }
    private fun String.isWithdrawal(): Boolean = normalized() in WITHDRAWAL_GRADES
    private fun String.result(): Boolean? {
        val normalized = normalized()
        val numeric = normalized.replace(',', '.').toDoubleOrNull()
        return when {
            numeric != null -> numeric >= 7.0
            normalized in PASSING_TEXT -> true
            normalized in FAILING_TEXT -> false
            else -> null
        }
    }
    private fun String.normalized(): String = trim().uppercase()
    private val PASSING_TEXT = setOf("APROBADO", "APROBADA", "PASS", "PASSED", "SATISFACTORIO", "SATISFACTORIA")
    private val FAILING_TEXT = setOf("REPROBADO", "REPROBADA", "FAIL", "FAILED", "INSATISFACTORIO", "INSATISFACTORIA")
    private val WITHDRAWALS = setOf("RETIRO", "RETIRADO", "RETIRADA", "WITHDRAWN", "WITHDRAWAL")
    private val WITHDRAWAL_GRADES = setOf("W", "RETIRO", "RETIRADO", "RETIRADA", "WITHDRAWN")
    private val CURRENT_PHRASES = setOf("EN CURSO", "CURSANDO", "IN PROGRESS", "ACTIVO", "ACTIVE", "INSCRITO", "REGISTRADO", "REGISTERED")
    private val CURRENT_CODES = setOf("IP")
    private val NON_FINAL = setOf("PENDIENTE", "PENDING", "WAITLIST", "LISTA DE ESPERA", "CANCEL", "ANUL", "ELIMIN", "DROP")
}
