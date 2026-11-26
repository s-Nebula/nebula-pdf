package org.vestaopensource.nebulapdf.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import org.vestaopensource.nebulapdf.core.TextTranslator

/** Traductor inglés → español que funciona en el teléfono (ML Kit). Sin claves ni servidores. */
class MlKitTranslator : TextTranslator, AutoCloseable {

    private val client = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.SPANISH)
            .build()
    )

    /** Descarga el modelo de idioma (unos 30 MB) solo la primera vez. */
    suspend fun prepare() {
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    override suspend fun translate(text: String): String = client.translate(text).await()

    override fun close() = client.close()
}
