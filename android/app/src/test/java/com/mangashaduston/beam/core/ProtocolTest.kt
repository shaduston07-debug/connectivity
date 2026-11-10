package com.mangashaduston.beam.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DirSinks(val dir: File) : SinkFactory {
    override fun create(item: ItemMeta): FileSink {
        val f = File(dir, item.name)
        val out = f.outputStream()
        return object : FileSink {
            override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)
            override fun finish(): String {
                out.close()
                return f.path
            }

            override fun abort() {
                out.close()
                f.delete()
            }
        }
    }
}

class FileSource(val file: File, private val delayMs: Long = 0) : Source {
    override val meta = ItemMeta(file.name, file.length(), "application/octet-stream")
    override fun open(): InputStream {
        val ins = file.inputStream()
        if (delayMs == 0L) return ins
        return object : InputStream() {
            override fun read(): Int = ins.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(delayMs)
                return ins.read(b, off, len)
            }

            override fun close() = ins.close()
        }
    }
}

class TestPeer(name: String, lan: Boolean, val autoAccept: Boolean? = true) : PeerLink.Listener {
    val dir: File = Files.createTempDirectory("beam-$name").toFile()
    val local = LocalInfo(Identity.generate(), name, "test")
    val lanServer = if (lan) LanServer(local).apply { addressProvider = { listOf("127.0.0.1") }; start() } else null
    val env = BeamEnv(local, DirSinks(dir), lanServer)
    val ready = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val offers = LinkedBlockingQueue<IncomingTransfer>()
    lateinit var link: PeerLink

    fun attach(sock: Socket, chunk: Int = 4096) {
        link = PeerLink(SocketDuplex(sock), env, chunk, this)
        link.start()
    }

    override fun onReady(link: PeerLink) = ready.countDown()
    override fun onClosed(link: PeerLink, error: String?) = closed.countDown()
    override fun onOffer(link: PeerLink, transfer: IncomingTransfer) {
        when (autoAccept) {
            true -> transfer.accept()
            false -> transfer.decline()
            null -> {}
        }
        offers.add(transfer)
    }
}

fun await(t: Transfer, seconds: Long = 30) {
    val end = System.currentTimeMillis() + seconds * 1000
    while (!t.isFinished && System.currentTimeMillis() < end) Thread.sleep(10)
    assertTrue("transfer did not finish: ${t.state}", t.isFinished)
}

fun randomFile(dir: File, name: String, size: Int): File =
    File(dir, name).apply { writeBytes(Random.nextBytes(size)) }

class ProtocolTest {
    private fun pair(a: TestPeer, b: TestPeer) {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        a.attach(client)
        b.attach(server.accept())
        server.close()
        assertTrue(a.ready.await(5, TimeUnit.SECONDS))
        assertTrue(b.ready.await(5, TimeUnit.SECONDS))
    }

    private fun sendAndCheck(lan: Boolean) {
        val a = TestPeer("alice", false)
        val b = TestPeer("bob", lan)
        pair(a, b)
        assertEquals("bob", a.link.peer.name)
        assertEquals(b.local.id, a.link.peer.id)
        val src = Files.createTempDirectory("beam-src").toFile()
        val files = listOf(randomFile(src, "photo.jpg", 700_001), randomFile(src, "empty.bin", 0), randomFile(src, "note.txt", 13))
        val out = a.link.offer(files.map { FileSource(it) }, null)
        await(out)
        assertEquals(TransferState.DONE, out.state)
        assertEquals(lan, out.viaLan)
        val inc = b.offers.poll(5, TimeUnit.SECONDS)!!
        await(inc)
        assertEquals(TransferState.DONE, inc.state)
        assertEquals(lan, inc.viaLan)
        assertEquals(3, inc.results.size)
        for (f in files) assertArrayEquals(f.readBytes(), File(b.dir, f.name).readBytes())
        assertEquals(out.totalBytes, out.bytesDone)
    }

    @Test
    fun sendsFilesOverTheControlLink() = sendAndCheck(false)

    @Test
    fun movesDataToWifiWhenAvailable() = sendAndCheck(true)

    @Test
    fun sendsText() {
        val a = TestPeer("alice", false)
        val b = TestPeer("bob", true)
        pair(a, b)
        val out = a.link.offer(emptyList(), "https://example.com/ünï")
        await(out)
        assertEquals(TransferState.DONE, out.state)
        assertEquals("https://example.com/ünï", b.offers.poll(5, TimeUnit.SECONDS)!!.text)
    }

    @Test
    fun declineIsReported() {
        val a = TestPeer("alice", false)
        val b = TestPeer("bob", false, autoAccept = false)
        pair(a, b)
        val src = Files.createTempDirectory("beam-src").toFile()
        val out = a.link.offer(listOf(FileSource(randomFile(src, "a.bin", 10))), null)
        await(out)
        assertEquals(TransferState.DECLINED, out.state)
    }

    private fun cancelMidway(lan: Boolean, bySender: Boolean) {
        val a = TestPeer("alice", false)
        val b = TestPeer("bob", lan)
        pair(a, b)
        val src = Files.createTempDirectory("beam-src").toFile()
        val out = a.link.offer(listOf(FileSource(randomFile(src, "big.bin", 3_000_000), delayMs = 5)), null)
        val inc = b.offers.poll(5, TimeUnit.SECONDS)!!
        while (inc.bytesDone == 0L) Thread.sleep(5)
        if (bySender) out.cancel() else inc.cancel()
        await(out)
        await(inc)
        assertEquals(TransferState.CANCELLED, out.state)
        assertEquals(TransferState.CANCELLED, inc.state)
        assertFalse(File(b.dir, "big.bin").exists())
        // The link survives a cancelled transfer.
        val again = a.link.offer(listOf(FileSource(randomFile(src, "small.bin", 100))), null)
        await(again)
        assertEquals(TransferState.DONE, again.state)
    }

    @Test
    fun senderCancels() = cancelMidway(lan = false, bySender = true)

    @Test
    fun receiverCancels() = cancelMidway(lan = false, bySender = false)

    @Test
    fun senderCancelsOnWifi() = cancelMidway(lan = true, bySender = true)

    @Test
    fun receiverCancelsOnWifi() = cancelMidway(lan = true, bySender = false)

    @Test
    fun lostLinkFailsTransfer() {
        val a = TestPeer("alice", false)
        val b = TestPeer("bob", false)
        pair(a, b)
        val src = Files.createTempDirectory("beam-src").toFile()
        val out = a.link.offer(listOf(FileSource(randomFile(src, "big.bin", 3_000_000), delayMs = 5)), null)
        val inc = b.offers.poll(5, TimeUnit.SECONDS)!!
        while (inc.bytesDone == 0L) Thread.sleep(5)
        a.link.close()
        await(inc)
        await(out)
        assertEquals(TransferState.FAILED, inc.state)
        assertNotEquals(TransferState.DONE, out.state)
        assertFalse(File(b.dir, "big.bin").exists())
    }

    @Test
    fun rejectsForgedIdentity() {
        // A hello signed with a different key than the one it claims must be refused.
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        val real = Identity.generate()
        val forged = Identity(Identity.generate().privateKey, real.publicBytes)
        val a = Session(SocketDuplex(client), LocalInfo(forged, "mallory", "test"))
        val b = Session(SocketDuplex(server.accept()), LocalInfo(Identity.generate(), "bob", "test"))
        val t = Thread { runCatching { a.handshake() } }.apply { start() }
        val result = runCatching { b.handshake() }
        assertTrue(result.exceptionOrNull() is ProtocolException)
        client.close()
        t.join()
    }

    @Test
    fun safeNames() {
        assertEquals("passwd", Transfer.safeName("../../etc/passwd"))
        assertEquals("a.txt", Transfer.safeName("C:\\x\\a.txt"))
        assertEquals("file", Transfer.safeName("..."))
    }

    // ---- Kotlin <-> Swift. Runs when BEAM_SWIFT_CLI points at the built beam-cli. ----

    /** A name with accents, where the JVM's locale can create such files. */
    private val oddName: String =
        if ((System.getProperty("sun.jnu.encoding") ?: "").startsWith("UTF", true)) "ünï côdé.txt" else "plain name.txt"

    private fun swiftCli(): String {
        val cli = System.getenv("BEAM_SWIFT_CLI")
        assumeTrue("BEAM_SWIFT_CLI not set", !cli.isNullOrEmpty())
        return cli!!
    }

    private fun waitForLine(p: Process, prefix: String): String {
        val r = p.inputStream.bufferedReader()
        while (true) {
            val line = r.readLine() ?: error("swift cli exited before printing $prefix")
            if (line.startsWith(prefix)) return line
        }
    }

    private fun kotlinToSwift(lan: Boolean) {
        val cli = swiftCli()
        val outDir = Files.createTempDirectory("beam-swift-out").toFile()
        val args = mutableListOf(cli, "recv", outDir.path)
        if (lan) args.add("--lan")
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        try {
            val port = waitForLine(p, "READY ").removePrefix("READY ").trim().toInt()
            val a = TestPeer("kotlin", false)
            a.attach(Socket("127.0.0.1", port))
            assertTrue(a.ready.await(5, TimeUnit.SECONDS))
            assertEquals("swift", a.link.peer.name)
            val src = Files.createTempDirectory("beam-src").toFile()
            val files = listOf(randomFile(src, "video.mov", 2_500_123), randomFile(src, oddName, 77))
            val out = a.link.offer(files.map { FileSource(it) }, null)
            await(out)
            assertEquals(out.error, TransferState.DONE, out.state)
            assertEquals(lan, out.viaLan)
            assertEquals("DONE DONE lan=$lan", waitForLine(p, "DONE "))
            for (f in files) assertArrayEquals(f.readBytes(), File(outDir, f.name).readBytes())
            val text = a.link.offer(emptyList(), "héllo from kotlin")
            await(text)
            assertEquals(TransferState.DONE, text.state)
            waitForLine(p, "DONE DONE")
            assertEquals("héllo from kotlin", File(outDir, "text.txt").readText())
        } finally {
            p.destroy()
        }
    }

    private fun swiftToKotlin(lan: Boolean) {
        val cli = swiftCli()
        val b = TestPeer("kotlin", lan)
        val server = ServerSocket(0)
        val src = Files.createTempDirectory("beam-src").toFile()
        val files = listOf(randomFile(src, "video.mov", 2_500_123), randomFile(src, oddName, 77))
        val p = ProcessBuilder(listOf(cli, "send", "127.0.0.1", server.localPort.toString()) + files.map { it.path })
            .redirectErrorStream(true).start()
        try {
            b.attach(server.accept())
            val inc = b.offers.poll(10, TimeUnit.SECONDS)!!
            assertEquals("swift", inc.peer.name)
            await(inc)
            assertEquals(inc.error, TransferState.DONE, inc.state)
            assertEquals(lan, inc.viaLan)
            for (f in files) assertArrayEquals(f.readBytes(), File(b.dir, f.name).readBytes())
            assertEquals("DONE DONE lan=$lan", waitForLine(p, "DONE "))
        } finally {
            p.destroy()
            server.close()
        }
    }

    @Test
    fun interopKotlinToSwift() = kotlinToSwift(false)

    @Test
    fun interopKotlinToSwiftWifi() = kotlinToSwift(true)

    @Test
    fun interopSwiftToKotlin() = swiftToKotlin(false)

    @Test
    fun interopSwiftToKotlinWifi() = swiftToKotlin(true)
}
