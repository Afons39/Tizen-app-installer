package com.example.sdbinstall

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var tpkUri: Uri? = null
    private lateinit var fileView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var bar: ProgressBar
    private lateinit var ipField: EditText
    private lateinit var btnPick: Button
    private lateinit var btnTest: Button
    private lateinit var btnInstall: Button

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            tpkUri = uri
            fileView.text = "Arquivo: " + nameOf(uri)
            log("Arquivo selecionado.")
        }
    }

    private fun nameOf(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) return c.getString(i)
        }
        return uri.lastPathSegment ?: "arquivo"
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setBusy(busy: Boolean) {
        runOnUiThread {
            listOf(btnPick, btnTest, btnInstall).forEach { it.isEnabled = !busy }
        }
    }

    private fun runTask(needFile: Boolean, job: (SdbClient) -> Unit) {
        val host = ipField.text.toString().trim()
        if (host.isEmpty()) { log("Digite o IP do relógio."); return }
        if (needFile && tpkUri == null) { log("Escolha o arquivo .tpk primeiro."); return }
        setBusy(true)
        thread {
            val client = SdbClient(host)
            try {
                log("Conectando a $host:26101...")
                client.connect()
                log("Conectado.")
                job(client)
            } catch (e: Exception) {
                log("ERRO: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                client.close()
                setBusy(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)

        val title = TextView(this)
        title.text = "Instalador de apps Tizen"
        title.textSize = 22f

        val info = TextView(this)
        info.text = "O relógio precisa estar com a depuração por Wi-Fi ligada e no mesmo Wi-Fi do celular."

        ipField = EditText(this)
        ipField.hint = "IP do relógio (ex: 192.168.0.25)"

        fileView = TextView(this)
        fileView.text = "Nenhum arquivo selecionado"

        btnPick = Button(this)
        btnPick.text = "1. Escolher arquivo .tpk"
        btnPick.setOnClickListener { picker.launch(arrayOf("*/*")) }

        btnTest = Button(this)
        btnTest.text = "2. Testar conexão"
        btnTest.setOnClickListener {
            runTask(false) { c ->
                log("Resposta do relógio: " + c.shell("uname -a").trim())
            }
        }

        btnInstall = Button(this)
        btnInstall.text = "3. Instalar no relógio"
        btnInstall.setOnClickListener {
            runTask(true) { c ->
                val bytes = contentResolver.openInputStream(tpkUri!!)!!.use { it.readBytes() }
                c.install(bytes, { m -> log(m) }) { p -> runOnUiThread { bar.progress = p } }
                log("Concluído.")
            }
        }

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100

        logView = TextView(this)
        scroll = ScrollView(this)
        scroll.addView(logView)

        root.addView(title)
        root.addView(info)
        root.addView(ipField)
        root.addView(fileView)
        root.addView(btnPick)
        root.addView(btnTest)
        root.addView(btnInstall)
        root.addView(bar)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}

class SdbClient(private val host: String, private val port: Int = 26101) {
    private class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var maxData = 4096
    private var nextId = 1

    companion object {
        const val CNXN = 0x4e584e43
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val AUTH = 0x48545541
        const val VERSION = 0x01000000
    }

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        val b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (x in data) sum += (x.toInt() and 0xff)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size)
        b.putInt(sum).putInt(cmd xor -1)
        output.write(b.array())
        output.write(data)
        output.flush()
    }

    private fun read(): Packet {
        val h = ByteArray(24)
        input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.getInt()
        val a0 = b.getInt()
        val a1 = b.getInt()
        val len = b.getInt()
        val data = ByteArray(len)
        input.readFully(data)
        return Packet(cmd, a0, a1, data)
    }

    fun connect() {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.soTimeout = 20000
        input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        send(CNXN, VERSION, 4096, "host::\u0000".toByteArray())
        while (true) {
            val p = read()
            if (p.cmd == CNXN) {
                maxData = minOf(p.arg1, 4096).coerceAtLeast(256)
                return
            }
            if (p.cmd == AUTH) throw IOException("O relógio pediu autenticação (não implementada)")
        }
    }

    fun close() {
        try { socket.close() } catch (e: Exception) { }
    }

    private fun openStream(service: String): Pair<Int, Int> {
        val local = nextId++
        send(OPEN, local, 0, (service + "\u0000").toByteArray())
        while (true) {
            val p = read()
            if (p.cmd == OKAY) return Pair(local, p.arg0)
            if (p.cmd == CLSE) throw IOException("Relógio recusou o serviço: $service")
        }
    }

    private fun writeAll(l: Int, r: Int, data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(maxData, data.size - off)
            send(WRTE, l, r, data.copyOfRange(off, off + n))
            while (true) {
                val p = read()
                if (p.cmd == OKAY) break
                if (p.cmd == CLSE) throw IOException("Conexão fechada pelo relógio")
            }
            off += n
        }
    }

    fun shell(cmd: String): String {
        val (l, r) = openStream("shell:$cmd")
        val out = ByteArrayOutputStream()
        while (true) {
            val p = read()
            if (p.cmd == WRTE) {
                out.write(p.data)
                send(OKAY, l, r)
            }
            if (p.cmd == CLSE) return out.toString("UTF-8")
        }
    }

    private fun le32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    fun push(data: ByteArray, remotePath: String, onProgress: (Int) -> Unit) {
        val (l, r) = openStream("sync:")
        val target = "$remotePath,33206".toByteArray()
        writeAll(l, r, "SEND".toByteArray() + le32(target.size) + target)
        var off = 0
        while (off < data.size) {
            val n = minOf(65536, data.size - off)
            writeAll(l, r, "DATA".toByteArray() + le32(n) + data.copyOfRange(off, off + n))
            off += n
            onProgress((off.toLong() * 100 / data.size).toInt())
        }
        writeAll(l, r, "DONE".toByteArray() + le32((System.currentTimeMillis() / 1000).toInt()))
        while (true) {
            val p = read()
            if (p.cmd == WRTE) {
                send(OKAY, l, r)
                val s = String(p.data.copyOf(minOf(4, p.data.size)))
                if (s == "OKAY") break
                throw IOException("Falha ao enviar arquivo: " + String(p.data))
            }
            if (p.cmd == CLSE) throw IOException("Conexão fechada durante o envio")
        }
        writeAll(l, r, "QUIT".toByteArray() + le32(0))
        send(CLSE, l, r)
    }

    fun install(tpk: ByteArray, log: (String) -> Unit, onProgress: (Int) -> Unit) {
        val dir = "/home/owner/share/tmp/sdk_tools"
        val path = "$dir/app.tpk"
        log("Preparando pasta...")
        shell("mkdir -p $dir")
        log("Enviando arquivo...")
        push(tpk, path, onProgress)
        log("Instalando...")
        log(shell("pkgcmd -i -t tpk -p $path -q").trim())
        shell("rm -f $path")
    }
}
