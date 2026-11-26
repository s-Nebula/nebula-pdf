package org.vestaopensource.nebulapdf.core

/** Utilidades para unir renglones y partir textos largos. */
object TextCleaner {
    private val BULLET = Regex("""^([•▪●◦‣∙·*]|\(?\d{1,3}[.)]|\(?[a-zA-Z][.)])\s""")

    /** Une renglones de un mismo párrafo, deshaciendo guiones de corte de palabra. */
    fun join(lines: List<String>): String {
        val sb = StringBuilder()
        lines.map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, line ->
            if (i == 0) { sb.append(line); return@forEachIndexed }
            val prev = sb.toString()
            when {
                BULLET.containsMatchIn(line) -> sb.append('\n').append(line)
                prev.length > 1 && prev.endsWith("-") && prev[prev.length - 2].isLowerCase() &&
                    line.first().isLowerCase() -> { sb.setLength(sb.length - 1); sb.append(line) }
                else -> sb.append(' ').append(line)
            }
        }
        return sb.toString()
    }

    /** Divide un texto largo por oraciones para el traductor. */
    fun chunks(text: String, max: Int = 1200): List<String> {
        if (text.length <= max) return listOf(text)
        val sentences = text.split(Regex("""(?<=[.!?;])\s+"""))
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length + 1 > max) { out += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private const val CODE_CHARS = "{}();=<>\"'+[]|&$\\_/*#"
    private val CODE_START = Regex("""^(\$|#|>|PS [A-Z]:|C:\\|msf\d? |meterpreter|root@|[\w.-]+@[\w.-]+:|sudo |SELECT |INSERT |UPDATE |DELETE |<\?php|<script|import |def |function |var |let |const |public |private |class |#include)""")

    /** ¿Parece código fuente o una línea de comandos? Entonces no se traduce. */
    fun looksLikeCode(text: String): Boolean {
        val t = text.trim()
        if (CODE_START.containsMatchIn(t)) return true
        val symbols = t.count { it in CODE_CHARS }
        return t.length >= 12 && symbols.toFloat() / t.length > 0.09f
    }

    /** ¿Tiene letras que valga la pena traducir? */
    fun isTranslatable(text: String): Boolean = text.count { it.isLetter() } >= 2
}

/**
 * Protege términos que deben quedarse en inglés (glosario de ciberseguridad, comandos, rutas,
 * URLs, CVE, siglas, identificadores de código…) reemplazándolos por marcadores que el traductor
 * no toca, y los restaura después.
 */
class TermProtector(glossary: Collection<String>) {

    private val glossaryRegex: Regex? = glossary
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|") { Regex.escape(it).replace(" ", "\\E\\s+\\Q").replace("\\Q\\E", "") }
        ?.let { Regex("""(?<![\p{L}\p{N}_])(?:$it)(?:s|es)?(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE) }

    /** Patrones técnicos que nunca se traducen. */
    private val technical = listOf(
        Regex("""(?:https?|ftp|ssh|smb|ldap|file)://\S+|www\.\S+"""),
        Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+"""),
        Regex("""\b(?:CVE|CWE|CAPEC|GHSA|MS\d{2})-[\w-]+""", RegexOption.IGNORE_CASE),
        Regex("""\bT\d{4}(?:\.\d{3})?\b"""), // técnicas MITRE ATT&CK
        Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?:/\d{1,2})?(?::\d+)?\b"""), // IPv4
        Regex("""\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b"""), // IPv6
        Regex("""\b0x[0-9a-fA-F]+\b"""),
        Regex("""\b[a-fA-F0-9]{32,}\b"""), // hashes
        Regex("""(?:~|\.{1,2})?(?:/[\w.@%+-]+){2,}/?"""), // rutas Unix
        Regex("""\b[A-Za-z]:\\[^\s,;]+"""), // rutas Windows
        Regex("""\bHK(?:LM|CU|CR|U|CC)\\[^\s,;]+"""), // registro de Windows
        Regex("""(?<![\w-])--?[a-zA-Z][\w-]*(?:=\S+)?"""), // opciones de línea de comandos
        Regex("""\${'$'}\{?[A-Za-z_]\w*\}?|%[A-Za-z_]\w*%"""), // variables
        Regex("""<[\w-]+(?:\s[^<>]*)?/?>"""), // etiquetas HTML/XML
        Regex("""\b\w+(?:\.\w+)*\([^()\n]{0,40}\)"""), // llamadas a funciones
        Regex("""\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\b""", RegexOption.IGNORE_CASE), // snake_case
        Regex("""\b[a-z]+(?:[A-Z][a-z0-9]*)+\b"""), // camelCase
        Regex("""\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+\b"""), // PascalCase (PowerShell, BloodHound)
        Regex("""\b[\w-]+\.(?:exe|dll|sys|py|sh|ps1|psm1|bat|cmd|vbs|txt|php|aspx?|jsp|js|ts|html?|conf|cfg|ini|xml|json|ya?ml|c|h|cpp|java|jar|class|apk|dex|elf|bin|so|zip|tar|gz|7z|pdf|docx?|xlsx?|db|sqlite|sql|log|rb|go|pl|rs|kt|pem|crt|key|pcap|pcapng)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b[\w-]+(?:\.[\w-]+)*\.(?:com|org|net|io|local|corp|lan|gov|edu|co|htb|thm)\b"""), // dominios
        Regex("""`[^`\n]+`"""),
    )

    private val acronym = Regex("""\b[A-Z][A-Z0-9&]{1,7}(?:-[A-Z0-9]+)*s?\b""")

    class Protected(val text: String, val saved: List<String>)

    fun protect(input: String): Protected {
        val saved = mutableListOf<String>()
        var text = input
        fun replace(r: Regex) {
            text = r.replace(text) { m ->
                if (m.value.startsWith("{{")) m.value
                else { saved += m.value; "{{${saved.size - 1}}}" }
            }
        }
        technical.forEach(::replace)
        glossaryRegex?.let(::replace)
        // siglas (TCP, XSS, SQLi…) salvo cuando todo el texto está en mayúsculas (títulos)
        val letters = input.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.count { it.isUpperCase() } < letters.length * 0.6) replace(acronym)
        return Protected(text, saved)
    }

    fun restore(translated: String, saved: List<String>): String {
        if (saved.isEmpty()) return translated
        val used = BooleanArray(saved.size)
        var out = PLACEHOLDER.replace(translated) { m ->
            val i = m.groupValues[1].toIntOrNull()
            if (i != null && i in saved.indices) { used[i] = true; saved[i] } else m.value
        }
        // Si el traductor perdió algún marcador, lo añadimos al final para no perder información
        val lost = saved.indices.filter { !used[it] }.map { saved[it] }
        if (lost.isNotEmpty()) out = out.trimEnd() + " (" + lost.joinToString(", ") + ")"
        return out
    }

    /** ¿El texto entero es técnico (comando, ruta, URL…) y no hay nada que traducir? */
    fun isFullyTechnical(text: String): Boolean {
        val p = protect(text)
        val rest = PLACEHOLDER.replace(p.text, "")
        return rest.count { it.isLetter() } < 2
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{\s*\{\s*(\d+)\s*\}\s*\}""")

        /** Glosario por defecto para libros de ciberseguridad. */
        val DEFAULT_GLOSSARY = """
            exploit
            payload
            shellcode
            shell
            reverse shell
            bind shell
            web shell
            webshell
            backdoor
            rootkit
            bootkit
            malware
            ransomware
            spyware
            adware
            keylogger
            botnet
            trojan
            worm
            dropper
            loader
            stager
            implant
            beacon
            firewall
            proxy
            phishing
            spear phishing
            whaling
            vishing
            smishing
            pharming
            spoofing
            sniffing
            sniffer
            hacking
            hacker
            pentest
            pentesting
            pentester
            red team
            blue team
            purple team
            red teaming
            threat hunting
            threat intelligence
            bug bounty
            zero-day
            0-day
            buffer overflow
            stack overflow
            heap overflow
            heap spraying
            use-after-free
            format string
            SQL injection
            blind SQL injection
            command injection
            code injection
            LDAP injection
            cross-site scripting
            cross-site request forgery
            server-side request forgery
            server-side template injection
            remote code execution
            local file inclusion
            remote file inclusion
            path traversal
            directory traversal
            man-in-the-middle
            adversary-in-the-middle
            password spraying
            credential stuffing
            pass-the-hash
            pass-the-ticket
            overpass-the-hash
            golden ticket
            silver ticket
            kerberoasting
            AS-REP roasting
            DCSync
            pivoting
            port forwarding
            tunneling
            fuzzing
            fuzzer
            debugger
            disassembler
            decompiler
            sandbox
            honeypot
            honeynet
            endpoint
            framework
            plugin
            script
            scripting
            token
            hash
            hashing
            salt
            nonce
            cookie
            session hijacking
            clickjacking
            jailbreak
            root
            rooting
            sudo
            kernel
            bootloader
            firmware
            footprinting
            fingerprinting
            banner grabbing
            wordlist
            cracking
            dump
            dumping
            backend
            frontend
            middleware
            payloads
            gadget
            ROP chain
            return-oriented programming
            ASLR
            DEP
            heap
            stack
            opcode
            syscall
            hook
            hooking
            callback
            handshake
            exploit kit
            command and control
            kill chain
            Cyber Kill Chain
            Active Directory
            Domain Controller
            Group Policy
            Blue Team
            Metasploit
            Meterpreter
            msfvenom
            msfconsole
            Burp Suite
            Burp
            Nmap
            Nessus
            OpenVAS
            Wireshark
            tcpdump
            John the Ripper
            Hashcat
            Hydra
            Medusa
            Mimikatz
            BloodHound
            SharpHound
            Responder
            Impacket
            CrackMapExec
            NetExec
            Evil-WinRM
            sqlmap
            Nikto
            Gobuster
            dirb
            dirbuster
            ffuf
            wfuzz
            Netcat
            ncat
            socat
            Kali Linux
            Parrot OS
            Cobalt Strike
            Sliver
            Empire
            PowerShell
            PowerSploit
            PowerView
            Ghidra
            IDA Pro
            Radare2
            Binary Ninja
            OllyDbg
            x64dbg
            WinDbg
            GDB
            pwntools
            Volatility
            Autopsy
            Aircrack-ng
            Shodan
            Maltego
            theHarvester
            Recon-ng
            OWASP
            MITRE ATT&CK
            Docker
            Kubernetes
            Linux
            Windows
            macOS
            Android
            Bash
            Python
            Ruby
            Perl
            JavaScript
            GitHub
        """.trimIndent()
    }
}
