package com.qdvc.paperpod.text

import android.graphics.Typeface
import java.io.File

/**
 * Discovers typefaces shipped inside the payload's `fonts/` directory, so adding
 * a face is a matter of dropping files into the bundle rather than rebuilding
 * the APK.
 *
 * Style is inferred from the filename per PAYLOAD-SPEC.md, which means the two
 * common upstream naming conventions both work without a metadata file:
 *
 *   AtkinsonHyperlegible/atkinsonhyperlegible_bold_italic.ttf
 *   DMSans/DMSans-BoldItalic.ttf
 */
object FontRegistry {

    data class Family(
        val name: String,
        val dirName: String,
        val regular: Typeface,
        val bold: Typeface?,
        val italic: Typeface?,
        val boldItalic: Typeface?,
    ) {
        fun typeface(bold: Boolean, italic: Boolean): Typeface = when {
            bold && italic -> boldItalic
                ?: this.bold?.let { Typeface.create(it, Typeface.ITALIC) }
                ?: Typeface.create(regular, Typeface.BOLD_ITALIC)
            bold -> this.bold ?: Typeface.create(regular, Typeface.BOLD)
            italic -> this.italic ?: Typeface.create(regular, Typeface.ITALIC)
            else -> regular
        }
    }

    private var loadedFrom: String? = null
    private val families = LinkedHashMap<String, Family>()

    /** System default, always offered so a broken payload can't leave you fontless. */
    const val SYSTEM = "System default"

    @Synchronized
    fun load(payloadRoot: File?, force: Boolean = false) {
        val key = payloadRoot?.absolutePath
        if (!force && key == loadedFrom && families.isNotEmpty()) return
        families.clear()
        loadedFrom = key
        val fontsDir = payloadRoot?.let { File(it, "fonts") } ?: return
        if (!fontsDir.isDirectory) return
        fontsDir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { dir ->
            if (dir.isDirectory) loadFamily(dir)?.let { families[it.name] = it }
        }
    }

    private fun loadFamily(dir: File): Family? {
        var regular: File? = null
        var bold: File? = null
        var italic: File? = null
        var boldItalic: File? = null

        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (!f.isFile) return@forEach
            val ext = f.extension.lowercase()
            if (ext != "ttf" && ext != "otf") return@forEach
            val n = f.nameWithoutExtension.lowercase()
            val isBold = n.contains("bold")
            val isItalic = n.contains("italic") || n.contains("oblique")
            when {
                isBold && isItalic -> boldItalic = boldItalic ?: f
                isBold -> bold = bold ?: f
                isItalic -> italic = italic ?: f
                else -> regular = regular ?: f
            }
        }

        // A family with no upright face has nothing to set body text in.
        val reg = regular ?: return null
        val regTf = safeCreate(reg) ?: return null
        return Family(
            name = prettify(dir.name),
            dirName = dir.name,
            regular = regTf,
            bold = bold?.let { safeCreate(it) },
            italic = italic?.let { safeCreate(it) },
            boldItalic = boldItalic?.let { safeCreate(it) },
        )
    }

    private fun safeCreate(f: File): Typeface? =
        try { Typeface.createFromFile(f) } catch (e: Exception) { null }

    /** "AtkinsonHyperlegible" -> "Atkinson Hyperlegible"; "DM_Sans" -> "DM Sans". */
    internal fun prettify(dirName: String): String {
        val spaced = dirName
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
        return spaced.split(' ').filter { it.isNotBlank() }.joinToString(" ")
    }

    fun names(): List<String> = listOf(SYSTEM) + families.keys

    fun family(name: String?): Family? = families[name]

    fun typeface(name: String?, bold: Boolean = false, italic: Boolean = false): Typeface {
        val fam = families[name]
        if (fam != null) return fam.typeface(bold, italic)
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(Typeface.SERIF, style)
    }

    fun describe(name: String?): String {
        val fam = families[name] ?: return "system serif"
        val faces = buildList {
            add("regular")
            if (fam.bold != null) add("bold")
            if (fam.italic != null) add("italic")
            if (fam.boldItalic != null) add("bold italic")
        }
        return faces.joinToString(", ")
    }
}
