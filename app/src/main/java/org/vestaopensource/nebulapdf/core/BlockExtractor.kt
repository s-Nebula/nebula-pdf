package org.vestaopensource.nebulapdf.core

import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Estilo visual de un fragmento de texto. */
data class TextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val mono: Boolean = false,
    val serif: Boolean = false,
    val color: Int = 0x000000,
)

/**
 * Un renglón (o trozo de renglón, si la página tiene columnas) con su geometría.
 * Coordenadas en puntos, origen arriba a la izquierda de la página visible.
 */
class TextLine(
    val text: String,
    val x0: Float,
    val x1: Float,
    val baseline: Float,
    val fontSize: Float,
    val style: TextStyle,
    val invisible: Boolean,
)

/** Párrafo, título, celda o bloque de código: la unidad que se traduce y se recoloca. */
class TextBlock(first: TextLine) {
    val lines = mutableListOf(first)
    val style get() = lines.first().style
    val fontSize get() = lines.map { it.fontSize }.sorted()[lines.size / 2]
    val x0 get() = lines.minOf { it.x0 }
    val x1 get() = lines.maxOf { it.x1 }
    val top get() = lines.first().baseline - fontSize * 0.85f
    val bottom get() = lines.last().baseline + fontSize * 0.25f
    val invisible get() = lines.count { it.invisible } * 2 > lines.size

    /** Distancia media entre renglones respecto al tamaño de letra. */
    val leadingRatio: Float
        get() {
            if (lines.size < 2) return 1.2f
            val gaps = lines.zipWithNext { a, b -> b.baseline - a.baseline }.filter { it > 0 }
            if (gaps.isEmpty()) return 1.2f
            return (gaps.average().toFloat() / fontSize).coerceIn(1.0f, 2.2f)
        }

    val text: String get() = TextCleaner.join(lines.map { it.text })
}

/** Resultado del análisis de una página. */
class PageText(val blocks: List<TextBlock>, val rotatedGlyphs: Int, val totalGlyphs: Int)

/**
 * Extrae renglones con posición, tamaño, fuente y color usando PDFBox y los agrupa en bloques.
 */
class BlockExtractor : PDFTextStripper() {

    private class Extra(val color: Int, val invisible: Boolean)

    private val extras = IdentityHashMap<TextPosition, Extra>()
    private val words = mutableListOf<List<TextPosition>>()
    private val lines = mutableListOf<TextLine>()
    private var rotated = 0
    private var total = 0

    init {
        sortByPosition = true
        suppressDuplicateOverlappingText = true
        // El extractor de texto no sigue los colores por defecto: los registramos
        addOperator(SetNonStrokingColorSpace())
        addOperator(SetNonStrokingColor())
        addOperator(SetNonStrokingColorN())
        addOperator(SetNonStrokingDeviceRGBColor())
        addOperator(SetNonStrokingDeviceGrayColor())
        addOperator(SetNonStrokingDeviceCMYKColor())
    }

    fun extract(doc: PDDocument, pageIndex: Int): PageText {
        extras.clear(); words.clear(); lines.clear(); rotated = 0; total = 0
        startPage = pageIndex + 1
        endPage = pageIndex + 1
        writeText(doc, StringWriter())
        flushLine()
        val blocks = group(lines.toList())
        extras.clear()
        return PageText(blocks, rotated, total)
    }

    override fun processTextPosition(text: TextPosition) {
        total++
        if (text.dir != 0f) rotated++
        val gs = graphicsState
        val invisible = gs.textState.renderingMode == RenderingMode.NEITHER
        val color = try { gs.nonStrokingColor.toRGB() } catch (e: Exception) { 0 }
        extras[text] = Extra(color, invisible)
        super.processTextPosition(text)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        val visible = textPositions.filter { it.dir == 0f }
        if (visible.any { it.unicode.isNotBlank() }) words += visible
    }

    override fun writeLineSeparator() = flushLine()

    override fun writePageEnd() {
        flushLine()
        super.writePageEnd()
    }

    /** Convierte las palabras acumuladas en uno o varios renglones (corta en huecos de columna). */
    private fun flushLine() {
        if (words.isEmpty()) return
        // Trabajamos glifo a glifo: PDFBox a veces junta en una "palabra" texto de dos columnas
        val glyphs: List<TextPosition?> = words.flatMap { w -> w + listOf<TextPosition?>(null) }.dropLast(1)
        words.clear()
        val ink = glyphs.filterNotNull().filter { it.unicode.isNotBlank() }
        if (ink.isEmpty()) return
        val wordGaps = ink.zipWithNext { a, b -> b.xDirAdj - (a.xDirAdj + a.widthDirAdj) }
            .filterIndexed { i, g -> g > size(ink[i]) * 0.15f }.sorted()

        var segment = mutableListOf<TextPosition?>()
        var prev: TextPosition? = null
        for (g in glyphs) {
            if (g == null || g.unicode.isBlank()) { segment += g; continue }
            val p = prev
            if (p != null) {
                val gap = g.xDirAdj - (p.xDirAdj + p.widthDirAdj)
                val size = max(size(p), 4f)
                val typical = if (wordGaps.size >= 3) wordGaps[wordGaps.size / 2] else size * 0.3f
                val columnGap = gap > max(size * 0.9f, typical * 2.5f)
                val baselineJump = abs(g.yDirAdj - p.yDirAdj) > size * 0.9f
                val sizeChange = size(g) / size > 1.25f || size / max(size(g), 1f) > 1.25f
                val colorChange = extras[p]?.color != extras[g]?.color
                val styleBreak = gap > size * 0.5f && (sizeChange || colorChange)
                // una viñeta o número de lista seguido de un hueco no es una columna
                val onlyMarker = MARKER.matches(segment.filterNotNull().joinToString("") { it.unicode }.trim())
                if ((columnGap && !onlyMarker) || gap < -size * 2 || baselineJump || (styleBreak && !onlyMarker)) {
                    emit(segment); segment = mutableListOf()
                }
            }
            segment += g
            prev = g
        }
        emit(segment)
    }

    private fun emit(segment: List<TextPosition?>) {
        val all = segment.filterNotNull().filter { it.unicode.isNotBlank() }
        if (all.isEmpty()) return
        val sb = StringBuilder()
        var last: TextPosition? = null
        var pendingSpace = false
        for (g in segment) {
            if (g == null || g.unicode.isBlank()) { pendingSpace = true; continue }
            val l = last
            if (l != null) {
                val gap = g.xDirAdj - (l.xDirAdj + l.widthDirAdj)
                if (pendingSpace || gap > size(l) * 0.15f) sb.append(' ')
            }
            sb.append(g.unicode)
            last = g
            pendingSpace = false
        }
        val text = sb.toString().replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return
        val sizes = all.map { size(it) }.sorted()
        val baselines = all.map { it.yDirAdj }.sorted()
        lines += TextLine(
            text = text,
            x0 = all.minOf { it.xDirAdj },
            x1 = all.maxOf { it.xDirAdj + it.widthDirAdj },
            baseline = baselines[baselines.size / 2],
            fontSize = sizes[sizes.size / 2],
            style = styleOf(all.first()),
            invisible = all.count { extras[it]?.invisible == true } * 2 > all.size,
        )
    }

    private fun size(p: TextPosition): Float {
        val s = p.fontSizeInPt
        return if (s >= 1f) s else max(p.heightDir * 1.4f, 4f)
    }

    private fun styleOf(p: TextPosition): TextStyle {
        val font = p.font
        val name = (font?.name ?: "").lowercase()
        val fd = try { font?.fontDescriptor } catch (e: Exception) { null }
        val mono = MONO.containsMatchIn(name) || (fd?.isFixedPitch == true)
        val serif = !mono && (SERIF.containsMatchIn(name) || (fd?.isSerif == true && !SANS.containsMatchIn(name)))
        val bold = BOLD.containsMatchIn(name) || fd?.isForceBold == true || (fd?.fontWeight ?: 0f) >= 600f
        val italic = ITALIC.containsMatchIn(name) || fd?.isItalic == true
        return TextStyle(bold, italic, mono, serif, extras[p]?.color ?: 0)
    }

    companion object {
        private val MONO = Regex("courier|mono|consol|code|menlo|inconsolata|typewriter|cmtt|lucidaconsole|fixed")
        private val SERIF = Regex("times|serif|roman|georgia|garamond|minion|cambria|palatino|book|charter|cmr|utopia|baskerville|caslon")
        private val SANS = Regex("sans|arial|helvetica|verdana|calibri|segoe")
        private val BOLD = Regex("bold|black|heavy|semibold|demi|\\bbd\\b|-bd")
        private val ITALIC = Regex("italic|oblique|\\bit\\b|-it")

        private val MARKER = Regex("""[•▪●◦‣∙·\-–—*➢►✓✔o]|\(?\d{1,3}[.)]?|\(?[a-zA-Z][.)]""")
        private val BULLET = Regex("""^([•▪●◦‣∙·\-–—*]|\(?\d{1,3}[.)]|\(?[a-zA-Z][.)])\s""")

        /** Agrupa renglones en bloques: misma columna, mismo estilo y renglones consecutivos. */
        fun group(input: List<TextLine>): List<TextBlock> {
            val lines = input.sortedWith(compareBy<TextLine> { it.baseline }.thenBy { it.x0 })
            val blocks = mutableListOf<TextBlock>()
            val open = mutableListOf<TextBlock>()
            for (line in lines) {
                val target = open.lastOrNull { canJoin(it, line) }
                if (target != null) {
                    target.lines += line
                } else {
                    val b = TextBlock(line)
                    blocks += b
                    open += b
                }
                // cierra bloques que quedaron muy arriba
                open.removeAll { line.baseline - it.lines.last().baseline > it.fontSize * 3 }
            }
            return blocks
        }

        private fun canJoin(block: TextBlock, line: TextLine): Boolean {
            val last = block.lines.last()
            val size = block.fontSize
            if (last.style.mono != line.style.mono) return false
            if (abs(line.fontSize - size) > size * 0.18f) return false
            if (last.invisible != line.invisible) return false
            val gap = line.baseline - last.baseline
            if (gap <= size * 0.3f) return false
            val expected = if (block.lines.size >= 2) block.leadingRatio * size else size * 1.25f
            if (gap > max(expected * 1.35f, size * 1.15f) || gap > size * 2.0f) return false
            // misma columna: se solapan horizontalmente
            val overlap = min(block.x1, line.x1) - max(block.x0, line.x0)
            if (overlap < min(block.x1 - block.x0, line.x1 - line.x0) * 0.3f) return false
            // la línea empieza un elemento de lista o un párrafo nuevo
            if (!line.style.mono) {
                if (BULLET.containsMatchIn(line.text)) return false
                if (block.lines.size >= 2 && BULLET.containsMatchIn(block.lines.first().text) &&
                    abs(line.x0 - block.lines[1].x0) > size * 0.6f
                ) return false
                // título en negrita seguido de texto normal
                if (last.style.bold != line.style.bold && block.lines.size == 1) return false
                // el renglón anterior terminó claramente antes (fin de párrafo)
                val width = block.x1 - block.x0
                val endsSentence = last.text.endsWith(".") || last.text.endsWith(":") ||
                    last.text.endsWith("?") || last.text.endsWith("!")
                if (block.lines.size >= 2 && endsSentence && last.x1 < block.x0 + width * 0.8f) return false
                // sangría de primera línea: nuevo párrafo
                if (block.lines.size >= 2 && line.x0 - block.lines[1].x0 > size * 0.8f && endsSentence) return false
            }
            return true
        }
    }
}
