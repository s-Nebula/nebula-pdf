package org.vestaopensource.nebulapdf.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.vestaopensource.nebulapdf.core.PageOcr
import org.vestaopensource.nebulapdf.core.TextLine
import org.vestaopensource.nebulapdf.core.TextStyle
import java.io.File

/** Reconoce el texto de páginas escaneadas (imágenes) para poder traducirlas. */
class MlKitOcr(file: File) : PageOcr, AutoCloseable {

    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val lock = Mutex()

    override suspend fun recognize(pageIndex: Int, pageWidth: Float, pageHeight: Float): List<TextLine> {
        if (pageIndex >= renderer.pageCount) return emptyList()
        val bitmap = lock.withLock {
            withContext(Dispatchers.IO) {
                renderer.openPage(pageIndex).use { page ->
                    val scale = (2200f / maxOf(page.width, page.height)).coerceIn(1f, 3f)
                    Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        .also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        }
                }
            }
        }
        try {
            // de píxeles a puntos PDF
            val sx = pageWidth / bitmap.width
            val sy = pageHeight / bitmap.height
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            return result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val h = box.height() * sy
                    TextLine(
                        text = line.text,
                        x0 = box.left * sx,
                        x1 = box.right * sx,
                        baseline = box.bottom * sy - h * 0.2f,
                        fontSize = (h / 1.15f).coerceAtLeast(4f),
                        style = TextStyle(),
                        invisible = true,
                    )
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        recognizer.close()
        renderer.close()
        pfd.close()
    }
}
