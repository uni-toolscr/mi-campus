package cr.micampus.app.data.banner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import cr.micampus.app.core.model.AcademicCycle

class BannerProgressClientTest {
    @Test fun parsesTermDescriptionBeforeValidatedCodeFallback() {
        val client = BannerProgressClient(baseUrl = "https://banner.test/StudentRegistrationSsb")
        val terms = client.discoverTerms("""<option value="202599">Ciclo II 2024</option><option value="202501">Período especial 2025</option>""")
        assertEquals(2024, terms[0].year)
        assertEquals(AcademicCycle.II, terms[0].cycle)
        assertEquals(2025, terms[1].year)
        assertEquals(AcademicCycle.I, terms[1].cycle)
    }
    private class FakeTransport : BannerTransport {
        val gets = mutableListOf<String>()
        val posts = mutableListOf<Pair<String, Map<String, String>>>()
        override fun get(url: String): BannerHttpResponse {
            gets += url
            return if (url.contains("/reset?term=")) BannerHttpResponse(200, """{"data":{"registrations":[{"creditHour":"3,0","grade":"8"},{"creditHour":"2","grade":"RP"}]}}""")
            else BannerHttpResponse(200, """<form action="/cas/login"><input type="hidden" name="lt" value="token"><input type="password" name="password"></form>""")
        }
        override fun post(url: String, form: Map<String, String>): BannerHttpResponse {
            posts += url to form.toMap()
            return when {
                url.endsWith("/cas/login") -> BannerHttpResponse(200, """<select><option value="202501">I</option><option value="202502">II</option></select>""")
                else -> error("Unexpected post")
            }
        }
    }
    @Test fun authenticatesWithHiddenFieldsDiscoversTermsAndFetchesSequentially() = runBlocking {
        val transport = FakeTransport()
        val client = BannerProgressClient({ transport }, "https://banner.test/StudentRegistrationSsb")
        val result = client.fetchProgress("student", "secret".toCharArray())
        assertEquals("token", transport.posts.first().second["lt"])
        val progress = AcademicProgressCalculator.derive(result)
        assertEquals(6.0, progress.attemptedCredits, 0.0)
        assertEquals(6.0, progress.approvedCredits, 0.0)
        assertEquals(2, result.terms.size)
        assertEquals(1, transport.posts.size)
        assertTrue(transport.gets.any { it.endsWith("/reset?term=202501") })
    }
    @Test fun malformedHistoryJsonIsTyped() = runBlocking {
        val client = BannerProgressClient({ object : BannerTransport {
            override fun get(url: String) = BannerHttpResponse(200, "<option value=\"202501\">")
            override fun post(url: String, form: Map<String, String>) = BannerHttpResponse(200, "not json")
        } }, "https://banner.test/StudentRegistrationSsb")
        val error = runCatching { client.fetchProgress("u", "p".toCharArray()) }.exceptionOrNull()
        assertTrue(error is BannerException)
        assertEquals(BannerFailureKind.UNSUPPORTED, (error as BannerException).kind)
    }

    @Test fun resolvesRelativeCasActionAgainstRedirectedLoginHostAndClearsSession() = runBlocking {
        var postedUrl: String? = null
        var cleared = false
        val transport = object : BannerTransport {
            override fun get(url: String): BannerHttpResponse = when {
                url.contains("/reset?term=") -> BannerHttpResponse(200, """{"data":{"registrations":[{"creditHour":3,"grade":"8"}]}}""")
                else -> BannerHttpResponse(
                    200,
                    """<form action="login?service=ssb"><input value="token" name="execution" type="hidden"><input name="password"></form>""",
                    finalUrl = "https://login.una.ac.cr/cas/login?service=ssb",
                )
            }
            override fun post(url: String, form: Map<String, String>): BannerHttpResponse {
                postedUrl = url
                return BannerHttpResponse(200, """<option value="202501">I</option>""")
            }
            override fun clear() { cleared = true }
        }

        BannerProgressClient({ transport }, "https://banner.test/StudentRegistrationSsb")
            .fetchProgress("student", "secret".toCharArray())

        assertEquals("https://login.una.ac.cr/cas/login?service=ssb", postedUrl)
        assertTrue(cleared)
    }

    @Test fun rejectsCredentialFormOutsideTrustedHttpsHosts() = runBlocking {
        val client = BannerProgressClient({ object : BannerTransport {
            override fun get(url: String) = BannerHttpResponse(
                200,
                """<form action="http://attacker.test/login"><input name="password"></form>""",
                finalUrl = "https://login.una.ac.cr/cas/login",
            )
            override fun post(url: String, form: Map<String, String>) = error("Credentials must not be posted")
        } }, "https://banner.test/StudentRegistrationSsb")

        val error = runCatching { client.fetchProgress("u", "p".toCharArray()) }.exceptionOrNull()

        assertTrue(error is BannerException)
        assertEquals(BannerFailureKind.INVALID_RESPONSE, (error as BannerException).kind)
    }
}
