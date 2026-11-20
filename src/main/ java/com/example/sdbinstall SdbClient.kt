package com.example.sdbinstall

import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cliente SDB mínimo (protocolo derivado do ADB) para relógios Tizen.
 * ATENÇÃO: não testado em um relógio real. Pode precisar de ajustes.
 */
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
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size)
        b.putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd xor -1)
        output.write(b.array()); output.write(data); output.flush()
    }

    private fun read(): Packet {
        val h = ByteArray(24); input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        val data = ByteArray(len); input.readFully(data)
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
            if (p.cmd == CNXN) { maxData = minOf(p.arg1, 4096).coerceAtLeast(256); return }
            if (p.cmd == AUTH) throw IOException("O relógio pediu autenticação (não implementada)")
        }
    }

    fun close() { try { socket.close() } catch (_: Exception) {} }

    private fun open(service: String): Pair<Int, Int> {
        val local = nextId++
        send(OPEN, local, 0, (service + "\u0000").toByteArray())
        while (true) {
            val p = read()
            if (p.cmd == OKAY) return local to p.arg0
            if (p.cmd == CLSE) throw IOException("Relógio recusou o serviço: $service")
        }
    }

    private fun write(l: Int, r: Int, data: ByteArray) {
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
        val (l, r) = open("shell:$cmd")
        val out = ByteArrayOutputStream()
        while (true) {
            val p = read()
            if (p.cmd == WRTE) { out.write(p.data); send(OKAY, l, r) }
            if (p.cmd == CLSE) return out.toString("UTF-8")
        }
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    fun push(data: ByteArray, remotePath: String, onProgress: (Int) -> Unit) {
        val (l, r) = open("sync:")
        val target = "$remotePath,33206".toByteArray()
        write(l, r, "SEND".toByteArray() + le32(target.size) + target)
        var off = 0
        while (off < data.size) {
            val n = minOf(65536, data.size - off)
            write(l, r, "DATA".toByteArray() + le32(n) + data.copyOfRange(off, off + n))
            off += n
            onProgress(off * 100 / data.size)
        }
        write(l, r, "DONE".toByteArray() + le32((System.currentTimeMillis() / 1000).toInt()))
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
        write(l, r, "QUIT".toByteArray() + le32(0))
        send(CLSE, l, r)
    }

    fun install(tpk: ByteArray, log: (String) -> Unit, onProgress: (Int) -> Unit) {
        val dir = "/home/owner/share/tmp/sdk_tools"
        val path = "$dir/app.tpk"
        log("Preparando pasta..."); shell("mkdir -p $dir")
        log("Enviando arquivo..."); push(tpk, path, onProgress)
        log("Instalando..."); log(shell("pkgcmd -i -t tpk -p $path -q").trim())
        shell("rm -f $path")
    }
}
