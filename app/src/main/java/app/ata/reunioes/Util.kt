package app.ata.reunioes

import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val KINDS = listOf("Importante", "Decisão", "Tarefa", "Dúvida")

fun fmtT(ms: Long, long: Boolean = false): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0 || long) String.format(Locale.US, "%02d:%02d:%02d", h, m, r)
    else String.format(Locale.US, "%02d:%02d", m, r)
}

fun fmtDate(ts: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(ts))

fun fmtSize(bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1.0) String.format(Locale("pt", "BR"), "%.1f MB", mb)
    else "${maxOf(1L, bytes / 1024)} KB"
}

fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

/** Afasta o conteúdo das barras do sistema e do teclado (tela de ponta a ponta). */
fun applyInsets(v: View, basePx: Int) {
    ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        view.setPadding(basePx + b.left, basePx + b.top, basePx + b.right, basePx + b.bottom)
        insets
    }
}

fun safeName(s: String): String {
    val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
    return n.take(50).ifEmpty { "reuniao" }
}

fun ataText(r: Rec): String {
    val sb = StringBuilder()
    sb.append("# ${r.title}\n\n")
    sb.append("Data: ${fmtDate(r.created)}\n")
    if (r.local.isNotBlank()) sb.append("Local: ${r.local}\n")
    if (r.participants.isNotBlank()) sb.append("Participantes: ${r.participants}\n")
    sb.append("Duração: ${fmtT(r.durationMs, true)}\n\n")
    if (r.summary.isNotBlank()) sb.append("## Resumo e decisões\n\n${r.summary.trim()}\n\n")
    val ev = r.events.sortedBy { it.t }
    for (k in KINDS) {
        val arr = ev.filter { it.kind == k }
        if (arr.isNotEmpty()) {
            sb.append("## $k\n")
            arr.forEach { sb.append("- [${fmtT(it.t)}] ${it.text ?: "(marcado no áudio)"}\n") }
            sb.append("\n")
        }
    }
    val plain = ev.filter { it.kind == null && !it.text.isNullOrBlank() }
    if (plain.isNotEmpty()) {
        sb.append("## Anotações\n")
        plain.forEach { sb.append("- [${fmtT(it.t)}] ${it.text}\n") }
    }
    return sb.toString().trim() + "\n"
}
