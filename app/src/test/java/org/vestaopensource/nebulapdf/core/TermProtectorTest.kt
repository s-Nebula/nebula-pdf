package org.vestaopensource.nebulapdf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermProtectorTest {
    private val protector = TermProtector(TermProtector.DEFAULT_GLOSSARY.lines())

    private fun roundTrip(text: String, fakeTranslate: (String) -> String = { it }): String {
        val p = protector.protect(text)
        return protector.restore(fakeTranslate(p.text), p.saved)
    }

    @Test fun keepsGlossaryTermsAndTools() {
        val p = protector.protect("Use Burp Suite to find a SQL injection and get a reverse shell.")
        assertTrue(p.saved.containsAll(listOf("Burp Suite", "SQL injection", "reverse shell")))
        assertFalse(p.text.contains("Burp"))
    }

    @Test fun keepsTechnicalPatterns() {
        val text = "Scan 10.10.10.5 with nmap -sV, see CVE-2021-44228 and /etc/passwd at https://example.com/a"
        val p = protector.protect(text)
        assertTrue(p.saved.contains("10.10.10.5"))
        assertTrue(p.saved.contains("-sV"))
        assertTrue(p.saved.contains("CVE-2021-44228"))
        assertTrue(p.saved.contains("/etc/passwd"))
        assertTrue(p.saved.any { it.startsWith("https://example.com") })
        assertEquals(text, roundTrip(text))
    }

    @Test fun restoresPlaceholdersWithSpacesAddedByTranslator() {
        val p = protector.protect("The payload is ready.")
        val translated = p.text.replace("The", "El").replace("{{0}}", "{{ 0 }}")
        assertTrue(protector.restore(translated, p.saved).contains("payload"))
    }

    @Test fun doesNotProtectAcronymsInAllCapsTitles() {
        val p = protector.protect("INTRODUCTION TO THE BOOK")
        assertTrue(p.saved.isEmpty())
    }

    @Test fun detectsCode() {
        assertTrue(TextCleaner.looksLikeCode("String query = \"SELECT * FROM accounts WHERE id='\" + id + \"'\";"))
        assertTrue(TextCleaner.looksLikeCode("$ sudo nmap -sS 10.0.0.1"))
        assertFalse(TextCleaner.looksLikeCode("This chapter explains how attackers think."))
    }

    @Test fun joinsHyphenatedLines() {
        assertEquals("vulnerability assessment", TextCleaner.join(listOf("vulner-", "ability assessment")))
    }
}
