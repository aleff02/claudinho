package app.ata.reunioes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.ata.reunioes.databinding.ActivityMainBinding
import app.ata.reunioes.databinding.ItemRecordingBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val h = Handler(Looper.getMainLooper())
    private var all: List<Rec> = emptyList()
    private var shown: List<Rec> = emptyList()
    private val adapter = LibAdapter()
    private var openAfterStop = false
    private var stopRequestedAt = 0L
    private var lastEventCount = -1

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                doStart()
            } else {
                Toast.makeText(this, "Sem permissão do microfone, não dá para gravar.", Toast.LENGTH_LONG).show()
            }
        }

    private val tick = object : Runnable {
        override fun run() {
            refreshRecUi()
            h.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets(b.root, dp(this, 16))

        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ -> openDetail(shown[pos].id) }
        b.btnTabRec.setOnClickListener { showTab(false) }
        b.btnTabLib.setOnClickListener { showTab(true) }
        b.btnStart.setOnClickListener { onStartClicked() }
        b.btnPause.setOnClickListener { sendToService(RecorderService.ACTION_TOGGLE) }
        b.btnStop.setOnClickListener {
            openAfterStop = true
            stopRequestedAt = SystemClock.elapsedRealtime()
            sendToService(RecorderService.ACTION_STOP)
        }
        val kindButtons = listOf(b.btnK1, b.btnK2, b.btnK3, b.btnK4)
        kindButtons.forEachIndexed { i, btn ->
            btn.text = KINDS[i]
            btn.setOnClickListener {
                sendToService(RecorderService.ACTION_EVENT) { it.putExtra("kind", KINDS[i]) }
                Toast.makeText(this, "${KINDS[i]} marcado", Toast.LENGTH_SHORT).show()
            }
        }
        b.btnNote.setOnClickListener { addNote() }
        b.eSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        showTab(false)
    }

    override fun onResume() {
        super.onResume()
        Store.recoverOrphans(this, RecorderService.current?.id)
        refreshLibrary()
        if (!openAfterStop && RecorderService.lastFinishedId != null) {
            RecorderService.lastFinishedId = null
            Toast.makeText(this, "Gravação salva.", Toast.LENGTH_SHORT).show()
            showTab(true)
        }
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    private fun showTab(lib: Boolean) {
        b.panelRec.visibility = if (lib) View.GONE else View.VISIBLE
        b.panelLib.visibility = if (lib) View.VISIBLE else View.GONE
        b.btnTabRec.alpha = if (lib) 0.5f else 1f
        b.btnTabLib.alpha = if (lib) 1f else 0.5f
        if (lib) refreshLibrary()
    }

    private fun sendToService(action: String, extra: ((Intent) -> Unit)? = null) {
        val i = Intent(this, RecorderService::class.java).setAction(action)
        extra?.invoke(i)
        startService(i)
    }

    private fun onStartClicked() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) doStart() else permLauncher.launch(missing.toTypedArray())
    }

    private fun doStart() {
        if (RecorderService.current != null) return
        val i = Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra("title", b.eTitle.text.toString())
            .putExtra("local", b.eLocal.text.toString())
            .putExtra("participants", b.eParts.text.toString())
        ContextCompat.startForegroundService(this, i)
        b.eTitle.setText("")
        b.eParts.setText("")
    }

    private fun addNote() {
        val t = b.eNote.text.toString().trim()
        if (t.isEmpty()) return
        sendToService(RecorderService.ACTION_EVENT) { it.putExtra("text", t) }
        b.eNote.setText("")
    }

    private fun refreshRecUi() {
        val s = RecorderService.current
        if (s == null) {
            b.idleBox.visibility = View.VISIBLE
            b.activeBox.visibility = View.GONE
            lastEventCount = -1
            if (openAfterStop) {
                val id = RecorderService.lastFinishedId
                if (id != null) {
                    RecorderService.lastFinishedId = null
                    openAfterStop = false
                    refreshLibrary()
                    openDetail(id)
                } else if (SystemClock.elapsedRealtime() - stopRequestedAt > 4000) {
                    openAfterStop = false
                }
            }
            return
        }
        b.idleBox.visibility = View.GONE
        b.activeBox.visibility = View.VISIBLE
        b.tvTimer.text = fmtT(s.elapsed(), true)
        b.tvStatus.text = if (s.running) "● Gravando" else "Pausado"
        b.btnPause.text = if (s.running) "Pausar" else "Continuar"
        val amp = if (s.running) RecorderService.amplitude() else 0
        b.level.progress = minOf(100, (Math.sqrt(amp / 32767.0) * 140.0).toInt())
        val n = s.rec.events.size
        if (n != lastEventCount) {
            lastEventCount = n
            val sb = StringBuilder()
            for (e in s.rec.events.reversed()) {
                sb.append(fmtT(e.t)).append("  ")
                if (e.kind != null) sb.append("[").append(e.kind).append("] ")
                if (e.text != null) sb.append(e.text)
                sb.append("\n")
            }
            b.tvEvents.text = if (n == 0) "As marcas e anotações aparecem aqui." else sb.toString().trim()
        }
    }

    private fun refreshLibrary() {
        all = Store.list(this)
        val locais = all.map { it.local }.filter { it.isNotBlank() }.distinct().sorted()
        b.eLocal.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, locais))
        applyFilter()
    }

    private fun applyFilter() {
        val q = b.eSearch.text.toString().trim().lowercase()
        shown = if (q.isEmpty()) all else all.filter { r ->
            val hay = StringBuilder()
            hay.append(r.title).append(' ').append(r.local).append(' ')
                .append(r.participants).append(' ').append(r.summary).append(' ')
            r.events.forEach { hay.append(it.kind ?: "").append(' ').append(it.text ?: "").append(' ') }
            hay.toString().lowercase().contains(q)
        }
        adapter.notifyDataSetChanged()
        b.tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        b.tvEmpty.text = if (all.isEmpty()) "Nenhuma gravação ainda. Grave a primeira reunião na aba Gravar."
        else "Nada encontrado para essa busca."
    }

    private fun openDetail(id: String) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra("id", id))
    }

    private inner class LibAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ib = if (convertView == null) ItemRecordingBinding.inflate(layoutInflater, parent, false)
            else ItemRecordingBinding.bind(convertView)
            val r = shown[position]
            val live = r.incomplete && r.id == RecorderService.current?.id
            ib.tvItemTitle.text = r.title
            ib.tvItemDur.text = if (live) "gravando" else fmtT(r.durationMs)
            val parts = mutableListOf(fmtDate(r.created))
            if (r.local.isNotBlank()) parts.add(r.local)
            parts.add(fmtSize(Store.audioFile(this@MainActivity, r.id).length()))
            if (r.events.isNotEmpty()) parts.add("${r.events.size} marca(s)")
            ib.tvItemMeta.text = parts.joinToString(" · ")
            return ib.root
        }
    }
}
