package app.ata.reunioes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Grava em primeiro plano: continua com a tela bloqueada ou com outro app aberto. */
class RecorderService : Service() {

    class Session(val rec: Rec, val file: File) {
        val id: String get() = rec.id
        var accMs: Long = 0L
        var segStart: Long = SystemClock.elapsedRealtime()
        @Volatile var running: Boolean = true
        fun elapsed(): Long = accMs + (if (running) SystemClock.elapsedRealtime() - segStart else 0L)
    }

    companion object {
        const val ACTION_START = "app.ata.reunioes.START"
        const val ACTION_TOGGLE = "app.ata.reunioes.TOGGLE"
        const val ACTION_STOP = "app.ata.reunioes.STOP"
        const val ACTION_EVENT = "app.ata.reunioes.EVENT"
        private const val CHANNEL = "gravacao"
        private const val NOTIF_ID = 1

        @Volatile var current: Session? = null
        @Volatile var lastFinishedId: String? = null
        @Volatile private var recorderRef: MediaRecorder? = null

        fun amplitude(): Int = try {
            recorderRef?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private var recorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_TOGGLE -> toggle()
            ACTION_STOP -> finishRecording()
            ACTION_EVENT -> addEvent(intent.getStringExtra("kind"), intent.getStringExtra("text"))
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (current != null) return
        val now = System.currentTimeMillis()
        val id = "r" + now.toString(36)
        var title = intent.getStringExtra("title").orEmpty().trim()
        if (title.isEmpty()) {
            title = "Reunião " + SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(now))
        }
        val rec = Rec(
            id, title, intent.getStringExtra("local").orEmpty().trim(),
            intent.getStringExtra("participants").orEmpty().trim(),
            now, 0L, "", true, mutableListOf()
        )
        val file = Store.audioFile(this, id)
        val session = Session(rec, file)
        current = session
        createChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            // AAC em ADTS não depende de cabeçalho final: se o app morrer, o áudio já gravado continua tocando.
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(Store.BITRATE)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recorderRef = r
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível iniciar a gravação.", Toast.LENGTH_LONG).show()
            current = null
            file.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ata:gravacao")
            wakeLock?.acquire(6L * 60L * 60L * 1000L)
        } catch (e: Exception) {
        }
        session.segStart = SystemClock.elapsedRealtime()
        Store.save(this, rec)
    }

    private fun toggle() {
        val s = current ?: return
        val r = recorder ?: return
        try {
            if (s.running) {
                r.pause()
                s.accMs = s.elapsed()
                s.running = false
            } else {
                r.resume()
                s.segStart = SystemClock.elapsedRealtime()
                s.running = true
            }
        } catch (e: Exception) {
        }
        updateNotification()
    }

    private fun addEvent(kind: String?, text: String?) {
        val s = current ?: return
        s.rec.events.add(Ev(s.elapsed(), kind, text))
        Store.save(this, s.rec)
    }

    private fun finishRecording() {
        val s = current
        if (s == null) {
            stopSelf()
            return
        }
        val dur = s.elapsed()
        s.running = false
        try {
            recorder?.stop()
        } catch (e: Exception) {
        }
        try {
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null
        recorderRef = null
        s.rec.durationMs = dur
        s.rec.incomplete = false
        if (!s.file.exists() || s.file.length() == 0L) {
            Store.delete(this, s.id)
        } else {
            Store.save(this, s.rec)
            lastFinishedId = s.id
        }
        current = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (current != null) finishRecording()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Gravação em andamento", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun action(act: String, req: Int, kind: String? = null): PendingIntent {
        val i = Intent(this, RecorderService::class.java).setAction(act)
        if (kind != null) i.putExtra("kind", kind)
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(): Notification {
        val s = current
        val running = s?.running ?: true
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (running) "Gravando reunião" else "Gravação pausada")
            .setContentText(s?.rec?.title ?: "Ata")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (running && s != null) {
            b.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - s.elapsed())
        }
        b.addAction(0, if (running) "Pausar" else "Continuar", action(ACTION_TOGGLE, 1))
        b.addAction(0, "Marcar", action(ACTION_EVENT, 2, "Importante"))
        b.addAction(0, "Parar", action(ACTION_STOP, 3))
        return b.build()
    }
}
