package cr.micampus.app.data.banner

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cr.micampus.app.core.model.AcademicCycle
import cr.micampus.app.core.model.AcademicProgressSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock

enum class BannerFailureKind { INVALID_CREDENTIALS, SESSION_EXPIRED, NO_COMPLETED_RESULTS, NETWORK, SERVER, INVALID_RESPONSE, UNSUPPORTED }
class BannerException(val kind: BannerFailureKind, message: String, val retryable: Boolean = false, cause: Throwable? = null) : Exception(message, cause)
data class BannerHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val finalUrl: String? = null,
)

/** Stateful only for one request sequence; implementations must keep cookies in memory. */
interface BannerTransport {
    fun get(url: String): BannerHttpResponse
    fun post(url: String, form: Map<String, String>): BannerHttpResponse
    fun clear() = Unit
}

class HttpUrlConnectionBannerTransport : BannerTransport {
    private val cookies = linkedMapOf<String, MutableMap<String, String>>()
    override fun get(url: String) = request(url, null)
    override fun post(url: String, form: Map<String, String>) = request(url, form)
    override fun clear() = cookies.clear()
    private fun request(url: String, form: Map<String, String>?): BannerHttpResponse {
        requireTrustedUnaUrl(url)
        var currentUrl = url
        var currentForm = form
        repeat(MAX_REDIRECTS) {
            val response = requestOnce(currentUrl, currentForm)
            if (response.status !in 300..399) return response.copy(finalUrl = currentUrl)
            val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull() ?: return response
            currentUrl = URL(URL(currentUrl), location).toString()
            requireTrustedUnaUrl(currentUrl)
            currentForm = null // CAS redirects are followed as GET requests.
        }
        throw IOException("Too many Banner redirects")
    }
    private fun requestOnce(url: String, form: Map<String, String>?): BannerHttpResponse {
        requireTrustedUnaUrl(url)
        val body = form?.entries?.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }?.toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (form == null) "GET" else "POST"; connectTimeout = 15_000; readTimeout = 30_000
            instanceFollowRedirects = false; setRequestProperty("Accept", "text/html, application/json"); setRequestProperty("User-Agent", "MiCampus Android/1.0")
            cookies[URL(url).host]?.takeIf { it.isNotEmpty() }?.let { hostCookies -> setRequestProperty("Cookie", hostCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }) }
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8"); setFixedLengthStreamingMode(body.size) }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            connection.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value.orEmpty() }.forEach { rememberCookie(URL(url).host, it) }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            BannerHttpResponse(status, String(stream?.use(::readLimited) ?: ByteArray(0), StandardCharsets.UTF_8), connection.headerFields)
        } finally { connection.disconnect() }
    }
    private fun rememberCookie(host: String, header: String) { header.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { cookies.getOrPut(host) { linkedMapOf() }[it[0]] = it[1] } }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; if (total > MAX_RESPONSE_BYTES) throw IOException("Banner response is too large"); output.write(buffer, 0, count) }
        return output.toByteArray()
    }
    private fun requireTrustedUnaUrl(value: String) {
        val url = URL(value)
        if (url.protocol != "https" || (url.host != "una.ac.cr" && !url.host.endsWith(".una.ac.cr"))) {
            throw IOException("Banner redirected outside UNA HTTPS")
        }
    }
    private companion object { const val MAX_RESPONSE_BYTES = 2_000_000; const val MAX_REDIRECTS = 8 }
}

/**
 * Banner SSB adapter. The endpoint strings are injectable because UNA can change the proxy path.
 * It discovers hidden login fields and terms from the authenticated history document, then fetches
 * each term sequentially through Banner's reset-data endpoint. Nothing from the session is stored.
 */
class BannerProgressClient(
    private val transportFactory: () -> BannerTransport = { HttpUrlConnectionBannerTransport() },
    private val baseUrl: String = "https://studentssb.una.ac.cr/StudentRegistrationSsb",
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun fetchProgress(username: String, password: CharArray): AcademicProgressSnapshot = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isEmpty()) throw BannerException(BannerFailureKind.INVALID_CREDENTIALS, "Se requieren credenciales")
        val transport = transportFactory()
        try {
            val history = "$baseUrl/ssb/registrationHistory/registrationHistory"
            val login = transport.get(history)
            checkResponse(login)
            val authenticated = if (looksLikeLogin(login.body)) authenticate(transport, login, username.trim(), password) else login.body
            if (looksLikeLogin(authenticated)) throw BannerException(BannerFailureKind.INVALID_CREDENTIALS, "Credenciales inválidas")
            val terms = discoverTerms(authenticated)
            if (terms.isEmpty()) throw BannerException(BannerFailureKind.UNSUPPORTED, "Banner no mostró períodos académicos")
            val attempts = terms.flatMap { term ->
                attemptsFrom(resetTerm(transport, term.code)).map { it.copy(termCode = term.code, year = term.year, cycle = term.cycle) }
            }
            AcademicProgressCalculator.snapshot(attempts, clock.millis())
        } catch (error: BannerException) { throw error
        } catch (error: IOException) { throw BannerException(BannerFailureKind.NETWORK, "No se pudo conectar con Banner", retryable = true, cause = error)
        } catch (error: Exception) { throw BannerException(BannerFailureKind.INVALID_RESPONSE, "Respuesta de Banner no reconocida", cause = error) }
        finally {
            transport.clear()
            password.fill('\u0000')
        }
    }

    private fun authenticate(transport: BannerTransport, login: BannerHttpResponse, username: String, password: CharArray): String {
        val loginForm = LOGIN_FORM.find(login.body)?.value ?: login.body
        val form = hiddenFields(loginForm).toMutableMap(); form["username"] = username; form["password"] = String(password)
        val action = formAction(loginForm)?.let { trustedUrl(resolve(login.finalUrl ?: baseUrl, it)) }
            ?: "$baseUrl/ssb/registrationHistory/registrationHistory"
        return try {
            val response = transport.post(action, form)
            checkResponse(response)
            response.body
        } finally {
            form.clear()
        }
    }
    private fun resetTerm(transport: BannerTransport, term: String): String {
        val response = transport.get("$baseUrl/ssb/registrationHistory/reset?term=${URLEncoder.encode(term, StandardCharsets.UTF_8.name())}")
        checkResponse(response)
        if (looksLikeLogin(response.body)) {
            throw BannerException(BannerFailureKind.SESSION_EXPIRED, "La sesión de Banner venció", retryable = true)
        }
        return response.body
    }
    private fun checkResponse(response: BannerHttpResponse) {
        if (response.status == 401 || response.status == 403) throw BannerException(BannerFailureKind.INVALID_CREDENTIALS, "La sesión de Banner fue rechazada")
        if (response.status !in 200..299) throw BannerException(BannerFailureKind.SERVER, "Banner respondió ${response.status}", response.status >= 500)
    }
    internal data class BannerTerm(val code: String, val year: Int?, val cycle: AcademicCycle?)

    internal fun discoverTerms(html: String): List<BannerTerm> = TERM_OPTION.findAll(html).mapNotNull { match ->
        val code = match.groupValues[1].trim()
        if (!code.matches(Regex("\\d{4,}"))) return@mapNotNull null
        val description = decodeHtml(match.groupValues[2]).replace(Regex("<[^>]+>"), " ")
        BannerTerm(code, yearFrom(description, code), cycleFrom(description, code))
    }.distinctBy(BannerTerm::code).toList()

    private fun yearFrom(description: String, code: String): Int? =
        Regex("(?:19|20)\\d{2}").find(description)?.value?.toIntOrNull()
            ?: code.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
    private fun cycleFrom(description: String, code: String): AcademicCycle? = when {
        Regex("\\b(CICLO|SEMESTRE)\\s*I\\b", RegexOption.IGNORE_CASE).containsMatchIn(description) -> AcademicCycle.I
        Regex("\\b(CICLO|SEMESTRE)\\s*II\\b", RegexOption.IGNORE_CASE).containsMatchIn(description) -> AcademicCycle.II
        else -> when (code.lastOrNull()) { '1' -> AcademicCycle.I; '2' -> AcademicCycle.II; else -> null }
    }
    private fun hiddenFields(html: String): Map<String, String> = INPUT.findAll(html).mapNotNull { input ->
        val attributes = ATTRIBUTE.findAll(input.value).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        if (attributes["type"]?.equals("hidden", ignoreCase = true) == true) {
            attributes["name"]?.let { it to decodeHtml(attributes["value"].orEmpty()) }
        } else null
    }.toMap()
    private fun formAction(html: String): String? = FORM_ACTION.find(html)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
    private fun resolve(documentUrl: String, action: String) = URL(URL(documentUrl), decodeHtml(action)).toString()
    private fun trustedUrl(value: String): String {
        val url = URL(value)
        val baseHost = URL(baseUrl).host
        val isUna = url.host == "una.ac.cr" || url.host.endsWith(".una.ac.cr")
        if (url.protocol != "https" || (url.host != baseHost && !isUna)) {
            throw BannerException(BannerFailureKind.INVALID_RESPONSE, "Banner intentó salir de un dominio seguro de UNA")
        }
        return value
    }
    private fun looksLikeLogin(html: String) = html.contains("name=\"password\"", true) || html.contains("name='password'", true)

    internal fun attemptsFrom(json: String): List<AcademicAttempt> {
        val root = runCatching { JsonParser.parseString(json) }.getOrElse { throw BannerException(BannerFailureKind.INVALID_RESPONSE, "El historial no fue JSON") }
        val rows = findRows(root)
        return rows.mapNotNull { row ->
            val credits = row.number("creditHour", "creditHours", "credits", "credit", "hours") ?: return@mapNotNull null
            AcademicAttempt(
                credits,
                row.string("grade", "finalGrade", "gradeCode"),
                row.string(
                    "status",
                    "statusDescription",
                    "courseStatus",
                    "registrationStatus",
                    "registrationStatusDescription",
                    "courseRegistrationStatusDescription",
                ),
            )
        }
    }
    private fun findRows(element: JsonElement): List<JsonObject> = when {
        element.isJsonArray -> element.asJsonArray.flatMap(::findRows)
        !element.isJsonObject -> emptyList()
        else -> {
            val objectValue = element.asJsonObject
            val direct = listOf("data", "registrations", "rows", "courses", "history").flatMap { key -> objectValue.get(key)?.let(::findRows).orEmpty() }
            if (direct.isNotEmpty()) direct else if (objectValue.has("creditHour") || objectValue.has("creditHours") || objectValue.has("credits")) listOf(objectValue) else emptyList()
        }
    }
    private fun JsonObject.string(vararg names: String): String? = names.firstNotNullOfOrNull { get(it)?.takeUnless(JsonElement::isJsonNull)?.asString }
    private fun JsonObject.number(vararg names: String): Double? = names.firstNotNullOfOrNull { get(it)?.takeUnless(JsonElement::isJsonNull)?.asString?.replace(',', '.')?.toDoubleOrNull() }
    private fun decodeHtml(value: String) = value.replace("&amp;", "&").replace("&quot;", "\"")
    private companion object {
        val INPUT = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
        val ATTRIBUTE = Regex("""([\w:-]+)\s*=\s*[\"']([^\"']*)[\"']""", RegexOption.IGNORE_CASE)
        val FORM_ACTION = Regex("""<form[^>]*action=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        val LOGIN_FORM = Regex("""<form\b[^>]*>.*?name\s*=\s*[\"']password[\"'].*?</form>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val TERM_OPTION = Regex("""<option[^>]*value=[\"']([^\"']+)[\"'][^>]*>(.*?)</option>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
