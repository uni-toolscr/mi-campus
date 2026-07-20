package cr.micampus.app.core.model

/** Banner's two recognised academic cycles. Other terms remain visible in the all-terms view. */
enum class AcademicCycle { I, II }

enum class UnfinishedCoursePolicy { EXCLUDE, AS_FAILED, AS_PASSED }

data class AcademicProgressFilter(
    val year: Int? = null,
    val cycle: AcademicCycle? = null,
    val unfinishedPolicy: UnfinishedCoursePolicy = UnfinishedCoursePolicy.EXCLUDE,
)

/** Privacy-preserving per-term aggregate. No course, grade, or Banner response is retained. */
data class AcademicTermProgress(
    val termCode: String,
    val year: Int?,
    val cycle: AcademicCycle?,
    val approvedCredits: Double,
    val failedCredits: Double,
    val unfinishedCredits: Double,
    val unclassifiedCredits: Double,
    val unclassifiedResults: Int,
    val updatedAtEpoch: Long,
)

/** Encrypted on device as format v2. */
data class AcademicProgressSnapshot(
    val terms: List<AcademicTermProgress>,
    val updatedAtEpoch: Long,
)

/** A view derived locally from [AcademicProgressSnapshot] and the student's filter. */
data class AcademicProgress(
    val attemptedCredits: Double,
    val approvedCredits: Double,
    val unclassifiedCredits: Double,
    val unclassifiedResults: Int,
    val percentage: Double?,
    val updatedAtEpoch: Long,
    val failedCredits: Double = 0.0,
    val unfinishedCredits: Double = 0.0,
)
