package cr.micampus.app.data.institution

import com.google.gson.*
import cr.micampus.app.core.model.*
import java.time.LocalDate

class DatasetValidationException(message: String) : IllegalArgumentException(message)

object TransportDatasetParser {
    fun parse(json: String): TransportDataset = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        fun text(name: String): String? = root.get(name)?.takeIf { !it.isJsonNull }?.asString
        fun date(name: String, fallback: String? = null) = LocalDate.parse(text(name) ?: fallback ?: throw DatasetValidationException("missing $name"))
        val version = root.get("version")?.asInt ?: throw DatasetValidationException("missing version")
        val institution = runCatching { Institution.valueOf(text("institution") ?: "") }.getOrElse { throw DatasetValidationException("invalid institution") }
        val from = date("verifiedFrom", text("effectiveFrom") ?: "2026-01-01"); val until = date("verifiedUntil", text("effectiveUntil") ?: "2026-12-31"); val verified = date("lastVerified")
        val base = directions(root.getAsJsonArray("trips"))
        if (base.isEmpty()) throw DatasetValidationException("missing trips")
        val overrides = root.getAsJsonArray("overrides")?.firstOrNull()?.asJsonObject
        val overrideDirections = overrides?.getAsJsonArray("trips")?.let(::directions) ?: emptyList()
        val stops = root.getAsJsonArray("stops")?.map { it.asString } ?: emptyList()
        val notes = root.getAsJsonArray("notes")?.map { it.asString } ?: emptyList()
        val included = dates(root.getAsJsonArray("includedDates")); val excluded = dates(root.getAsJsonArray("excludedDates")) + dates(root.getAsJsonArray("exclusions"))
        validate(version, from, until, base + overrideDirections)
        TransportDataset(
            version = version,
            institution = institution,
            source = text("source") ?: throw DatasetValidationException("missing source"),
            sourceUrl = text("sourceUrl"),
            lastVerified = verified,
            verifiedFrom = from,
            verifiedUntil = until,
            directions = base,
            stops = stops,
            notes = notes,
            overrideFrom = overrides?.get("effectiveFrom")?.asString?.let(LocalDate::parse),
            overrideUntil = overrides?.get("effectiveUntil")?.asString?.let(LocalDate::parse),
            overrideDirections = overrideDirections,
            includedDates = included,
            excludedDates = excluded,
            auditNote = text("auditNote"),
        )
    }.getOrElse { e -> if (e is DatasetValidationException) throw e else throw DatasetValidationException("invalid JSON: ${e.message}") }

    private fun dates(array: JsonArray?): Set<LocalDate> = array?.mapNotNull { runCatching { LocalDate.parse(it.asString) }.getOrNull() }?.toSet() ?: emptySet()
    private fun directions(array: JsonArray?): List<TransportDirection> {
        if (array == null) return emptyList()
        return array.map { element ->
            val obj = element.asJsonObject
            val from = obj.get("from")?.asString ?: throw DatasetValidationException("missing route from")
            val to = obj.get("to")?.asString ?: throw DatasetValidationException("missing route to")
            val weekdays = obj.getAsJsonArray("departures")?.map { it.asString } ?: emptyList()
            val saturday = obj.getAsJsonArray("saturday")?.map { it.asString } ?: emptyList()
            TransportDirection("$from-$to", from, to, weekdays, saturday)
        }
    }
    fun validate(version: Int, from: LocalDate, until: LocalDate, directions: List<TransportDirection>) { require(version > 0) { "version" }; require(!from.isAfter(until)) { "range" }; require(directions.isNotEmpty()) { "directions" }; directions.forEach { d -> listOf(d.weekdays, d.saturday).forEach { times -> require(times == times.sorted()) { "unsorted departures" }; require(times.distinct().size == times.size) { "duplicate departures" }; require(times.all { Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(it) }) { "invalid time" } } } }
}
