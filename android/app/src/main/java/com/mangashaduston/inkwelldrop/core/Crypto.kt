package com.mangashaduston.inkwelldrop.core

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** P-256 / HKDF-SHA256 helpers. Byte formats match Apple CryptoKit (see PROTOCOL.md). */
object Crypto {
    private val ecParams: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** Uncompressed X9.63 point: 0x04 || X(32) || Y(32). */
    fun x963(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        val out = ByteArray(65)
        out[0] = 4
        fixed(w.affineX).copyInto(out, 1)
        fixed(w.affineY).copyInto(out, 33)
        return out
    }

    fun publicFromX963(b: ByteArray): PublicKey {
        if (b.size != 65 || b[0].toInt() != 4) throw ProtocolException("Bad public key")
        val x = BigInteger(1, b.copyOfRange(1, 33))
        val y = BigInteger(1, b.copyOfRange(33, 65))
        // Check the point is on the curve, so a bad key can't be used to attack the key exchange.
        val p = (ecParams.curve.field as java.security.spec.ECFieldFp).p
        val lhs = y.multiply(y).mod(p)
        val rhs = x.multiply(x).multiply(x).add(ecParams.curve.a.multiply(x)).add(ecParams.curve.b).mod(p)
        if (lhs != rhs) throw ProtocolException("Bad public key")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), ecParams))
    }

    private fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) raw.copyInto(out, 0, raw.size - 32, raw.size)
        else raw.copyInto(out, 32 - raw.size)
        return out
    }

    fun ecdh(priv: PrivateKey, peer: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(priv); doPhase(peer, true) }.generateSecret()

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach { update(it) } }.doFinal()

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        var t = ByteArray(0)
        var out = ByteArray(0)
        var i = 1
        while (out.size < length) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            out += t
            i++
        }
        return out.copyOf(length)
    }

    fun sign(priv: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(priv); update(data) }.sign()

    fun verify(pub: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(data) }.verify(sig)
    } catch (e: Exception) {
        false
    }

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = try {
        Base64.getDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        throw ProtocolException("Bad base64")
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** A device's ID is the first 8 bytes of the SHA-256 of its long-term public key. */
    fun deviceId(staticPub: ByteArray): String = hex(sha256(staticPub).copyOf(8))
}

/** The long-term key that identifies this install to other devices. */
class Identity(val privateKey: PrivateKey, val publicBytes: ByteArray) {
    val id: String = Crypto.deviceId(publicBytes)

    fun sign(data: ByteArray): ByteArray = Crypto.sign(privateKey, data)

    /** Text form for storing in preferences. */
    fun encode(): String = Crypto.b64(privateKey.encoded) + ":" + Crypto.b64(publicBytes)

    companion object {
        fun generate(): Identity {
            val kp = Crypto.generateKeyPair()
            return Identity(kp.private, Crypto.x963(kp.public))
        }

        fun decode(s: String): Identity {
            val parts = s.split(":")
            val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Crypto.unb64(parts[0])))
            return Identity(priv, Crypto.unb64(parts[1]))
        }
    }
}
