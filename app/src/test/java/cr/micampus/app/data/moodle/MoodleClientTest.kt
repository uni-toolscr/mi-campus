package cr.micampus.app.data.moodle

import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.ResourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

class MoodleClientTest {
    private data class Request(val url: String, val form: Map<String, String>)

    private class FakeTransport(
        private val responder: (String, Map<String, String>) -> MoodleHttpResponse,
    ) : MoodleTransport {
        val requests = mutableListOf<Request>()

        override fun post(url: String, form: Map<String, String>): MoodleHttpResponse {
            requests += Request(url, form)
            return responder(url, form)
        }
    }

    @Test
    fun authenticateUsesTokenEndpointAndDiscoversCapabilities() = runBlocking {
        val transport = FakeTransport { url, form ->
            when {
                url.endsWith("/login/token.php") -> MoodleHttpResponse(200, "{\"token\":\"secret-token\"}")
                form["wsfunction"] == MoodleClient.SITE_INFO -> MoodleHttpResponse(200, siteInfoJson())
                else -> error("Unexpected request: $url $form")
            }
        }
        val client = MoodleClient(transport, baseUrl = "https://moodle.test", zoneId = ZoneOffset.UTC)
        val password = "password".toCharArray()

        val account = client.authenticate("student", password)

        assertEquals("secret-token", account.token)
        assertEquals(42L, account.userId)
        assertEquals("Student Name", account.displayName)
        assertEquals("moodle_mobile_app", transport.requests.first().form["service"])
        assertTrue(transport.requests.all { "secret-token" !in it.url })
        assertEquals("secret-token", transport.requests.last().form["wstoken"])
    }

    @Test
    fun fetchSnapshotMapsAssignmentDeadlineAndCourse() = runBlocking {
        val transport = FakeTransport { _, form ->
            when (form["wsfunction"]) {
                MoodleClient.SITE_INFO -> MoodleHttpResponse(200, siteInfoJson())
                MoodleClient.USERS_COURSES -> MoodleHttpResponse(
                    200,
                    "[{\"id\":7,\"shortname\":\"EIF-203\",\"fullname\":\"Programación\"}]",
                )
                MoodleClient.ACTION_EVENTS -> MoodleHttpResponse(
                    200,
                    """{"events":[{"id":91,"name":"<b>Proyecto 2</b>","description":"Entrega final","component":"mod_assign","modulename":"assign","courseid":7,"timesort":1700003600,"timemodified":1700000000,"url":"https://moodle.test/mod/assign/view.php?id=10"}],"lastid":91}""",
                )
                else -> error("Unexpected function ${form["wsfunction"]}")
            }
        }
        val client = MoodleClient(transport, baseUrl = "https://moodle.test", zoneId = ZoneOffset.UTC)
        val account = MoodleAccount("token", 42, "Student Name")

        val snapshot = client.fetchSnapshot(
            account,
            Instant.ofEpochSecond(1_700_000_000),
            Instant.ofEpochSecond(1_710_000_000),
        )

        assertEquals(1, snapshot.events.size)
        val event = snapshot.events.single()
        assertEquals("una-moodle:event:91", event.id)
        assertEquals("Proyecto 2", event.title)
        assertEquals(EventKind.TAREA, event.kind)
        assertEquals("EIF-203", event.courseCode)
        assertEquals("calendar-event:91", event.externalId)
        assertEquals("https://moodle.test/mod/assign/view.php?id=10", event.externalUrl)
        assertTrue(event.end.isAfter(event.start))
        val eventRequest = transport.requests.single { it.form["wsfunction"] == MoodleClient.ACTION_EVENTS }
        assertEquals("50", eventRequest.form["limitnum"])
        assertEquals("1", eventRequest.form["limittononsuspendedevents"])
    }

    @Test
    fun invalidLoginIsTyped() = runBlocking {
        val transport = FakeTransport { _, _ ->
            MoodleHttpResponse(200, "{\"error\":\"Wrong username or password\",\"errorcode\":\"invalidlogin\"}")
        }
        val client = MoodleClient(transport, baseUrl = "https://moodle.test")

        val error = runCatching { client.authenticate("student", "bad".toCharArray()) }.exceptionOrNull()

        assertTrue(error is MoodleException)
        assertEquals(MoodleFailureKind.INVALID_CREDENTIALS, (error as MoodleException).kind)
        assertFalse(error.retryable)
    }

    @Test
    fun fetchContentCatalogMapsResourcesAndStripsTokens() = runBlocking {
        val transport = FakeTransport { _, form ->
            when (form["wsfunction"]) {
                MoodleClient.SITE_INFO -> MoodleHttpResponse(200, siteInfoJson(includeContents = true))
                MoodleClient.USERS_COURSES -> MoodleHttpResponse(200, """[
                    {"id":7,"shortname":"EIF-203","fullname":"<b>Programación</b>","sortorder":4}
                ]""")
                MoodleClient.CONTENTS -> MoodleHttpResponse(200, contentsJson())
                else -> error("Unexpected function ${form["wsfunction"]}")
            }
        }
        val client = MoodleClient(transport, baseUrl = "https://moodle.test", zoneId = ZoneOffset.UTC)

        val result = client.fetchContentCatalog(MoodleAccount("secret-token", 42, "Student Name"))

        assertTrue(result is MoodleContentCatalog.Available)
        val catalog = result as MoodleContentCatalog.Available
        val course = catalog.courses.single()
        assertEquals("Programación", course.name)
        assertEquals(4, course.remoteOrder)
        assertEquals(2, course.sections.size)
        val resources = course.sections.first().resources
        assertEquals(listOf(ResourceKind.FILE, ResourceKind.FOLDER, ResourceKind.PAGE, ResourceKind.URL, ResourceKind.FORUM, ResourceKind.SCORM, ResourceKind.LABEL, ResourceKind.UNKNOWN, ResourceKind.FILE), resources.map { it.kind })
        assertEquals(2, resources[1].files.size)
        assertFalse(resources.any { it.name == "Oculto" })
        val restricted = resources.single { it.name == "Restringido" }
        assertFalse(restricted.enabled)
        assertEquals("Disponible el lunes", restricted.availabilityMessage)
        val file = resources.first().files.single()
        assertEquals("https://moodle.test/pluginfile.php/7/mod_resource/content/1/guia%20uno.pdf?forcedownload=1", file.url)
        assertFalse(file.url.contains("secret-token"))
        assertTrue(transport.requests.any { it.form["wsfunction"] == MoodleClient.CONTENTS && it.form["courseid"] == "7" })
    }

    @Test
    fun contentCatalogMapsSingleHtmlResourceToNavigablePage() = runBlocking {
        val catalog = fetchCatalogForContents("""
            [
              {"id":70,"name":"Semana 1","section":1,"modules":[
                {"id":101,"name":"Sílabo","modname":"resource","url":"https://moodle.test/mod/resource/view.php?id=101&token=module-secret","contents":[
                  {"filename":"index.html","fileurl":"https://moodle.test/pluginfile.php/7/mod_resource/content/1/index.html","mimetype":"text/html"}
                ]}
              ]}
            ]
        """.trimIndent())

        val resource = catalog.courses.single().sections.single().resources.single()

        assertEquals(ResourceKind.PAGE, resource.kind)
        assertEquals("https://moodle.test/mod/resource/view.php?id=101", resource.url)
        assertTrue(resource.files.isEmpty())
    }

    @Test
    fun contentCatalogMapsHtmlMimeAndExtensionsOnlyWhenModuleHasSafeUrl() = runBlocking {
        val htmlMime = fetchCatalogForContents(singleModuleContentsJson("resource", """
            {"filename":"syllabus.bin","fileurl":"https://moodle.test/pluginfile.php/syllabus","mimetype":"TEXT/HTML; charset=utf-8"}
        """, moduleUrl = "https://moodle.test/mod/resource/view.php?id=101")).courses.single().sections.single().resources.single()
        val htmlExtension = fetchCatalogForContents(singleModuleContentsJson("resource", """
            {"filename":"syllabus.HTM","fileurl":"https://moodle.test/pluginfile.php/syllabus"}
        """, moduleUrl = "https://moodle.test/mod/resource/view.php?id=102")).courses.single().sections.single().resources.single()

        assertEquals(ResourceKind.PAGE, htmlMime.kind)
        assertEquals(ResourceKind.PAGE, htmlExtension.kind)
        assertTrue(htmlMime.files.isEmpty())
        assertTrue(htmlExtension.files.isEmpty())
    }

    @Test
    fun contentCatalogPromotesHtmlAfterFilteringDecorativeImages() = runBlocking {
        val htmlWithPreview = fetchCatalogForContents(singleModuleContentsJson("resource", """
            {"filename":"index.html","fileurl":"https://moodle.test/pluginfile.php/index","mimetype":"text/html"},
            {"filename":"preview.png","fileurl":"https://moodle.test/pluginfile.php/preview","mimetype":"image/png"}
        """, moduleUrl = "https://moodle.test/mod/resource/view.php?id=101")).courses.single().sections.single().resources.single()
        val htmlWithImageMime = fetchCatalogForContents(singleModuleContentsJson("resource", """
            {"filename":"index.html","fileurl":"https://moodle.test/pluginfile.php/index","mimetype":"image/png"}
        """, moduleUrl = "https://moodle.test/mod/resource/view.php?id=102")).courses.single().sections.single().resources.single()

        assertEquals(ResourceKind.PAGE, htmlWithPreview.kind)
        assertEquals(ResourceKind.PAGE, htmlWithImageMime.kind)
        assertTrue(htmlWithPreview.files.isEmpty())
        assertTrue(htmlWithImageMime.files.isEmpty())
    }

    @Test
    fun contentCatalogKeepsDocumentsAndUnsafeHtmlResourcesAsDownloads() = runBlocking {
        val pdf = fetchCatalogForContents(singleModuleContentsJson("resource", """
            {"filename":"syllabus.pdf","fileurl":"https://moodle.test/pluginfile.php/syllabus","mimetype":"application/pdf"}
        """, moduleUrl = "https://moodle.test/mod/resource/view.php?id=101")).courses.single().sections.single().resources.single()
        val missingUrl = fetchCatalogForContents(singleModuleContentsJson("resource", """
            {"filename":"syllabus.html","fileurl":"https://moodle.test/pluginfile.php/syllabus","mimetype":"text/html"}
        """)).courses.single().sections.single().resources.single()
        val insecureUrl = fetchCatalogForContents(singleModuleContentsJson("resource", """
            {"filename":"syllabus.html","fileurl":"https://moodle.test/pluginfile.php/syllabus","mimetype":"text/html"}
        """, moduleUrl = "http://moodle.test/mod/resource/view.php?id=103")).courses.single().sections.single().resources.single()

        assertEquals(ResourceKind.FILE, pdf.kind)
        assertEquals(listOf("syllabus.pdf"), pdf.files.map { it.name })
        assertEquals(ResourceKind.FILE, missingUrl.kind)
        assertEquals(ResourceKind.FILE, insecureUrl.kind)
        assertNull(missingUrl.url)
        assertNull(insecureUrl.url)
        assertEquals(listOf("syllabus.html"), missingUrl.files.map { it.name })
        assertEquals(listOf("syllabus.html"), insecureUrl.files.map { it.name })
    }

    @Test
    fun contentCatalogDoesNotPromoteMixedOrFolderHtmlContents() = runBlocking {
        val mixed = fetchCatalogForContents(singleModuleContentsJson("resource", """
            {"filename":"syllabus.html","fileurl":"https://moodle.test/pluginfile.php/syllabus","mimetype":"text/html"},
            {"filename":"notes.pdf","fileurl":"https://moodle.test/pluginfile.php/notes","mimetype":"application/pdf"}
        """, moduleUrl = "https://moodle.test/mod/resource/view.php?id=101")).courses.single().sections.single().resources.single()
        val folder = fetchCatalogForContents(singleModuleContentsJson("folder", """
            {"filename":"syllabus.html","fileurl":"https://moodle.test/pluginfile.php/syllabus","mimetype":"text/html"}
        """, moduleUrl = "https://moodle.test/mod/folder/view.php?id=102")).courses.single().sections.single().resources.single()

        assertEquals(ResourceKind.FILE, mixed.kind)
        assertEquals(listOf("syllabus.html", "notes.pdf"), mixed.files.map { it.name })
        assertEquals(ResourceKind.FOLDER, folder.kind)
        assertEquals(listOf("syllabus.html"), folder.files.map { it.name })
    }

    @Test
    fun missingOptionalContentsCapabilityDoesNotBreakCalendarCapabilities() = runBlocking {
        val transport = FakeTransport { _, form ->
            when (form["wsfunction"]) {
                MoodleClient.SITE_INFO -> MoodleHttpResponse(200, siteInfoJson())
                else -> error("Catalog must not request ${form["wsfunction"]} when unsupported")
            }
        }
        val client = MoodleClient(transport, baseUrl = "https://moodle.test")

        val result = client.fetchContentCatalog(MoodleAccount("token", 42, "Student Name"))

        assertTrue(result is MoodleContentCatalog.Unsupported)
    }

    @Test
    fun contentCatalogDropsFilesWithImageMimeTypes() = runBlocking {
        val catalog = fetchCatalogForContents(singleModuleContentsJson("resource", """
            {"filename":"banner.png","fileurl":"https://moodle.test/pluginfile.php/banner","mimetype":"image/png"},
            {"filename":"notes.pdf","fileurl":"https://moodle.test/pluginfile.php/notes","mimetype":"application/pdf"}
        """))

        assertEquals(listOf("notes.pdf"), catalog.courses.single().sections.single().resources.single().files.map { it.name })
    }

    @Test
    fun contentCatalogDropsExtensionOnlyImagesAndKeepsDocuments() = runBlocking {
        val catalog = fetchCatalogForContents(singleModuleContentsJson("folder", """
            {"filename":"logo.jpg","fileurl":"https://moodle.test/pluginfile.php/logo"},
            {"filename":"notes.pdf","fileurl":"https://moodle.test/pluginfile.php/notes","mimetype":"application/pdf"}
        """))

        assertEquals(listOf("notes.pdf"), catalog.courses.single().sections.single().resources.single().files.map { it.name })
    }

    @Test
    fun contentCatalogDropsFileResourcesContainingOnlyImages() = runBlocking {
        val catalog = fetchCatalogForContents(singleModuleContentsJson("resource", """
            {"filename":"banner.png","fileurl":"https://moodle.test/pluginfile.php/banner","mimetype":"image/png"}
        """))

        assertTrue(catalog.courses.single().sections.single().resources.isEmpty())
    }

    @Test
    fun contentCatalogKeepsLabelsContainingOnlyImages() = runBlocking {
        val catalog = fetchCatalogForContents(singleModuleContentsJson("label", """
            {"filename":"banner.png","fileurl":"https://moodle.test/pluginfile.php/banner","mimetype":"image/png"}
        """))

        val resource = catalog.courses.single().sections.single().resources.single()
        assertEquals(ResourceKind.LABEL, resource.kind)
        assertTrue(resource.files.isEmpty())
    }

    @Test
    fun verifyTokenInvalidReturnsTrueForInvalidTokenProbe() = runBlocking {
        val client = MoodleClient(
            FakeTransport { _, _ -> MoodleHttpResponse(200, "{\"errorcode\":\"invalidtoken\",\"message\":\"Token inválido\"}") },
            baseUrl = "https://moodle.test",
        )

        assertTrue(client.verifyTokenInvalid(MoodleAccount("token", 42, "Student Name")))
    }

    @Test
    fun verifyTokenInvalidReturnsFalseForSuccessfulProbe() = runBlocking {
        val client = MoodleClient(
            FakeTransport { _, _ -> MoodleHttpResponse(200, siteInfoJson()) },
            baseUrl = "https://moodle.test",
        )

        assertFalse(client.verifyTokenInvalid(MoodleAccount("token", 42, "Student Name")))
    }

    @Test
    fun verifyTokenInvalidReturnsFalseForNetworkProbeFailure() = runBlocking {
        val client = MoodleClient(
            FakeTransport { _, _ -> throw IOException("offline") },
            baseUrl = "https://moodle.test",
        )

        assertFalse(client.verifyTokenInvalid(MoodleAccount("token", 42, "Student Name")))
    }

    @Test
    fun fetchContentCatalogWithPreloadedSessionSkipsHandshakeRequests() = runBlocking {
        val transport = FakeTransport { _, form ->
            when (form["wsfunction"]) {
                MoodleClient.CONTENTS -> MoodleHttpResponse(200, "[]")
                else -> error("Unexpected function ${form["wsfunction"]}")
            }
        }
        val client = MoodleClient(transport, baseUrl = "https://moodle.test")
        val site = MoodleSiteInfoDto(
            userid = 42,
            fullname = "Student Name",
            functions = listOf(
                MoodleFunctionDto(MoodleClient.SITE_INFO),
                MoodleFunctionDto(MoodleClient.USERS_COURSES),
                MoodleFunctionDto(MoodleClient.CONTENTS),
            ),
        )
        val courses = listOf(MoodleCourseDto(id = 7, shortname = "EIF-203", fullname = "Programación"))

        val result = client.fetchContentCatalog(
            MoodleAccount("token", 42, "Student Name"),
            MoodlePreloadedSession(site, courses),
        )

        assertTrue(result is MoodleContentCatalog.Available)
        assertEquals(listOf(MoodleClient.CONTENTS), transport.requests.map { it.form["wsfunction"] })
    }

    private suspend fun fetchCatalogForContents(contents: String): MoodleContentCatalog.Available {
        val transport = FakeTransport { _, form ->
            when (form["wsfunction"]) {
                MoodleClient.SITE_INFO -> MoodleHttpResponse(200, siteInfoJson(includeContents = true))
                MoodleClient.USERS_COURSES -> MoodleHttpResponse(200, """[
                    {"id":7,"shortname":"EIF-203","fullname":"Programación"}
                ]""")
                MoodleClient.CONTENTS -> MoodleHttpResponse(200, contents)
                else -> error("Unexpected function ${form["wsfunction"]}")
            }
        }
        return client(transport).fetchContentCatalog(MoodleAccount("token", 42, "Student Name")) as MoodleContentCatalog.Available
    }

    private fun client(transport: MoodleTransport) = MoodleClient(transport, baseUrl = "https://moodle.test", zoneId = ZoneOffset.UTC)

    private fun singleModuleContentsJson(modName: String, contents: String, moduleUrl: String? = null) = """
        [
          {"id":70,"name":"Semana 1","section":1,"modules":[
            {"id":101,"name":"Material","modname":"$modName"${moduleUrl?.let { ",\"url\":\"$it\"" }.orEmpty()},"contents":[$contents]}
          ]}
        ]
    """.trimIndent()

    private fun siteInfoJson(includeContents: Boolean = false) = """
        {
          "userid": 42,
          "fullname": "Student Name",
          "functions": [
            {"name": "${MoodleClient.SITE_INFO}"},
            {"name": "${MoodleClient.USERS_COURSES}"},
            {"name": "${MoodleClient.ACTION_EVENTS}"}${if (includeContents) ", {\"name\": \"${MoodleClient.CONTENTS}\"}" else ""}
          ]
        }
    """.trimIndent()

    private fun contentsJson() = """
        [
          {"id":70,"name":"Semana 1","section":1,"modules":[
            {"id":101,"name":"Guía","modname":"resource","url":"https://moodle.test/mod/resource/view.php?id=101","contents":[{"filename":"guia uno.pdf","filepath":"/","fileurl":"https://moodle.test/pluginfile.php/7/mod_resource/content/1/guia%20uno.pdf?token=secret-token&forcedownload=1","mimetype":"application/pdf","filesize":12,"timemodified":1700000000}]},
            {"id":102,"name":"Materiales","modname":"folder","contents":[{"filename":"a.nb","filepath":"/","fileurl":"https://moodle.test/pluginfile.php/a?token=secret-token"},{"filename":"b.pdf","filepath":"/sub/","fileurl":"https://moodle.test/pluginfile.php/b?wstoken=secret-token"}]},
            {"id":103,"name":"Página","modname":"page","url":"https://moodle.test/mod/page/view.php?id=103"},
            {"id":104,"name":"Enlace","modname":"url","url":"https://moodle.test/mod/url/view.php?id=104"},
            {"id":105,"name":"Foro","modname":"forum","url":"https://moodle.test/mod/forum/view.php?id=105"},
            {"id":106,"name":"SCORM","modname":"scorm","url":"https://moodle.test/mod/scorm/view.php?id=106"},
            {"id":107,"name":"Etiqueta","modname":"label"},
            {"id":108,"name":"Otro","modname":"h5pactivity"},
            {"id":109,"name":"Restringido","modname":"resource","visible":0,"availabilityinfo":"<b>Disponible el lunes</b>"},
            {"id":110,"name":"Oculto","modname":"resource","visible":0}
          ]},
          {"id":71,"name":"Semana vacía","section":2,"modules":[]}
        ]
    """.trimIndent()
}
