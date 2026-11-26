package org.vestaopensource.nebulapdf.core

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.max
import kotlin.math.min

/** Un bloque listo para dibujarse en la página traducida. */
class PlacedBlock(
    val source: TextBlock,
    /** Texto traducido, o null si el bloque se copia tal cual (código, comandos, números…). */
    val translated: String?,
    /** Tapar el fondo con blanco antes de escribir (texto escaneado o capa OCR invisible). */
    val cover: Boolean,
)

/**
 * Reescribe una página: quita el texto original y escribe la traducción en la misma posición,
 * con el mismo tamaño, color y tipo de letra aproximado. Imágenes, gráficos y fondo no se tocan.
 */
class PageRewriter(private val doc: PDDocument) {

    private val strippedForms = HashSet<Any>()

    /** Elimina los operadores que pintan texto (Tj, TJ, ', ") del contenido de la página. */
    fun stripText(page: PDPage) {
        val tokens = PDFStreamParser(page).also { it.parse() }.tokens
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(removeTextOps(tokens)) }
        page.setContents(stream)
        page.resources?.let { stripForms(it, 0) }
    }

    /** El texto también puede estar dentro de formularios XObject (encabezados, plantillas…). */
    private fun stripForms(resources: PDResources, depth: Int) {
        if (depth > 6) return
        for (name in resources.xObjectNames) {
            val xo = try { resources.getXObject(name) } catch (e: Exception) { null }
            if (xo !is PDFormXObject) continue
            val cos = xo.cosObject
            if (!strippedForms.add(cos)) continue
            try {
                val tokens = PDFStreamParser(xo).also { it.parse() }.tokens
                if (tokens.none { it is Operator && it.name in TEXT_OPS }) {
                    xo.resources?.let { stripForms(it, depth + 1) }
                    continue
                }
                val cleaned = removeTextOps(tokens)
                cos.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(cleaned) }
            } catch (e: Exception) {
                // si un formulario no se puede analizar, se deja como está
            }
            xo.resources?.let { stripForms(it, depth + 1) }
        }
    }

    private fun removeTextOps(tokens: List<Any?>): List<Any?> {
        val out = ArrayList<Any?>(tokens.size)
        val operands = ArrayList<Any?>()
        for (t in tokens) {
            if (t is Operator) {
                if (t.name !in TEXT_OPS) { out.addAll(operands); out.add(t) }
                operands.clear()
            } else operands.add(t)
        }
        out.addAll(operands)
        return out
    }

    /** Dibuja los bloques en la página. */
    fun draw(page: PDPage, blocks: List<PlacedBlock>) {
        if (blocks.isEmpty()) return
        val box = page.cropBox
        val pageW = box.width
        val pageH = box.height
        // Convierte coordenadas "desde arriba" a coordenadas PDF
        val ox = box.lowerLeftX
        val oy = box.upperRightY
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            for (pb in blocks) {
                val b = pb.source
                if (pb.cover) {
                    cs.setNonStrokingColor(1f, 1f, 1f)
                    val pad = b.fontSize * 0.15f
                    cs.addRect(ox + b.x0 - pad, oy - b.bottom - pad, (b.x1 - b.x0) + 2 * pad, (b.bottom - b.top) + 2 * pad)
                    cs.fill()
                }
                if (pb.translated == null) drawVerbatim(cs, b, ox, oy)
                else drawFlowed(cs, b, pb.translated, ox, oy, pageW, pageH, blocks)
            }
        }
    }

    /** Copia cada renglón original en su posición exacta (código, comandos, tablas numéricas…). */
    private fun drawVerbatim(cs: PDPageContentStream, b: TextBlock, ox: Float, oy: Float) {
        for (line in b.lines) {
            val font = Fonts.pick(line.style)
            val text = Fonts.sanitize(font, line.text)
            if (text.isBlank()) continue
            var size = line.fontSize
            val w = Fonts.width(font, text, size)
            val avail = (line.x1 - line.x0) * 1.08f + 2f
            if (w > avail && w > 0) size *= max(avail / w, 0.6f)
            cs.beginText()
            cs.setFont(font, size)
            setColor(cs, line.style.color)
            cs.setTextMatrix(Matrix.getTranslateInstance(ox + line.x0, oy - line.baseline))
            cs.showText(text)
            cs.endText()
        }
    }

    /** Reparte el texto traducido dentro del área del bloque original. */
    private fun drawFlowed(
        cs: PDPageContentStream, b: TextBlock, translated: String,
        ox: Float, oy: Float, pageW: Float, pageH: Float, all: List<PlacedBlock>,
    ) {
        val font = Fonts.pick(b.style)
        val text = Fonts.sanitize(font, translated)
        val base = b.fontSize
        val lines = b.lines
        var firstX = lines.first().x0
        var restX = if (lines.size > 1) lines[1].x0 else firstX
        var left = min(firstX, restX)
        var right = b.x1
        val ratio = b.leadingRatio
        val single = lines.size == 1
        val centered = isCentered(b, pageW)
        val rightAligned = !centered && isRightAligned(b)

        // Los renglones sueltos (títulos, etiquetas) pueden crecer a lo ancho hasta el bloque vecino
        if (single) {
            val sameRow = all.map { it.source }.filter { it !== b && it.top < b.bottom && it.bottom > b.top }
            val maxRight = min(pageW - 18f, sameRow.filter { it.x0 >= b.x1 - 1f }.minOfOrNull { it.x0 - 6f } ?: Float.MAX_VALUE)
            val minLeft = max(18f, sameRow.filter { it.x1 <= b.x0 + 1f }.maxOfOrNull { it.x1 + 6f } ?: 0f)
            val need = Fonts.width(font, text, base) - (right - left)
            if (need > 0) {
                when {
                    centered -> { left = max(minLeft, left - need / 2); right = min(maxRight, right + need / 2) }
                    rightAligned -> left = max(minLeft, left - need)
                    else -> right = min(maxRight, right + need)
                }
            }
            firstX = left
        } else if (rightAligned) {
            // Bloques alineados a la derecha (subtítulos de portada): pueden crecer hacia la izquierda
            val sameRows = all.map { it.source }.filter { it !== b && it.top < b.bottom && it.bottom > b.top && it.x1 <= b.x0 + 1f }
            val minLeft = max(18f, sameRows.maxOfOrNull { it.x1 + 6f } ?: 0f)
            left = max(minLeft, b.x1 - (b.x1 - left) * 1.35f)
            firstX = left
            restX = left
        }

        // Alto disponible: hasta el siguiente bloque de la misma columna o el margen inferior
        val below = all.asSequence().map { it.source }
            .filter { it !== b && it.top >= b.bottom - 0.5f && min(it.x1, right) - max(it.x0, left) > 0 }
            .minOfOrNull { it.top }
        val limitBottom = min(below?.minus(base * 0.1f) ?: (pageH - 14f), pageH - 8f)
        val firstBaseline = lines.first().baseline
        val availH = max(limitBottom - (firstBaseline - base * 0.85f), b.bottom - b.top)

        // Busca el tamaño de letra más grande que quepa (mínimo 60 % del original)
        var size = base
        var wrapped: List<String>
        if (single) {
            // Un renglón sigue siendo un renglón: se reduce la letra en vez de partirlo
            val line = text.replace('\n', ' ')
            val w = Fonts.width(font, line, base)
            if (w > right - left && w > 0) size = max(base * (right - left) / w, base * 0.45f)
            wrapped = listOf(line)
        } else while (true) {
            wrapped = wrap(font, text, size, right - firstX.coerceAtLeast(left), right - restX.coerceAtLeast(left))
            val h = size * 0.85f + (wrapped.size - 1) * size * ratio + size * 0.25f
            if (h <= availH || size <= max(base * 0.6f, 4f)) break
            size -= max(base * 0.03f, 0.2f)
        }

        val justify = !centered && isJustified(b)
        cs.beginText()
        cs.setFont(font, size)
        setColor(cs, b.style.color)
        wrapped.forEachIndexed { i, line ->
            val x0 = if (i == 0) firstX.coerceAtLeast(left) else restX.coerceAtLeast(left)
            val width = right - x0
            val w = Fonts.width(font, line, size)
            val x = when {
                centered -> (if (single) (left + right) / 2 else (b.x0 + b.x1) / 2) - w / 2
                rightAligned -> right - w
                else -> x0
            }
            val y = firstBaseline + i * size * ratio
            val last = i == wrapped.lastIndex || line.endsWith("\n")
            val spaces = line.count { it == ' ' }
            val tw = if (justify && !last && spaces > 0 && w < width) (width - w) / spaces else 0f
            cs.setWordSpacing(tw.coerceAtMost(size * 0.6f))
            cs.setTextMatrix(Matrix.getTranslateInstance(ox + x, oy - y))
            cs.showText(line.trimEnd('\n'))
        }
        cs.setWordSpacing(0f)
        cs.endText()
    }

    private fun isRightAligned(b: TextBlock): Boolean {
        val lines = b.lines
        if (lines.size < 2) return false
        val varied = lines.maxOf { it.x0 } - lines.minOf { it.x0 } > b.fontSize
        return varied && lines.all { b.x1 - it.x1 < b.fontSize * 0.4f }
    }

    private fun isCentered(b: TextBlock, pageW: Float): Boolean {
        val lines = b.lines
        if (lines.size == 1) {
            val l = lines[0]
            val c = (l.x0 + l.x1) / 2
            return kotlin.math.abs(c - pageW / 2) < pageW * 0.03f && l.x0 > pageW * 0.15f
        }
        val c = (b.x0 + b.x1) / 2
        val varied = lines.maxOf { it.x0 } - lines.minOf { it.x0 } > b.fontSize
        return varied && lines.all { kotlin.math.abs((it.x0 + it.x1) / 2 - c) < b.fontSize * 0.6f }
    }

    private fun isJustified(b: TextBlock): Boolean {
        if (b.lines.size < 4) return false
        val body = b.lines.dropLast(1)
        val tol = (b.x1 - b.x0) * 0.015f + 0.5f
        return body.count { b.x1 - it.x1 < tol } >= body.size * 0.8f
    }

    private fun setColor(cs: PDPageContentStream, rgb: Int) {
        cs.setNonStrokingColor(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)
    }

    /** Parte el texto en renglones según el ancho; respeta los saltos de línea (listas). */
    private fun wrap(font: PDFont, text: String, size: Float, firstWidth: Float, restWidth: Float): List<String> {
        val out = mutableListOf<String>()
        val paragraphs = text.split('\n')
        paragraphs.forEachIndexed { pi, para ->
            var cur = StringBuilder()
            for (word in para.split(' ').filter { it.isNotEmpty() }) {
                val limit = if (out.isEmpty()) firstWidth else restWidth
                val candidate = if (cur.isEmpty()) word else "$cur $word"
                if (cur.isNotEmpty() && Fonts.width(font, candidate, size) > limit) {
                    out += cur.toString(); cur = StringBuilder(word)
                } else {
                    cur = StringBuilder(candidate)
                }
            }
            // marcamos el fin de párrafo con \n para no justificar esa línea
            out += cur.toString() + if (pi < paragraphs.lastIndex) "\n" else ""
        }
        return out.filter { it.isNotEmpty() }
    }

    companion object {
        private val TEXT_OPS = setOf("Tj", "TJ", "'", "\"")
    }
}

/** Fuentes estándar del PDF (no hay que incrustarlas y cubren todos los acentos del español). */
object Fonts {
    fun pick(s: TextStyle): PDFont = when {
        s.mono && s.bold && s.italic -> PDType1Font.COURIER_BOLD_OBLIQUE
        s.mono && s.bold -> PDType1Font.COURIER_BOLD
        s.mono && s.italic -> PDType1Font.COURIER_OBLIQUE
        s.mono -> PDType1Font.COURIER
        s.serif && s.bold && s.italic -> PDType1Font.TIMES_BOLD_ITALIC
        s.serif && s.bold -> PDType1Font.TIMES_BOLD
        s.serif && s.italic -> PDType1Font.TIMES_ITALIC
        s.serif -> PDType1Font.TIMES_ROMAN
        s.bold && s.italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
        s.bold -> PDType1Font.HELVETICA_BOLD
        s.italic -> PDType1Font.HELVETICA_OBLIQUE
        else -> PDType1Font.HELVETICA
    }

    private val supported = HashMap<Pair<String, Char>, Boolean>()

    private val replacements = mapOf(
        '→' to "->", '←' to "<-", '⇒' to "=>", '≤' to "<=", '≥' to ">=", '≠' to "!=", '✓' to "v",
        '✔' to "v", '✗' to "x", '✘' to "x", '★' to "*", '▪' to "•", '●' to "•", '◦' to "•", '‣' to "•",
        '∙' to "•", ' ' to " ", ' ' to " ", '​' to "", '�' to "?", '−' to "-", '‐' to "-",
        '‑' to "-", '′' to "'", '″' to "\"", '…' to "...",
    )

    /** Reemplaza caracteres que la fuente estándar no puede mostrar. */
    fun sanitize(font: PDFont, text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (c == '\n') { sb.append(c); continue }
            if (c.isISOControl()) continue
            val ok = supported.getOrPut(font.name to c) {
                try { font.encode(c.toString()); true } catch (e: Exception) { false }
            }
            when {
                ok -> sb.append(c)
                replacements.containsKey(c) -> sb.append(sanitize(font, replacements.getValue(c)))
                else -> {
                    val plain = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFKD)
                        .filter { it.code < 128 && !it.isISOControl() }
                    sb.append(plain.ifEmpty { "?" })
                }
            }
        }
        return sb.toString()
    }

    fun width(font: PDFont, text: String, size: Float): Float =
        try { font.getStringWidth(text.trimEnd('\n')) / 1000f * size } catch (e: Exception) { text.length * size * 0.5f }
}
