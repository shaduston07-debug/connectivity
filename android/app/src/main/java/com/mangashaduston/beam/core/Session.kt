package com.mangashaduston.beam.core

import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProtocolException(message: String) : IOException(message)

object Proto {
    const val VERSION = 1
    const val MAX_FRAME = 1 shl 20

    const val HELLO = 0x01
    const val OFFER = 0x10
    const val ACCEPT = 0x11
    const val DECLINE = 0x12
    const val CANCEL = 0x13
    const val FILE_START = 0x20
    const val DATA = 0x21
    const val FILE_END = 0x22
    const val DONE = 0x23
    const val RECEIVED = 0x24
    const val ATTACH = 0x30
}

/** A reliable two-way byte stream: a Bluetooth L2CAP channel or a TCP socket. */
interface Duplex {
    val input: InputStream
    val output: OutputStream
    fun close()
}

data class PeerInfo(val id: String, val name: String, val platform: String)

class LocalInfo(val identity: Identity, @Volatile var name: String, val platform: String) {
    val id: String get() = identity.id
}

class Frame(val type: Int, val payload: ByteArray) {
    fun json(): JSONObject = try {
        JSONObject(String(payload, Charsets.UTF_8))
    } catch (e: Exception) {
        throw ProtocolException("Bad message")
    }
}

/**
 * Framing, handshake and encryption on top of a [Duplex].
 * After [handshake] every frame is AES-256-GCM encrypted with keys from an ephemeral P-256 exchange,
 * and the other device has proved it holds the long-term key behind its ID.
 */
class Session(private val duplex: Duplex, private val local: LocalInfo) {
    private val sendLock = Any()
    private var sendKey: SecretKeySpec? = null
    private var recvKey: SecretKeySpec? = null
    private var sendCounter = 0L
    private var recvCounter = 0L
    private val lenBuf = ByteArray(4)

    lateinit var peer: PeerInfo
        private set

    fun handshake() {
        val eph = Crypto.generateKeyPair()
        val epub = Crypto.x963(eph.public)
        val hello = JSONObject()
            .put("v", Proto.VERSION)
            .put("id", local.id)
            .put("name", local.name)
            .put("platform", local.platform)
            .put("epub", Crypto.b64(epub))
            .put("spub", Crypto.b64(local.identity.publicBytes))
            .put("sig", Crypto.b64(local.identity.sign(epub)))
        writeRaw(byteArrayOf(Proto.HELLO.toByte()) + hello.toString().toByteArray())

        val body = readRaw()
        if (body.isEmpty() || body[0].toInt() != Proto.HELLO) throw ProtocolException("Expected hello")
        val theirs = try {
            JSONObject(String(body, 1, body.size - 1, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ProtocolException("Bad hello")
        }
        if (theirs.optInt("v") != Proto.VERSION) throw ProtocolException("The other device has a different version of Beam. Update both apps.")
        val theirEpub = Crypto.unb64(theirs.optString("epub"))
        val theirSpub = Crypto.unb64(theirs.optString("spub"))
        val sig = Crypto.unb64(theirs.optString("sig"))
        if (theirEpub.contentEquals(epub)) throw ProtocolException("Bad hello")
        val staticKey = Crypto.publicFromX963(theirSpub)
        if (!Crypto.verify(staticKey, theirEpub, sig)) throw ProtocolException("The other device failed the identity check")
        val id = Crypto.deviceId(theirSpub)
        peer = PeerInfo(id, theirs.optString("name").take(64).ifBlank { "Unknown" }, theirs.optString("platform").take(16))

        val secret = Crypto.ecdh(eph.private, Crypto.publicFromX963(theirEpub))
        val mineFirst = compare(epub, theirEpub) < 0
        val lo = if (mineFirst) epub else theirEpub
        val hi = if (mineFirst) theirEpub else epub
        val okm = Crypto.hkdf(secret, Crypto.sha256(lo, hi), "beam/1".toByteArray(), 64)
        val k1 = SecretKeySpec(okm, 0, 32, "AES")
        val k2 = SecretKeySpec(okm, 32, 32, "AES")
        sendKey = if (mineFirst) k1 else k2
        recvKey = if (mineFirst) k2 else k1
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return 0
    }

    private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    fun send(type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
        synchronized(sendLock) {
            val key = sendKey ?: throw ProtocolException("No handshake")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(sendCounter++)))
            val out = ByteArray(4 + 1 + length + 16)
            val total = 1 + length + 16
            ByteBuffer.wrap(out).putInt(total)
            var n = cipher.update(byteArrayOf(type.toByte()), 0, 1, out, 4)
            n += cipher.update(payload, offset, length, out, 4 + n)
            cipher.doFinal(out, 4 + n)
            duplex.output.write(out)
            duplex.output.flush()
        }
    }

    fun send(type: Int, json: JSONObject) = send(type, json.toString().toByteArray())

    /** Blocks until the next frame arrives. Call from one thread only. */
    fun receive(): Frame {
        val key = recvKey ?: throw ProtocolException("No handshake")
        val body = readRaw()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(recvCounter++)))
        val plain = try {
            cipher.doFinal(body)
        } catch (e: Exception) {
            throw ProtocolException("Decryption failed")
        }
        if (plain.isEmpty()) throw ProtocolException("Empty frame")
        return Frame(plain[0].toInt() and 0xff, plain.copyOfRange(1, plain.size))
    }

    private fun writeRaw(body: ByteArray) {
        synchronized(sendLock) {
            duplex.output.write(ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array())
            duplex.output.flush()
        }
    }

    private fun readRaw(): ByteArray {
        readFully(lenBuf, 4)
        val len = ByteBuffer.wrap(lenBuf).int
        if (len < 1 || len > Proto.MAX_FRAME + 64) throw ProtocolException("Bad frame length")
        val body = ByteArray(len)
        readFully(body, len)
        return body
    }

    private fun readFully(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = duplex.input.read(buf, off, len - off)
            if (n < 0) throw EOFException("Connection closed")
            off += n
        }
    }

    fun close() {
        try {
            duplex.close()
        } catch (_: Exception) {
        }
    }
}
