package br.movida.laudo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Confere no GitHub (Releases) se há versão nova do APK e instala por cima.
 * O GitHub Actions publica junto do APK um "versao.json" com o número da versão.
 */
class Atualizador(private val act: Activity) {

    companion object {
        const val VERSAO_URL = "https://github.com/aleff02/claudinho/releases/download/laudo-apk/versao.json"
        const val APK_URL = "https://github.com/aleff02/claudinho/releases/download/laudo-apk/LaudoAvaria.apk"
    }

    val versaoAtual: Long
        get() {
            val pi = act.packageManager.getPackageInfo(act.packageName, 0)
            @Suppress("DEPRECATION")
            return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        }

    val nomeVersao: String
        get() = act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "?"

    private var baixando = false

    /** @param avisarSeAtualizado mostra "já está na última versão" (quando o usuário pede manualmente). */
    fun verificar(avisarSeAtualizado: Boolean = false) {
        Thread {
            try {
                val json = JSONObject(baixarTexto(VERSAO_URL + "?t=" + System.currentTimeMillis()))
                val remota = json.optLong("versionCode", 0)
                val nome = json.optString("versionName", remota.toString())
                val apk = json.optString("apk", APK_URL)
                act.runOnUiThread {
                    if (remota > versaoAtual) perguntar(nome, apk)
                    else if (avisarSeAtualizado) toast("Você já está na versão mais recente ($nomeVersao)")
                }
            } catch (e: Exception) {
                if (avisarSeAtualizado) act.runOnUiThread { toast("Não foi possível verificar agora. Confira a internet.") }
            }
        }.start()
    }

    private fun perguntar(nome: String, apk: String) {
        if (act.isFinishing) return
        AlertDialog.Builder(act)
            .setTitle("Nova versão disponível")
            .setMessage("Versão $nome do Laudo de Avaria está disponível (você tem a $nomeVersao).\n\nSeus laudos e seu perfil continuam salvos.")
            .setPositiveButton("Atualizar agora") { _, _ -> iniciar(apk) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun iniciar(apk: String) {
        // Android 8+: o usuário precisa permitir, uma vez, que este app instale atualizações
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("Permitir atualizações")
                .setMessage("Na próxima tela, ative \"Permitir desta fonte\" e volte para o app. Isso é feito só uma vez.")
                .setPositiveButton("Abrir configuração") { _, _ ->
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
                    pendente = apk
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        baixarEInstalar(apk)
    }

    private var pendente: String? = null

    /** Chamado no onResume: se o usuário acabou de liberar a permissão, continua a atualização. */
    fun retomar() {
        val apk = pendente ?: return
        if (Build.VERSION.SDK_INT < 26 || act.packageManager.canRequestPackageInstalls()) {
            pendente = null
            baixarEInstalar(apk)
        }
    }

    private fun baixarEInstalar(apk: String) {
        if (baixando) return
        baixando = true
        toast("Baixando atualização…")
        Thread {
            try {
                val dir = File(act.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val arq = File(dir, "LaudoAvaria.apk")
                abrir(apk).inputStream.use { inp -> arq.outputStream().use { inp.copyTo(it) } }
                act.runOnUiThread {
                    baixando = false
                    val uri = FileProvider.getUriForFile(act, "br.movida.laudo.fotos", arq)
                    val i = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    act.startActivity(i)
                }
            } catch (e: Exception) {
                act.runOnUiThread { baixando = false; toast("Falha ao baixar a atualização: ${e.message}") }
            }
        }.start()
    }

    private fun abrir(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(5) { // segue redirecionamentos do GitHub
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "LaudoAvariaApp")
            val code = c.responseCode
            if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code != 200) throw Exception("HTTP $code")
            return c
        }
        throw Exception("muitos redirecionamentos")
    }

    private fun baixarTexto(url: String) = abrir(url).inputStream.bufferedReader().use { it.readText() }

    private fun toast(m: String) = Toast.makeText(act, m, Toast.LENGTH_LONG).show()
}
