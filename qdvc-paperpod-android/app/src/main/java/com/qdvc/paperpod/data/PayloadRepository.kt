package com.qdvc.paperpod.data

import android.content.Context
import android.os.Environment
import com.qdvc.paperpod.prefs.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Reads the mirrored payload directory. Everything is a small file on disk, so
 * loads are cheap, incremental and inspectable by hand when something looks off.
 *
 * Parsing uses org.json from the platform rather than a serialisation library:
 * the payload is our own format, the shapes are shallow, and a dependency-free
 * build is one less thing to fight when iterating on a 2fps device.
 */
class PayloadRepository(private val context: Context) {

    private val prefs = Prefs(context)

    @Volatile
    var manifest: Manifest? = null
        private set

    @Volatile
    var buildInfo: BuildInfo? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Candidate roots, in preference order, so a fresh install usually just works. */
    fun candidateRoots(): List<File> {
        val configured = prefs.payloadPath
        val roots = mutableListOf<File>()
        if (!configured.isNullOrBlank()) roots += File(configured)
        val ext = Environment.getExternalStorageDirectory()
        roots += File(ext, "QDVC-Paperpod")
        roots += File(ext, "Paperpod")
        roots += File(ext, "Documents/QDVC-Paperpod")
        context.getExternalFilesDir(null)?.let { roots += File(it, "payload") }
        return roots
    }

    fun resolveRoot(): File? = candidateRoots().firstOrNull { File(it, MANIFEST).isFile }

    val root: File? get() = resolveRoot()

    fun file(relative: String): File? {
        val r = resolveRoot() ?: return null
        val f = File(r, relative)
        return if (f.exists()) f else null
    }

    /** Loads manifest and build info. Returns true when a payload was found. */
    fun load(): Boolean {
        lastError = null
        val r = resolveRoot()
        if (r == null) {
            lastError = "No payload found. Looked in:\n" +
                candidateRoots().joinToString("\n") { "  " + it.absolutePath }
            manifest = null
            buildInfo = null
            return false
        }
        return try {
            manifest = parseManifest(JSONObject(File(r, MANIFEST).readText()))
            buildInfo = File(r, BUILD).takeIf { it.isFile }
                ?.let { parseBuild(JSONObject(it.readText())) }
            true
        } catch (e: Exception) {
            lastError = "Could not read $MANIFEST: ${e.message}"
            manifest = null
            false
        }
    }

    // ---------------------------------------------------------------- manifest

    private fun parseManifest(o: JSONObject): Manifest {
        val t = o.optJSONObject("typography")
        val mods = mutableListOf<ModuleSpec>()
        o.optJSONArray("modules")?.forEachObject { m ->
            val id = m.optString("id")
            if (id.isNotBlank()) {
                mods += ModuleSpec(
                    id = id,
                    label = m.optString("label", id),
                    icon = m.optString("icon", id),
                    primitive = m.optString("primitive", "card"),
                    source = m.optString("source", "").ifBlank { null },
                )
            }
        }
        return Manifest(
            schema = o.optInt("schema", 1),
            bundleId = o.optString("bundleId"),
            generatedAt = o.optString("generatedAt"),
            title = o.optString("title", "Paperpod"),
            typography = Typography(
                defaultFamily = t?.optString("defaultFamily")?.ifBlank { null },
                defaultBodySizeSp = t?.optInt("defaultBodySizeSp", 19) ?: 19,
                defaultLineSpacing = (t?.optDouble("defaultLineSpacing", 1.35) ?: 1.35).toFloat(),
            ),
            modules = mods,
        )
    }

    private fun parseBuild(o: JSONObject): BuildInfo {
        val counts = mutableMapOf<String, Int>()
        o.optJSONObject("counts")?.let { c ->
            c.keys().forEach { k -> counts[k] = c.optInt(k) }
        }
        val files = mutableMapOf<String, String>()
        o.optJSONObject("files")?.let { f ->
            f.keys().forEach { k -> files[k] = f.optString(k) }
        }
        return BuildInfo(
            buildId = o.optString("buildId"),
            generatedAt = o.optString("generatedAt"),
            studioVersion = o.optString("studioVersion"),
            counts = counts,
            files = files,
        )
    }

    // --------------------------------------------------------------------- day

    fun day(date: LocalDate, sourceDir: String = "days"): Day? {
        val f = file("$sourceDir/${date.format(ISO)}.json") ?: return null
        return try {
            val o = JSONObject(f.readText())
            val events = mutableListOf<DayEvent>()
            o.optJSONArray("events")?.forEachObject { events += parseEvent(it) }
            val tasks = mutableListOf<DayTask>()
            o.optJSONArray("tasks")?.forEachObject {
                tasks += DayTask(
                    title = it.optString("title"),
                    project = it.optString("project"),
                    priority = it.optString("priority"),
                    due = it.optString("due").ifBlank { null },
                    overdue = it.optBoolean("overdue", false),
                )
            }
            val sun = o.optJSONObject("sun")?.let {
                Sun(it.optString("rise").ifBlank { null }, it.optString("set").ifBlank { null })
            }
            Day(
                date = o.optString("date", date.format(ISO)),
                weekday = o.optString("weekday", date.dayOfWeek.name.lowercase(Locale.UK)
                    .replaceFirstChar { c -> c.uppercase() }),
                dayNote = o.optString("dayNote"),
                sun = sun,
                moon = o.optString("moon"),
                events = events.sortedBy { e -> sortKey(e) },
                tasks = tasks,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun sortKey(e: DayEvent): String =
        if (e.allDay) "00:00" else (e.start ?: "99:99")

    private fun parseEvent(it: JSONObject) = DayEvent(
        start = it.optString("start").ifBlank { null },
        end = it.optString("end").ifBlank { null },
        allDay = it.optBoolean("allDay", false),
        title = it.optString("title"),
        location = it.optString("location"),
        calendar = it.optString("calendar"),
        note = it.optString("note"),
    )

    // -------------------------------------------------------------------- week

    fun weekIdFor(date: LocalDate): String {
        val wf = WeekFields.ISO
        val week = date.get(wf.weekOfWeekBasedYear())
        val year = date.get(wf.weekBasedYear())
        return String.format(Locale.UK, "%d-W%02d", year, week)
    }

    fun week(date: LocalDate, sourceDir: String = "weeks"): Week? {
        val f = file("$sourceDir/${weekIdFor(date)}.json") ?: return null
        return try {
            val o = JSONObject(f.readText())
            val days = mutableListOf<WeekDay>()
            o.optJSONArray("days")?.forEachObject { d ->
                val events = mutableListOf<DayEvent>()
                d.optJSONArray("events")?.forEachObject { events += parseEvent(it) }
                days += WeekDay(
                    date = d.optString("date"),
                    weekday = d.optString("weekday"),
                    taskCount = d.optInt("taskCount", 0),
                    events = events,
                )
            }
            Week(o.optString("isoWeek"), o.optString("start"), o.optString("end"), days)
        } catch (e: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------- library

    fun library(source: String = "library/index.json"): List<DocumentRef> {
        val f = file(source) ?: return emptyList()
        return try {
            val out = mutableListOf<DocumentRef>()
            JSONObject(f.readText()).optJSONArray("documents")?.forEachObject { d ->
                out += DocumentRef(
                    id = d.optString("id"),
                    title = d.optString("title"),
                    authors = d.optJSONArray("authors").toStringList(),
                    year = d.optInt("year", 0).takeIf { it > 0 },
                    venue = d.optString("venue"),
                    kind = d.optString("kind", "paper"),
                    tags = d.optJSONArray("tags").toStringList(),
                    words = d.optInt("words", 0),
                    readingMinutes = d.optInt("readingMinutes", 0),
                    addedAt = d.optString("addedAt"),
                    sourceUrl = d.optString("sourceUrl"),
                    path = d.optString("path", "library/${d.optString("id")}"),
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun documentDetail(ref: DocumentRef): DocumentDetail? {
        val f = file("${ref.path}/doc.json") ?: return null
        return try {
            val o = JSONObject(f.readText())
            DocumentDetail(
                id = o.optString("id", ref.id),
                title = o.optString("title", ref.title),
                authors = o.optJSONArray("authors").toStringList().ifEmpty { ref.authors },
                abstract = o.optString("abstract"),
                textPath = "${ref.path}/${o.optString("text", "text.md")}",
                method = o.optJSONObject("provenance")?.optString("method") ?: "",
            )
        } catch (e: Exception) {
            null
        }
    }

    fun readText(relative: String): String? = file(relative)?.readText()

    // ------------------------------------------------------------------- dwell

    fun dwellDeck(source: String = "dwell/deck.json"): DwellDeck? {
        val f = file(source) ?: return null
        return try {
            val o = JSONObject(f.readText())
            val schedule = mutableMapOf<String, List<String>>()
            o.optJSONObject("schedule")?.let { s ->
                s.keys().forEach { k -> schedule[k] = s.optJSONArray(k).toStringList() }
            }
            val cards = mutableListOf<DwellCard>()
            o.optJSONArray("cards")?.forEachObject { c ->
                cards += DwellCard(
                    id = c.optString("id"),
                    kind = c.optString("kind", "note"),
                    title = c.optString("title"),
                    body = c.optString("body"),
                    image = c.optString("image").ifBlank { null },
                    attribution = c.optString("attribution"),
                    date = c.optString("date"),
                )
            }
            DwellDeck(schedule, cards)
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------- countdowns

    fun countdowns(source: String = "soon/countdowns.json"): List<Countdown> {
        val f = file(source) ?: return emptyList()
        return try {
            val out = mutableListOf<Countdown>()
            JSONObject(f.readText()).optJSONArray("items")?.forEachObject { c ->
                out += Countdown(
                    id = c.optString("id"),
                    title = c.optString("title"),
                    date = c.optString("date"),
                    kind = c.optString("kind", "event"),
                    annual = c.optBoolean("annual", false),
                    note = c.optString("note"),
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------- zones

    fun zones(source: String = "time/zones.json"): List<TimeZoneEntry> {
        val f = file(source) ?: return emptyList()
        return try {
            val out = mutableListOf<TimeZoneEntry>()
            JSONObject(f.readText()).optJSONArray("zones")?.forEachObject { z ->
                out += TimeZoneEntry(
                    label = z.optString("label"),
                    tz = z.optString("tz"),
                    primary = z.optBoolean("primary", false),
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        const val MANIFEST = "manifest.json"
        const val BUILD = "build.json"
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}

private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(action)
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let { out += it }
    return out
}
