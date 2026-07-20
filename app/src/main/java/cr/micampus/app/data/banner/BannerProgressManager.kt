package cr.micampus.app.data.banner

import cr.micampus.app.core.model.AcademicProgressSnapshot

interface AcademicProgressService {
    fun cached(): AcademicProgressSnapshot?
    suspend fun fetch(username: String, password: CharArray): AcademicProgressSnapshot
    fun save(snapshot: AcademicProgressSnapshot)
    fun clear()
}

object NoOpAcademicProgressService : AcademicProgressService {
    override fun cached(): AcademicProgressSnapshot? = null
    override suspend fun fetch(username: String, password: CharArray): AcademicProgressSnapshot =
        throw BannerException(BannerFailureKind.UNSUPPORTED, "Avance académico no configurado")
    override fun save(snapshot: AcademicProgressSnapshot) = Unit
    override fun clear() = Unit
}

class BannerProgressManager(
    private val client: BannerProgressClient,
    private val storage: AcademicProgressStorage,
) : AcademicProgressService {
    override fun cached(): AcademicProgressSnapshot? = storage.read()
    override suspend fun fetch(username: String, password: CharArray): AcademicProgressSnapshot {
        val snapshot = client.fetchProgress(username, password)
        return snapshot
    }
    override fun save(snapshot: AcademicProgressSnapshot) = storage.save(snapshot)
    override fun clear() = storage.clear()
}
