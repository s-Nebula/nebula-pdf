package org.vestaopensource.nebulapdf

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vestaopensource.nebulapdf.core.TermProtector
import org.vestaopensource.nebulapdf.ui.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Archivo elegido por el usuario, ya copiado al almacenamiento interno. */
data class Picked(val path: String, val name: String)

enum class Screen(val title: String) {
    Translator("Nebula PDF - Traductor"),
    Library("Mis traducciones"),
    Glossary("Glosario técnico"),
    About("Acerca de Nebula PDF"),
}

class MainActivity : ComponentActivity() {

    private val incoming = mutableStateOf<Picked?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncoming(intent)
        setContent { NebulaDesktop(incoming) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Permite abrir un PDF desde otra app ("Abrir con" o "Compartir"). */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> streamUri(intent)
            else -> null
        } ?: return
        lifecycleScope.launch { importPdf(this@MainActivity, uri)?.let { incoming.value = it } }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
}

/** Copia el PDF al almacenamiento interno para poder procesarlo en segundo plano. */
suspend fun importPdf(context: Context, uri: Uri): Picked? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "documento.pdf"
        val dir = File(context.cacheDir, "input").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "input-${System.currentTimeMillis()}.pdf")
        resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        Picked(file.absolutePath, name)
    }.getOrNull()
}

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private fun openPdf(context: Context, file: File) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(context, file), "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(i) } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No hay un lector de PDF instalado", Toast.LENGTH_LONG).show()
    }
}

private fun sharePdf(context: Context, file: File) {
    val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(i, "Compartir PDF traducido"))
}

private fun openLink(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
}

@Composable
fun AppIcon(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(22))
            .background(Brush.linearGradient(listOf(Color(0xFF0F0C29), Color(0xFF6A2C91), Color(0xFF302B63))))
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
    }
}

@Composable
fun NebulaDesktop(incoming: MutableState<Picked?>) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var screen by rememberSaveable { mutableStateOf(Screen.Translator) }
    var startOpen by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        while (true) { clock = fmt.format(Date()); delay(15_000) }
    }

    Desktop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)
                ) {
                    XpWindow(
                        title = screen.title,
                        icon = { AppIcon(Modifier.fillMaxSize()) },
                        onClose = if (screen != Screen.Translator) ({ screen = Screen.Translator }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        when (screen) {
                            Screen.Translator -> TranslatorScreen(settings, incoming) { screen = it }
                            Screen.Library -> LibraryScreen()
                            Screen.Glossary -> GlossaryScreen(settings) { screen = Screen.Translator }
                            Screen.About -> AboutScreen()
                        }
                    }
                }
                if (startOpen) {
                    Box(Modifier.fillMaxSize().clickable { startOpen = false })
                    XpStartMenu(
                        header = "Nebula PDF",
                        headerIcon = { AppIcon(Modifier.fillMaxSize()) },
                        items = listOf(
                            "Traducir un PDF" to { screen = Screen.Translator; startOpen = false },
                            "Mis traducciones" to { screen = Screen.Library; startOpen = false },
                            "Glosario técnico" to { screen = Screen.Glossary; startOpen = false },
                            "Acerca de" to { screen = Screen.About; startOpen = false },
                        ),
                        footer = "FORBIDDEN S.A.S. · VESTA SECURITY",
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }
            XpTaskbar(
                startLabel = "Inicio",
                onStart = { startOpen = !startOpen },
                clock = clock,
                startIcon = { AppIcon(Modifier.fillMaxSize()) },
            ) {
                XpTaskButton(screen.title, active = true) { startOpen = false }
            }
        }
    }
}

@Composable
private fun TranslatorScreen(settings: Settings, incoming: MutableState<Picked?>, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by TranslationState.state.collectAsStateWithLifecycle()
    var picked by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<Picked?, List<String>>(
        save = { p -> p?.let { listOf(it.path, it.name) } ?: emptyList() },
        restore = { l -> if (l.size == 2) Picked(l[0], l[1]) else null },
    )) { mutableStateOf<Picked?>(null) }
    var keepTerms by remember { mutableStateOf(settings.keepTechnicalTerms) }
    var useOcr by remember { mutableStateOf(settings.useOcr) }
    var loading by remember { mutableStateOf(false) }
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(incoming.value) {
        incoming.value?.let { picked = it; TranslationState.reset(); incoming.value = null }
    }
    val running = job is JobState.Running
    LaunchedEffect(running) { while (running) { tick++; delay(140) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            picked = importPdf(context, uri)
            loading = false
            if (picked == null) Toast.makeText(context, "No se pudo abrir el archivo", Toast.LENGTH_LONG).show()
            else TranslationState.reset()
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val done = TranslationState.state.value as? JobState.Done
        if (uri != null && done != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { out -> done.output.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "PDF guardado" else "No se pudo guardar", Toast.LENGTH_SHORT).show()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Traductor de PDF", style = Xp.body.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp))
            Text("Inglés → Español, conservando el diseño original", style = Xp.small)
        }
    }

    XpGroup("1. Documento en inglés") {
        XpField(
            when {
                loading -> "Cargando…"
                picked != null -> picked!!.name
                else -> "Ningún archivo seleccionado"
            },
            Modifier.fillMaxWidth(),
        )
        XpButton("Examinar…", onClick = { picker.launch(arrayOf("application/pdf")) }, enabled = !running && !loading)
    }

    XpGroup("2. Opciones") {
        XpCheckbox(keepTerms, "Conservar términos técnicos en inglés") { keepTerms = it; settings.keepTechnicalTerms = it }
        XpCheckbox(useOcr, "Reconocer texto en páginas escaneadas (OCR)") { useOcr = it; settings.useOcr = it }
        Text(
            "Editar glosario técnico…",
            style = Xp.body.copy(color = Xp.Link, textDecoration = TextDecoration.Underline),
            modifier = Modifier.clickable { navigate(Screen.Glossary) }
        )
    }

    XpGroup("3. Traducción") {
        when (val s = job) {
            is JobState.Running -> {
                Text(s.fileName, style = Xp.body.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                XpProgress(if (s.total > 0) s.page.toFloat() / s.total else null, tick = tick)
                Text(s.message, style = Xp.small)
                Text("Puedes salir de la app: la traducción sigue en segundo plano.", style = Xp.small.copy(color = Color(0xFF555555)))
                XpButton("Cancelar", onClick = { TranslationService.cancel(context) })
            }
            is JobState.Done -> {
                Text("¡Traducción terminada!", style = Xp.body.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1E7B1E)))
                Text(
                    "${s.summary.pages} páginas · ${s.summary.translatedBlocks} bloques traducidos · " +
                        "${s.summary.keptBlocks} conservados (código, términos, números)" +
                        (if (s.summary.ocrPages > 0) " · ${s.summary.ocrPages} páginas con OCR" else ""),
                    style = Xp.small,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XpButton("Abrir", onClick = { openPdf(context, s.output) }, default = true)
                    XpButton("Guardar como…", onClick = { saver.launch(s.output.name) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XpButton("Compartir", onClick = { sharePdf(context, s.output) })
                    XpButton("Traducir otro", onClick = { picked = null; TranslationState.reset() })
                }
            }
            is JobState.Failed -> {
                Text(s.message, style = Xp.body.copy(color = Color(0xFFB00020)))
                XpButton("Reintentar", onClick = {
                    picked?.let { p ->
                        if (File(p.path).exists()) TranslationService.start(context, File(p.path), p.name)
                        else Toast.makeText(context, "Vuelve a seleccionar el archivo", Toast.LENGTH_SHORT).show()
                    }
                }, enabled = picked != null)
            }
            JobState.Idle -> {
                Text(
                    "Los libros largos pueden tardar varios minutos. Verás el avance en la notificación.",
                    style = Xp.small,
                )
                XpButton(
                    "Traducir",
                    default = true,
                    enabled = picked != null && !loading,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        picked?.let { TranslationService.start(context, File(it.path), it.name) }
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryScreen() {
    val context = LocalContext.current
    var files by remember { mutableStateOf(listOf<File>()) }
    fun refresh() {
        files = File(context.filesDir, "translated").listFiles { f -> f.extension == "pdf" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    LaunchedEffect(Unit) { refresh() }
    if (files.isEmpty()) {
        Text("Todavía no has traducido ningún documento.", style = Xp.body)
        return
    }
    val fmt = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale("es")) }
    files.forEach { f ->
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color.White).border(1.dp, Xp.FieldBorder).padding(8.dp)
        ) {
            Text(f.name, style = Xp.body.copy(fontWeight = FontWeight.Bold), maxLines = 2)
            Text("${fmt.format(Date(f.lastModified()))} · ${"%.1f".format(f.length() / 1_048_576.0)} MB", style = Xp.small)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                XpButton("Abrir", onClick = { openPdf(context, f) })
                XpButton("Compartir", onClick = { sharePdf(context, f) })
                XpButton("Eliminar", onClick = { f.delete(); refresh() })
            }
        }
    }
}

@Composable
private fun GlossaryScreen(settings: Settings, onDone: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(settings.glossary) }
    Text(
        "Escribe un término por línea. Estas palabras se dejan en inglés al traducir. " +
            "Además, la app conserva siempre comandos, rutas, URLs, CVE, siglas y bloques de código.",
        style = Xp.small,
    )
    Spacer(Modifier.height(8.dp))
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        textStyle = Xp.body,
        modifier = Modifier.fillMaxWidth().height(340.dp).background(Color.White).border(1.dp, Xp.FieldBorder).padding(8.dp),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        XpButton("Aceptar", default = true, onClick = {
            settings.glossary = text
            Toast.makeText(context, "Glosario guardado", Toast.LENGTH_SHORT).show()
            onDone()
        })
        XpButton("Restaurar", onClick = { text = TermProtector.DEFAULT_GLOSSARY })
    }
}

@Composable
private fun AboutScreen() {
    val context = LocalContext.current
    @Composable
    fun link(label: String, url: String) = Text(
        label,
        style = Xp.body.copy(color = Xp.Link, textDecoration = TextDecoration.Underline),
        modifier = Modifier.clickable { openLink(context, url) }.padding(vertical = 2.dp)
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Modifier.size(72.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Nebula PDF", style = Xp.body.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp))
            Text("Versión ${BuildConfig.VERSION_NAME}", style = Xp.small)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Traduce documentos y libros en PDF del inglés al español manteniendo la misma estructura: " +
            "páginas, imágenes, columnas, tablas y código quedan en su lugar.",
        style = Xp.body,
    )
    XpGroup("Desarrollador") {
        Text("Nebula", style = Xp.body.copy(fontWeight = FontWeight.Bold))
        Text("FORBIDDEN S.A.S. · NIT 901653270-2", style = Xp.body)
        Text("Santa Marta, Colombia", style = Xp.body)
        Text("Parte del proyecto VESTA SECURITY", style = Xp.body)
    }
    XpGroup("Contacto") {
        link("nebulagnu@proton.me", "mailto:nebulagnu@proton.me")
        link("WhatsApp +57 324 616 7956", "https://wa.me/573246167956")
        link("vestasecurity.date", "https://vestasecurity.date")
        link("github.com/s-Nebula/nebula-pdf", "https://github.com/s-Nebula/nebula-pdf")
    }
    XpGroup("Privacidad") {
        Text(
            "Tus documentos se traducen en el teléfono y no se envían a ningún servidor. " +
                "Solo se usa internet una vez, para descargar el modelo de idioma inglés-español.",
            style = Xp.small,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text("© 2026 FORBIDDEN S.A.S. Todos los derechos reservados.", style = Xp.small.copy(color = Color(0xFF555555)))
}
