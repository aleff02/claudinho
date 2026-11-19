package br.movida.laudo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    companion object {
        const val URL = "https://aleff02.github.io/claudinho/laudo/"
        const val HOST = "aleff02.github.io"
        const val REQ_FOTO = 1
        const val REQ_VOZ = 2
    }

    private lateinit var web: WebView
    private var fotoCallback: ValueCallback<Array<Uri>>? = null
    private var fotoCamera: File? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xFF0D0A08.toInt())
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            allowFileAccess = false
            // sem internet: usa a última versão salva do app (os laudos ficam na fila do aparelho)
            cacheMode = if (online()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
            userAgentString = "$userAgentString LaudoAvariaApp/1.0"
        }
        web.addJavascriptInterface(Ponte(), "AndroidApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == HOST) return false
                // WhatsApp, links de fotos, etc. abrem fora do app
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Não foi possível abrir o link", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fotoCallback?.onReceiveValue(null)
                fotoCallback = callback
                abrirSeletorDeFoto()
                return true
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(URL)
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun abrirSeletorDeFoto() {
        val pasta = File(cacheDir, "fotos").apply { mkdirs() }
        val arq = File(pasta, "avaria_${System.currentTimeMillis()}.jpg")
        fotoCamera = arq
        val uri = FileProvider.getUriForFile(this, "br.movida.laudo.fotos", arq)
        val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val galeria = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val escolha = Intent.createChooser(galeria, "Foto da avaria").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        }
        try {
            startActivityForResult(escolha, REQ_FOTO)
        } catch (e: Exception) {
            fotoCallback?.onReceiveValue(null); fotoCallback = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FOTO -> {
                var res: Array<Uri>? = null
                if (resultCode == RESULT_OK) {
                    val clip = data?.clipData
                    res = when {
                        clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                        data?.data != null -> arrayOf(data.data!!)
                        fotoCamera?.let { it.exists() && it.length() > 0 } == true ->
                            arrayOf(FileProvider.getUriForFile(this, "br.movida.laudo.fotos", fotoCamera!!))
                        else -> null
                    }
                }
                fotoCallback?.onReceiveValue(res)
                fotoCallback = null
            }
            REQ_VOZ -> {
                val txt = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (resultCode == RESULT_OK && !txt.isNullOrBlank()) {
                    web.evaluateJavascript("window.onDitado && window.onDitado(${JSONObject.quote(txt)})", null)
                } else {
                    web.evaluateJavascript("window.onDitado && window.onDitado('')", null)
                }
            }
        }
    }

    inner class Ponte {
        @JavascriptInterface
        fun ditar() {
            runOnUiThread {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Descreva a avaria")
                }
                try { startActivityForResult(i, REQ_VOZ) } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Ditado por voz indisponível neste celular", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** Salva um arquivo (ex.: Excel exportado) na pasta Downloads. */
        @JavascriptInterface
        fun salvarArquivo(nome: String, base64: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, nome)
                        put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
                    File(dir, nome).writeBytes(bytes)
                }
                runOnUiThread { Toast.makeText(this@MainActivity, "Salvo em Downloads: $nome", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Erro ao salvar: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }

        @JavascriptInterface
        fun versao(): String = "1.0"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // deixa o site decidir (fechar gaveta / voltar passo); se ele não tratar, sai do app
        web.evaluateJavascript("(window.voltarApp && window.voltarApp()) ? 1 : 0") { r ->
            if (r != "1") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }
}
