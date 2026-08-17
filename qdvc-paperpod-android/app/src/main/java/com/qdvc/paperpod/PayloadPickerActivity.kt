package com.qdvc.paperpod

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.qdvc.paperpod.data.PayloadRepository
import com.qdvc.paperpod.ui.Eink
import java.io.File

/**
 * Chooses the payload folder without making anyone type a path.
 *
 * This is a plain [File] browser rather than the system's
 * ACTION_OPEN_DOCUMENT_TREE picker, for two reasons. The system picker hands back
 * a content:// tree URI, and turning that into the filesystem path the rest of the
 * app works in means reverse-engineering document ids — a heuristic that breaks on
 * removable storage. And it is somebody else's UI: animated, grey on grey, and
 * built for scrolling, which is the worst combination this panel can be asked to
 * render. Since the app already holds all-files access, browsing directly is both
 * simpler and legible.
 *
 * Scanning is offered first because it is nearly always the right answer: a
 * payload announces itself with a manifest.json, so the app can find it rather
 * than asking where it is.
 */
class PayloadPickerActivity : AppCompatActivity() {

    private lateinit var listCol: LinearLayout
    private lateinit var pathLabel: TextView
    private lateinit var actionBar: LinearLayout

    private var current: File = Environment.getExternalStorageDirectory()
    private var scanning = false
    private var scanResults: List<File>? = null
    private var confirmingUnverified = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val start = intent.getStringExtra(EXTRA_START)?.let { File(it) }
        current = start?.takeIf { it.isDirectory }
            ?: PaperpodApp.repository(this).root
            ?: Environment.getExternalStorageDirectory()

        val root = Eink.column(this, 14f)

        root.addView(Eink.body(this, "Payload folder", sizeSp = 22f, bold = true))
        pathLabel = Eink.body(this, "", sizeSp = 12f).apply {
            setPadding(0, Eink.dp(this@PayloadPickerActivity, 2f), 0, Eink.dp(this@PayloadPickerActivity, 6f))
            // Long paths matter at the end, not the beginning.
            ellipsize = android.text.TextUtils.TruncateAt.START
            isSingleLine = true
        }
        root.addView(pathLabel)
        root.addView(Eink.rule(this, Eink.HEAVY_DP))

        listCol = Eink.column(this)
        root.addView(
            ScrollView(this).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isFillViewport = true
                addView(listCol, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(Eink.rule(this, Eink.HAIRLINE_DP, marginTopDp = 6f))
        actionBar = Eink.row(this).apply {
            setPadding(0, Eink.dp(this@PayloadPickerActivity, 8f), 0, 0)
        }
        root.addView(actionBar)

        setContentView(root)
        render()
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        pathLabel.text = if (scanResults != null) "Scan results" else current.absolutePath
        listCol.removeAllViews()
        actionBar.removeAllViews()

        when {
            !hasAllFilesAccess() -> renderPermissionNeeded()
            scanning -> renderScanning()
            scanResults != null -> renderScanResults()
            else -> renderBrowser()
        }
    }

    private fun renderPermissionNeeded() {
        val card = card()
        card.addView(Eink.body(this, "No access to storage yet", sizeSp = 17f, bold = true))
        card.addView(Eink.spacer(this, 6f))
        card.addView(Eink.body(
            this,
            "The payload is a plain folder maintained by your sync helper, which " +
                "scoped storage hides from other apps. Grant all-files access and " +
                "this browser will work.",
            sizeSp = 14f
        ))
        card.addView(Eink.spacer(this, 10f))
        card.addView(button("Grant file access") { requestAllFilesAccess() })
        listCol.addView(card, wide(12f))
        actionBar.addView(button("Cancel") { finish() })
    }

    private fun renderScanning() {
        listCol.addView(
            Eink.body(this, "Looking for a manifest.json\u2026", sizeSp = 16f, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, Eink.dp(this@PayloadPickerActivity, 40f), 0, 0)
            }
        )
        actionBar.addView(button("Cancel") { finish() })
    }

    private fun renderScanResults() {
        val found = scanResults.orEmpty()
        if (found.isEmpty()) {
            listCol.addView(card().apply {
                addView(Eink.body(this@PayloadPickerActivity, "No payload found", sizeSp = 17f, bold = true))
                addView(Eink.spacer(this@PayloadPickerActivity, 6f))
                addView(Eink.body(
                    this@PayloadPickerActivity,
                    "Nothing containing a manifest.json turned up. The scan only looks " +
                        "a few levels deep, so if your sync helper puts the payload " +
                        "somewhere buried, browse to it instead. Otherwise check that " +
                        "the helper has actually run.",
                    sizeSp = 14f
                ))
            }, wide(12f))
        } else {
            found.forEach { dir -> listCol.addView(payloadRow(dir), wide(7f)) }
        }
        actionBar.addView(button("Browse instead") { scanResults = null; render() })
        actionBar.addView(spacer())
        actionBar.addView(button("Scan again") { startScan() })
    }

    private fun renderBrowser() {
        val parent = current.parentFile
        if (parent != null && parent.canRead()) {
            listCol.addView(row("\u2191  ${parent.name.ifBlank { parent.absolutePath }}", "Up one level") {
                current = parent
                confirmingUnverified = false
                render()
            }, wide(7f))
        }

        val children = current.listFiles()
        if (children == null) {
            listCol.addView(card().apply {
                addView(Eink.body(this@PayloadPickerActivity, "Cannot read this folder", sizeSp = 16f, bold = true))
                addView(Eink.spacer(this@PayloadPickerActivity, 4f))
                addView(Eink.body(
                    this@PayloadPickerActivity,
                    "Android does not allow this app to list it. Go up a level and " +
                        "try a different route.",
                    sizeSp = 14f
                ))
            }, wide(12f))
        } else {
            val dirs = children
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
            if (dirs.isEmpty()) {
                listCol.addView(
                    Eink.body(this, "No sub-folders here.", sizeSp = 14f).apply {
                        setPadding(0, Eink.dp(this@PayloadPickerActivity, 16f), 0, 0)
                    }
                )
            }
            dirs.forEach { dir ->
                if (isPayload(dir)) {
                    listCol.addView(payloadRow(dir), wide(7f))
                } else {
                    listCol.addView(row(dir.name, describe(dir)) {
                        current = dir
                        confirmingUnverified = false
                        render()
                    }, wide(7f))
                }
            }
        }

        val here = isPayload(current)
        actionBar.addView(button("Scan") { startScan() })
        actionBar.addView(spacer())
        actionBar.addView(button("Cancel") { finish() })
        actionBar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(Eink.dp(this@PayloadPickerActivity, 8f), 1)
        })
        actionBar.addView(
            button(
                when {
                    here -> "Use this folder"
                    confirmingUnverified -> "Use anyway"
                    else -> "Use this folder"
                }
            ) { chooseCurrent(here) }
        )

        if (confirmingUnverified && !here) {
            // A second tap rather than a dialog: one refresh instead of two, and it
            // stays in the same visual language as the rest of the app.
            listCol.addView(card().apply {
                addView(Eink.body(
                    this@PayloadPickerActivity,
                    "No manifest.json in this folder",
                    sizeSp = 16f, bold = true
                ))
                addView(Eink.spacer(this@PayloadPickerActivity, 4f))
                addView(Eink.body(
                    this@PayloadPickerActivity,
                    "The app will not find anything to show here until a payload is " +
                        "synced into it. Tap \u201cUse anyway\u201d to select it regardless.",
                    sizeSp = 14f
                ))
            }, wide(12f))
        }
    }

    // ------------------------------------------------------------------ actions

    private fun chooseCurrent(verified: Boolean) {
        if (!verified && !confirmingUnverified) {
            confirmingUnverified = true
            render()
            return
        }
        choose(current)
    }

    private fun choose(dir: File) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, dir.absolutePath))
        finish()
    }

    private fun startScan() {
        scanning = true
        scanResults = null
        confirmingUnverified = false
        render()
        Thread {
            val found = scanForPayloads()
            handler.post {
                // The scan can outlive the screen if it is dismissed mid-walk.
                if (isFinishing || isDestroyed) return@post
                scanning = false
                scanResults = found
                render()
            }
        }.start()
    }

    /**
     * Breadth-first search for directories containing a manifest.json.
     *
     * Bounded on every axis — depth, directories visited, results — because an
     * unbounded walk of shared storage on slow flash would look identical to a
     * hang, and there is no spinner convincing enough to fix that.
     */
    private fun scanForPayloads(): List<File> {
        val results = mutableListOf<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        val seen = HashSet<String>()

        roots().forEach { root ->
            if (root.isDirectory && root.canRead()) queue.addLast(root to 0)
        }

        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITED && results.size < MAX_RESULTS) {
            val (dir, depth) = queue.removeFirst()
            visited++

            // Symlinked storage roots can otherwise send this round in circles.
            val canonical = try { dir.canonicalPath } catch (e: Exception) { dir.absolutePath }
            if (!seen.add(canonical)) continue

            if (isPayload(dir)) {
                results += dir
                continue  // no payloads nested inside payloads
            }
            if (depth >= MAX_DEPTH) continue

            dir.listFiles()?.forEach { child ->
                if (child.isDirectory && !child.name.startsWith(".") && child.name !in SKIP) {
                    queue.addLast(child to depth + 1)
                }
            }
        }
        return results.sortedBy { it.absolutePath }
    }

    private fun roots(): List<File> {
        val out = mutableListOf<File>()
        out += Environment.getExternalStorageDirectory()
        // Removable storage, where a large payload is quite likely to live.
        File("/storage").listFiles()?.forEach { volume ->
            if (volume.isDirectory && volume.name != "emulated" && volume.name != "self") {
                out += volume
            }
        }
        return out.distinctBy { it.absolutePath }
    }

    private fun isPayload(dir: File): Boolean =
        File(dir, PayloadRepository.MANIFEST).isFile

    private fun describe(dir: File): String {
        val children = dir.listFiles() ?: return "cannot read"
        val subdirs = children.count { it.isDirectory }
        val files = children.size - subdirs
        return buildList {
            if (subdirs > 0) add("$subdirs folder${if (subdirs == 1) "" else "s"}")
            if (files > 0) add("$files file${if (files == 1) "" else "s"}")
        }.joinToString(", ").ifBlank { "empty" }
    }

    // ------------------------------------------------------------------ widgets

    private fun row(title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Eink.outline(this@PayloadPickerActivity, Eink.HAIRLINE_DP)
            val p = Eink.dp(this@PayloadPickerActivity, 11f)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(Eink.body(this, title, sizeSp = 17f, bold = true))
        if (subtitle.isNotBlank()) {
            row.addView(Eink.body(this, subtitle, sizeSp = 12f))
        }
        return row
    }

    /** A found payload, marked with an inverted stamp and selectable in one tap. */
    private fun payloadRow(dir: File): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Eink.outline(this@PayloadPickerActivity, Eink.HEAVY_DP)
            val p = Eink.dp(this@PayloadPickerActivity, 11f)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener { choose(dir) }
        }
        val stamp = Eink.body(this, "PAYLOAD", sizeSp = 11f, bold = true).apply {
            background = Eink.invertedFill(this@PayloadPickerActivity)
            setTextColor(Eink.paper(this@PayloadPickerActivity))
            val h = Eink.dp(this@PayloadPickerActivity, 6f)
            val v = Eink.dp(this@PayloadPickerActivity, 4f)
            setPadding(h, v, h, v)
            letterSpacing = 0.1f
        }
        row.addView(stamp, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = Eink.dp(this@PayloadPickerActivity, 10f) })

        val col = Eink.column(this)
        col.addView(Eink.body(this, dir.name.ifBlank { dir.absolutePath }, sizeSp = 17f, bold = true))
        col.addView(Eink.body(this, dir.absolutePath, sizeSp = 11f).apply {
            ellipsize = android.text.TextUtils.TruncateAt.START
            isSingleLine = true
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Eink.outline(this@PayloadPickerActivity)
        val p = Eink.dp(this@PayloadPickerActivity, 12f)
        setPadding(p, p, p, p)
    }

    private fun button(label: String, onClick: () -> Unit): View =
        Eink.body(this, label, sizeSp = 15f, bold = true).apply {
            background = Eink.outline(this@PayloadPickerActivity, Eink.OUTLINE_DP)
            val h = Eink.dp(this@PayloadPickerActivity, 14f)
            val v = Eink.dp(this@PayloadPickerActivity, 9f)
            setPadding(h, v, h, v)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun wide(topDp: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Eink.dp(this@PayloadPickerActivity, topDp) }

    // -------------------------------------------------------------- permission

    override fun onResume() {
        super.onResume()
        // Coming back from the system permission screen, the browser should just work.
        render()
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    companion object {
        const val EXTRA_START = "startPath"
        const val EXTRA_RESULT = "chosenPath"

        private const val MAX_DEPTH = 5
        private const val MAX_VISITED = 6000
        private const val MAX_RESULTS = 25

        /** Directories that are large, uninteresting, or both. */
        private val SKIP = setOf("Android", "cache", "obb", "lost+found", "LOST.DIR")

        fun intent(context: android.content.Context, start: File?): Intent =
            Intent(context, PayloadPickerActivity::class.java).apply {
                start?.let { putExtra(EXTRA_START, it.absolutePath) }
            }
    }
}
