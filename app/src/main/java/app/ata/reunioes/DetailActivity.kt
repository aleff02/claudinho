package app.ata.reunioes

import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import app.ata.reunioes.databinding.ActivityDetailBinding

class DetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailBinding
    private lateinit var rec: Rec
    private var mp: MediaPlayer? = null
    private var speed = 1f
    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
    private val h = Handler(Looper.getMainLooper())
    private var dragging = false

    private val tick = object : Runnable {
        override fun run() {
            updatePos()
            h.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val id = intent.getStringExtra("id")
        val loaded = if (id != null) Store.load(this, id) else null
        if (loaded == null) {
            finish()
            return
        }
        rec = loaded
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets(b.root, dp(this, 16))

        b.eTitle.setText(rec.title)
        b.eLocal.setText(rec.local)
        b.eParts.setText(rec.participants)
        b.eSummary.setText(rec.summary)
        val locais = Store.list(this).map { it.local }.filter { it.isNotBlank() }.distinct().sorted()
        b.eLocal.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, locais))
        val size = Store.audioFile(this, rec.id).length()
        b.tvInfo.text = "${fmtDate(rec.created)} · ${fmtT(rec.durationMs, true)} · ${fmtSize(size)}"

        try {
            val p = MediaPlayer()
            p.setDataSource(Store.audioFile(this, rec.id).absolutePath)
            p.prepare()
            p.setOnCompletionListener { b.btnPlay.text = "Ouvir" }
            mp = p
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o áudio.", Toast.LENGTH_LONG).show()
        }

        b.seek.max = maxOf(1000L, rec.durationMs).toInt()
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) b.tvPos.text = posText(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                seekTo((sb?.progress ?: 0).toLong(), false)
            }
        })
        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnBack15.setOnClickListener { seekTo(curMs() - 15000, false) }
        b.btnFwd15.setOnClickListener { seekTo(minOf(rec.durationMs, curMs() + 15000), false) }
        b.btnSpeed.setOnClickListener { cycleSpeed() }
        b.btnMarkHere.setOnClickListener {
            rec.events.add(Ev(curMs(), "Importante", null))
            persist()
            renderEvents()
            Toast.makeText(this, "Marcado em ${fmtT(curMs())}", Toast.LENGTH_SHORT).show()
        }
        b.btnAddNote.setOnClickListener {
            val t = b.eNote.text.toString().trim()
            if (t.isNotEmpty()) {
                rec.events.add(Ev(curMs(), null, t))
                b.eNote.setText("")
                persist()
                renderEvents()
            }
        }
        b.btnShareAta.setOnClickListener {
            persist()
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, rec.title)
                .putExtra(Intent.EXTRA_TEXT, ataText(rec))
            startActivity(Intent.createChooser(send, "Compartilhar ata"))
        }
        b.btnShareAudio.setOnClickListener { shareAudio() }
        b.btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Apagar gravação?")
                .setMessage("“${rec.title}” e o áudio serão removidos deste aparelho. Isso não pode ser desfeito.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Apagar") { _, _ ->
                    mp?.release()
                    mp = null
                    Store.delete(this, rec.id)
                    finish()
                }
                .show()
        }
        renderEvents()
    }

    override fun onResume() {
        super.onResume()
        if (::rec.isInitialized) h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        if (::rec.isInitialized) {
            mp?.let { if (it.isPlaying) it.pause() }
            b.btnPlay.text = "Ouvir"
            persist()
        }
        super.onPause()
    }

    override fun onDestroy() {
        mp?.release()
        mp = null
        super.onDestroy()
    }

    private fun persist() {
        rec.title = b.eTitle.text.toString().trim().ifEmpty { rec.title }
        rec.local = b.eLocal.text.toString().trim()
        rec.participants = b.eParts.text.toString().trim()
        rec.summary = b.eSummary.text.toString()
        Store.save(this, rec)
    }

    private fun curMs(): Long = try { (mp?.currentPosition ?: 0).toLong() } catch (e: Exception) { 0L }

    private fun posText(ms: Long) = "${fmtT(ms)} / ${fmtT(rec.durationMs)}"

    private fun updatePos() {
        if (dragging) return
        val c = curMs()
        b.seek.progress = minOf(b.seek.max.toLong(), c).toInt()
        b.tvPos.text = posText(c)
    }

    private fun applySpeed() {
        val p = mp ?: return
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                p.playbackParams = p.playbackParams.setSpeed(speed)
            } catch (e: Exception) {
            }
        }
    }

    private fun togglePlay() {
        val p = mp ?: return
        if (p.isPlaying) {
            p.pause()
            b.btnPlay.text = "Continuar"
        } else {
            p.start()
            applySpeed()
            b.btnPlay.text = "Pausar"
        }
    }

    private fun cycleSpeed() {
        val i = (speeds.indexOfFirst { it == speed } + 1) % speeds.size
        speed = speeds[i]
        b.btnSpeed.text = speed.toString().replace(".0", "").replace('.', ',') + "×"
        if (mp?.isPlaying == true) applySpeed()
    }

    private fun seekTo(ms: Long, play: Boolean) {
        val p = mp ?: return
        p.seekTo(maxOf(0L, ms).toInt())
        if (play && !p.isPlaying) {
            p.start()
            applySpeed()
            b.btnPlay.text = "Pausar"
        }
        updatePos()
    }

    private fun renderEvents() {
        b.eventsBox.removeAllViews()
        val sorted = rec.events.sortedBy { it.t }
        for (e in sorted) {
            val tv = TextView(this)
            val sb = StringBuilder(fmtT(e.t)).append("   ")
            if (e.kind != null) sb.append("[").append(e.kind).append("] ")
            if (e.text != null) sb.append(e.text)
            tv.text = sb.toString()
            tv.textSize = 15f
            tv.setPadding(0, dp(this, 10), 0, dp(this, 10))
            tv.setOnClickListener { seekTo(e.t, true) }
            tv.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Remover esta marca?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Remover") { _, _ ->
                        rec.events.remove(e)
                        persist()
                        renderEvents()
                    }
                    .show()
                true
            }
            b.eventsBox.addView(tv)
        }
        b.eventsBox.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shareAudio() {
        val f = Store.audioFile(this, rec.id)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/aac")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, rec.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Enviar áudio"))
    }
}
