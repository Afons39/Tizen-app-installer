package com.example.sdbinstall

import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var tpkUri: Uri? = null
    private lateinit var logView: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        tpkUri = uri
        log(if (uri != null) "Arquivo selecionado: $uri" else "Nenhum arquivo selecionado")
    }

    private fun log(msg: String) = runOnUiThread { logView.append(msg + "\n") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val ip = EditText(this).apply { hint = "IP do relógio (ex: 192.168.0.25)" }
        val pick = Button(this).apply { text = "Escolher arquivo .tpk"; setOnClickListener { picker.launch(arrayOf("*/*")) } }
        val go = Button(this).apply { text = "Instalar no relógio" }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        logView = TextView(this)
        listOf(ip, pick, go, bar, ScrollView(this).apply { addView(logView) }).forEach { root.addView(it) }
        setContentView(root)

        go.setOnClickListener {
            val uri = tpkUri ?: return@setOnClickListener log("Escolha o .tpk primeiro")
            val host = ip.text.toString().trim()
            if (host.isEmpty()) return@setOnClickListener log("Digite o IP")
            thread {
                val client = SdbClient(host)
                try {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    log("Conectando a $host:26101...")
                    client.connect(); log("Conectado.")
                    client.install(bytes, ::log) { p -> runOnUiThread { bar.progress = p } }
                    log("Concluído.")
                } catch (e: Exception) {
                    log("ERRO: ${e.javaClass.simpleName}: ${e.message}")
                } finally { client.close() }
            }
        }
    }
}
