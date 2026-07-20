package cr.micampus.app.data.banner

import cr.micampus.app.core.model.AcademicProgressSnapshot
import cr.micampus.app.core.model.AcademicTermProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BannerProgressManagerTest {
    @Test fun unfinishedOnlySnapshotIsReturnedForLocalPolicySelection() = runBlocking {
        val storage = FakeStorage(
            AcademicProgressSnapshot(listOf(AcademicTermProgress("202501", 2025, null, 3.0, 0.0, 0.0, 0.0, 0, 1L)), 1L),
        )
        val client = BannerProgressClient(
            transportFactory = {
                object : BannerTransport {
                    override fun get(url: String) = if (url.contains("/reset?term=")) {
                        BannerHttpResponse(200, """{"data":{"registrations":[{"creditHour":3,"grade":"","status":"Activo"}]}}""")
                    } else {
                        BannerHttpResponse(200, """<option value="202501">I</option>""")
                    }

                    override fun post(url: String, form: Map<String, String>) = error("Login is not expected")
                }
            },
            baseUrl = "https://banner.test/StudentRegistrationSsb",
        )

        val snapshot = BannerProgressManager(client, storage).fetch("student", "secret".toCharArray())
        assertEquals(3.0, snapshot.terms.single().unfinishedCredits, 0.0)
        assertEquals(3.0, storage.value?.terms?.single()?.approvedCredits)
    }

    private class FakeStorage(var value: AcademicProgressSnapshot?) : AcademicProgressStorage {
        override fun read(): AcademicProgressSnapshot? = value
        override fun save(snapshot: AcademicProgressSnapshot) { value = snapshot }
        override fun clear() { value = null }
    }
}
