package org.vestaopensource.nebulapdf.core

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Traduce un texto corto (un párrafo). */
fun interface TextTranslator {
    suspend fun translate(text: String): String
}

/** OCR de una página sin texto: devuelve renglones en puntos PDF (origen arriba a la izquierda). */
fun interface PageOcr {
    suspend fun recognize(pageIndex: Int, pageWidth: Float, pageHeight: Float): List<TextLine>
}

data class Progress(val page: Int, val totalPages: Int, val message: String)

data class Summary(val pages: Int, val translatedBlocks: Int, val keptBlocks: Int, val ocrPages: Int, val skippedPages: Int)

/**
 * Traduce un PDF completo conservando la estructura: cada página de salida es la página original
 * con el texto sustituido por su traducción en el mismo lugar.
 * Está pensado para libros largos: procesa página a página y usa archivos temporales en vez de RAM.
 */
class BookTranslator(
    private val translator: TextTranslator,
    private val protector: TermProtector,
    private val ocr: PageOcr? = null,
) {
    private val cache = object : LinkedHashMap<String, String?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 4000
    }

    suspend fun run(input: File, output: File, onProgress: suspend (Progress) -> Unit): Summary {
        var translated = 0
        var kept = 0
        var ocrPages = 0
        var skipped = 0
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val total = doc.numberOfPages
            val extractor = BlockExtractor()
            val rewriter = PageRewriter(doc)
            for (i in 0 until total) {
                currentCoroutineContext().ensureActive()
                onProgress(Progress(i + 1, total, "Traduciendo página ${i + 1} de $total"))
                val page = doc.getPage(i)
                if (page.rotation % 360 != 0) { skipped++; continue }

                val analysis = try { extractor.extract(doc, i) } catch (e: Exception) { null }
                if (analysis == null) { skipped++; continue }
                var blocks = analysis.blocks
                var fromOcr = false
                if (blocks.isEmpty() && ocr != null) {
                    val box = page.cropBox
                    val lines = try { ocr.recognize(i, box.width, box.height) } catch (e: Exception) { emptyList() }
                    blocks = BlockExtractor.group(lines)
                    fromOcr = blocks.isNotEmpty()
                    if (fromOcr) ocrPages++
                }
                if (blocks.isEmpty()) continue

                // Si hay texto girado no podemos quitarlo con seguridad: tapamos y escribimos encima
                val canStrip = !fromOcr && analysis.rotatedGlyphs == 0
                val placed = blocks.map { b ->
                    currentCoroutineContext().ensureActive()
                    val t = translateBlock(b)
                    if (t == null) kept++ else translated++
                    PlacedBlock(b, t, cover = fromOcr || b.invisible || (!canStrip && t != null))
                }
                // En modo "tapar", los bloques que no cambian se dejan como están
                val toDraw = if (canStrip) placed else placed.filter { it.translated != null }
                if (canStrip) rewriter.stripText(page)
                rewriter.draw(page, toDraw)
            }
            onProgress(Progress(total, total, "Guardando PDF…"))
            doc.save(output)
            return Summary(total, translated, kept, ocrPages, skipped)
        }
    }

    /** Devuelve la traducción del bloque, o null si debe quedarse igual (código, comandos, números…). */
    private suspend fun translateBlock(b: TextBlock): String? {
        if (b.style.mono) return null
        val text = b.text
        if (!TextCleaner.isTranslatable(text) || TextCleaner.looksLikeCode(text) || protector.isFullyTechnical(text)) return null
        if (cache.containsKey(text)) return cache[text]
        val p = protector.protect(text)
        val parts = mutableListOf<String>()
        for (para in p.text.split('\n')) {
            if (!TextCleaner.isTranslatable(para.replace(PLACEHOLDER, ""))) { parts += para; continue }
            val pieces = mutableListOf<String>()
            for (chunk in TextCleaner.chunks(para)) pieces += translator.translate(chunk).trim()
            parts += pieces.joinToString(" ")
        }
        val out = parts.joinToString("\n")
        val result = protector.restore(out, p.saved).takeIf { it.isNotBlank() && it != text }
        cache[text] = result
        return result
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{\{\d+\}\}""")
    }
}
