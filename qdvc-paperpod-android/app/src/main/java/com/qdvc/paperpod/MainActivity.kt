package com.qdvc.paperpod

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.qdvc.paperpod.data.ModuleSpec
import com.qdvc.paperpod.modules.ModuleRegistry
import com.qdvc.paperpod.prefs.Prefs
import com.qdvc.paperpod.text.FontRegistry
import com.qdvc.paperpod.ui.Eink
import com.qdvc.paperpod.ui.PagedDocumentView
import com.qdvc.paperpod.ui.RailView

/**
 * A single activity hosting the rail and one module at a time.
 *
 * There is deliberately no drawer, no bottom bar and no gesture navigation. On a
 * display this slow, the cost of a wrong tap is a full second of your attention,
 * so every destination stays visible and in the same place.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rail: RailView
    private lateinit var root: LinearLayout
    private lateinit var content: android.widget.FrameLayout
    private lateinit var prefs: Prefs
    private var currentModuleId: String? = null
    private var railIsOnRight: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        root = findViewById(R.id.root)
        rail = findViewById(R.id.rail)
        content = findViewById(R.id.content)

        rail.setOnSelect { spec -> show(spec) }
        rail.setOnSettings { startActivity(Intent(this, SettingsActivity::class.java)) }

        applyRailSide()
        rebuildFromManifest(restoreId = savedInstanceState?.getString(KEY_MODULE))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MODULE, currentModuleId)
    }

    override fun onResume() {
        super.onResume()
        applyRailSide()
    }

    /**
     * Moves the rail to the reachable edge. Only the rail is detached — the
     * fragment container stays put, so switching sides never tears down the
     * module you were looking at.
     *
     * The width is taken from the dimension resource rather than left as
     * WRAP_CONTENT, so this path cannot reintroduce the full-width rail.
     */
    private fun applyRailSide() {
        val onRight = prefs.railOnRight
        if (railIsOnRight == onRight) return
        railIsOnRight = onRight
        root.removeView(rail)
        val railParams = LinearLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.rail_width),
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(rail, if (onRight) root.childCount else 0, railParams)
        content.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
        )
        rail.setEdge(onRight)
    }

    /** Re-reads the manifest and rebuilds the rail. Called after a payload reload. */
    fun rebuildFromManifest(restoreId: String? = null) {
        val repo = PaperpodApp.repository(this)
        val modules = repo.manifest?.modules ?: fallbackModules()
        FontRegistry.load(repo.root)
        rail.bind(modules, currentModuleId)

        val target = modules.firstOrNull { it.id == restoreId }
            ?: modules.firstOrNull { it.id == currentModuleId }
            ?: modules.firstOrNull { it.id == prefs.startModuleId }
            ?: modules.firstOrNull()
        if (target != null && currentModuleId != target.id) {
            show(target)
        } else if (target != null) {
            show(target, force = true)
        }
    }

    /**
     * If the manifest cannot be read we still show a Sync screen, because that is
     * the screen that explains why — a blank app would leave you guessing.
     */
    private fun fallbackModules(): List<ModuleSpec> = listOf(
        ModuleSpec("sync", "Sync", "sync", "sync", null)
    )

    private fun show(spec: ModuleSpec, force: Boolean = false) {
        if (!force && spec.id == currentModuleId) return
        currentModuleId = spec.id
        rail.setSelectedModule(spec.id)
        supportFragmentManager.let { fm ->
            // Clear any reader/figure stack so the rail always lands on the module.
            while (fm.backStackEntryCount > 0) fm.popBackStackImmediate()
            fm.beginTransaction()
                .setReorderingAllowed(false)
                .replace(R.id.content, ModuleRegistry.create(spec))
                .commit()
        }
    }

    /**
     * Hardware page keys, where the device has them, drive the reader. Physical
     * buttons are worth wiring up: they turn a page without a finger crossing the
     * panel and without a stray drag.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val pager = findPager()
        if (pager != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    pager.nextPage(); return true
                }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    pager.previousPage(); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun findPager(): PagedDocumentView? {
        val frag = supportFragmentManager.findFragmentById(R.id.content) ?: return null
        return findPagerIn(frag.view)
    }

    private fun findPagerIn(view: android.view.View?): PagedDocumentView? {
        if (view is PagedDocumentView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findPagerIn(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val KEY_MODULE = "currentModule"
    }
}
