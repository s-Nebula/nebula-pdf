package org.vestaopensource.nebulapdf

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.vestaopensource.nebulapdf.core.BookTranslator
import org.vestaopensource.nebulapdf.core.TermProtector
import org.vestaopensource.nebulapdf.engine.MlKitOcr
import org.vestaopensource.nebulapdf.engine.MlKitTranslator
import java.io.File

/**
 * Servicio en primer plano: los libros largos pueden tardar varios minutos, así que la traducción
 * sigue aunque cierres la app o se apague la pantalla. Muestra el progreso en una notificación.
 */
class TranslationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val input = intent.getStringExtra(EXTRA_INPUT)?.let(::File) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: input.name
                if (job?.isActive == true) return START_NOT_STICKY
                ServiceCompat.startForeground(
                    this, NOTIF_PROGRESS, progressNotification(name, 0, 0, "Preparando…"),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
                )
                job = scope.launch { translate(input, name) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun translate(input: File, name: String) {
        val settings = Settings(this)
        val outDir = File(filesDir, "translated").apply { mkdirs() }
        val output = File(outDir, outputName(name))
        val tmp = File(outDir, output.name + ".part")
        acquireWakeLock()
        try {
            update(JobState.Running(name, 0, 0, "Preparando el traductor (la primera vez descarga ~30 MB)…"))
            MlKitTranslator().use { translator ->
                translator.prepare()
                val ocr = if (settings.useOcr) runCatching { MlKitOcr(input) }.getOrNull() else null
                try {
                    val engine = BookTranslator(translator, TermProtector(settings.glossaryTerms()), ocr)
                    val summary = engine.run(input, tmp) { p ->
                        update(JobState.Running(name, p.page, p.totalPages, p.message))
                    }
                    if (output.exists()) output.delete()
                    tmp.renameTo(output)
                    update(JobState.Done(name, output, summary))
                    notifyDone(name, true)
                } finally {
                    ocr?.close()
                }
            }
        } catch (e: CancellationException) {
            update(JobState.Failed(name, "Traducción cancelada."))
        } catch (e: Throwable) {
            val msg = when {
                e is OutOfMemoryError -> "El PDF es demasiado grande para la memoria del teléfono."
                e.message?.contains("password", true) == true -> "El PDF está protegido con contraseña."
                e.javaClass.name.contains("mlkit", true) || e.message?.contains("download", true) == true ->
                    "No se pudo descargar el modelo de traducción. Conéctate a internet e inténtalo otra vez."
                else -> "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            update(JobState.Failed(name, msg))
            notifyDone(name, false)
        } finally {
            tmp.delete()
            input.delete()
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun update(s: JobState) {
        TranslationState.mutable.value = s
        if (s is JobState.Running) {
            val now = System.currentTimeMillis()
            if (now - lastNotify > 700 || s.page == s.total) {
                lastNotify = now
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_PROGRESS, progressNotification(s.fileName, s.page, s.total, s.message))
            }
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotification(name: String, page: Int, total: Int, text: String): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TranslationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NebulaApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_nebula)
            .setContentTitle("Traduciendo $name")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, page, total == 0)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancelar", cancel)
            .build()
    }

    private fun notifyDone(name: String, ok: Boolean) {
        val n = NotificationCompat.Builder(this, NebulaApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_nebula)
            .setContentTitle(if (ok) "Traducción lista" else "No se pudo traducir")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n)
        } catch (e: SecurityException) {
            // sin permiso de notificaciones: la app muestra el resultado igualmente
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NebulaPDF:translate").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.vestaopensource.nebulapdf.START"
        const val ACTION_CANCEL = "org.vestaopensource.nebulapdf.CANCEL"
        const val EXTRA_INPUT = "input"
        const val EXTRA_NAME = "name"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2

        fun outputName(original: String): String {
            val base = original.substringBeforeLast('.', original)
            return "$base (ES).pdf"
        }

        fun start(context: Context, input: File, name: String) {
            val i = Intent(context, TranslationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INPUT, input.absolutePath)
                .putExtra(EXTRA_NAME, name)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, TranslationService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
