package org.vestaopensource.nebulapdf

import android.content.Context
import org.vestaopensource.nebulapdf.core.TermProtector

/** Preferencias del usuario: glosario de términos que se quedan en inglés y opciones. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("nebula", Context.MODE_PRIVATE)

    var glossary: String
        get() = prefs.getString("glossary", null) ?: TermProtector.DEFAULT_GLOSSARY
        set(value) = prefs.edit().putString("glossary", value).apply()

    var keepTechnicalTerms: Boolean
        get() = prefs.getBoolean("keepTerms", true)
        set(value) = prefs.edit().putBoolean("keepTerms", value).apply()

    var useOcr: Boolean
        get() = prefs.getBoolean("ocr", true)
        set(value) = prefs.edit().putBoolean("ocr", value).apply()

    fun glossaryTerms(): List<String> =
        if (keepTechnicalTerms) glossary.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        else emptyList()
}
