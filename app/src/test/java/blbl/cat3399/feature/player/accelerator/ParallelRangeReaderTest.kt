package blbl.cat3399.feature.player.accelerator

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ParallelRangeReaderTest {
    private val payload = ByteArray(ParallelRangeReader.CHUNK_SIZE * 7 + 173).apply { Random(41).nextBytes(this) }
    private val server = MockWebServer()
    private val handlers = ConcurrentHashMap<String, (TestExchange, Long, Long) -> Unit>()
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
    private val readers = mutableListOf<ParallelRangeReader>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val handler = handlers[request.path?.substringBefore('?')] ?: return MockResponse().setResponseCode(404)
                val range = checkNotNull(request.getHeader("Range")).removePrefix("bytes=").split('-')
                val exchange = TestExchange()
                handler(exchange, range[0].toLong(), range[1].toLong())
                return exchange.response()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        readers.forEach { it.close() }
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    @Test
    fun reordersConcurrentChunksAndBoundsPrefetch() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val requests = AtomicInteger()
        endpoint("media") { exchange, start, end ->
            requests.incrementAndGet()
            val concurrent = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, concurrent) }
            try {
                if (start > 0) Thread.sleep(if (start / ParallelRangeReader.CHUNK_SIZE % 2L == 1L) 130 else 20)
                respond(exchange, start, end)
            } finally {
                active.decrementAndGet()
            }
        }
        val reader = reader("media", connections = 4)
        assertEquals(payload.size.toLong(), reader.open(0))
        Thread.sleep(220)
        assertEquals("Only the initial bounded window may be requested before consumption", 4, requests.get())
        assertArrayEquals(payload, readAll(reader))
        assertTrue("Ranges should run concurrently", peak.get() >= 2)
        assertTrue(peak.get() <= 4)
    }

    @Test
    fun readsNonzeroBoundedRangeAndFinalPartialChunk() {
        endpoint("media") { exchange, start, end -> respond(exchange, start, end) }
        val start = 357L
        val length = payload.size - 359L
        val reader = reader("media")
        assertEquals(length, reader.open(start, length))
        assertArrayEquals(payload.copyOfRange(start.toInt(), (start + length).toInt()), readAll(reader))
    }

    @Test
    fun singleConnectionContinuesAfterFirstChunk() {
        endpoint("media") { exchange, start, end -> respond(exchange, start, end) }
        val reader = reader("media", connections = 1)
        reader.open(0)
        assertArrayEquals(payload, readAll(reader))
    }

    @Test
    fun ignoredRangesAreReportedForSequentialFallback() {
        endpoint("media") { exchange, _, _ ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        assertThrows(RangeUnsupportedException::class.java) { reader("media").open(0) }
    }

    @Test
    fun wrongRangeNeverReachesDecoderAndUsesBackup() {
        endpoint("bad") { exchange, start, end -> respond(exchange, start, end, responseStart = start + 1) }
        endpoint("good") { exchange, start, end -> respond(exchange, start, end) }
        val reader = reader("bad", "good")
        reader.open(0)
        assertArrayEquals(payload, readAll(reader))
    }

    @Test
    fun invalidLengthWithoutReceivedBytesDoesNotPinWrongTotal() {
        val backupStartedAt = java.util.concurrent.atomic.AtomicLong(-1)
        endpoint("bad") { exchange, start, end ->
            val last = minOf(end, payload.lastIndex.toLong())
            exchange.responseHeaders.add("Content-Range", "bytes $start-$last/${payload.size + 1}")
            // A bogus total accompanied by an invalid body length must be discarded.
            exchange.sendResponseHeaders(206, 17)
            exchange.responseBody.use { it.write(payload, start.toInt(), 17) }
        }
        endpoint("good") { exchange, start, end ->
            backupStartedAt.compareAndSet(-1, start)
            respond(exchange, start, end)
        }
        val reader = reader("bad", "good")
        assertEquals(payload.size.toLong(), reader.open(0))
        assertEquals("The rejected response supplied no usable prefix", 0L, backupStartedAt.get())
        assertArrayEquals(payload, readAll(reader))
    }

    @Test
    fun truncatedResponseIsRetriedOnBackup() {
        val resumedAt = java.util.concurrent.atomic.AtomicLong(-1)
        endpoint("bad") { exchange, start, end ->
            val last = minOf(end, payload.lastIndex.toLong())
            exchange.responseHeaders.add("Content-Range", "bytes $start-$last/${payload.size}")
            exchange.sendResponseHeaders(206, last - start + 1)
            exchange.responseBody.use { it.write(payload, start.toInt(), 17) }
        }
        endpoint("good") { exchange, start, end ->
            resumedAt.compareAndSet(-1, start)
            respond(exchange, start, end)
        }
        val reader = reader("bad", "good")
        reader.open(0)
        assertEquals("Only the missing tail should be retried", 17L, resumedAt.get())
        assertArrayEquals(payload, readAll(reader))
    }

    @Test
    fun inconsistentTotalOnLaterRangesIsRejected() {
        endpoint("media") { exchange, start, end ->
            respond(exchange, start, end, total = payload.size + if (start > 0) 1 else 0)
        }
        val reader = reader("media")
        reader.open(0)
        assertThrows(IOException::class.java) { readAll(reader) }
    }

    @Test
    fun closeCancelsSocketAndDoesNotRetry() {
        val arrived = CountDownLatch(1)
        val requests = AtomicInteger()
        endpoint("media") { exchange, start, end ->
            requests.incrementAndGet()
            if (start == 0L) respond(exchange, start, end) else {
                arrived.countDown()
                exchange.socketPolicy = SocketPolicy.NO_RESPONSE
            }
        }
        val reader = reader("media")
        reader.open(0)
        assertTrue(arrived.await(2, TimeUnit.SECONDS))
        val finished = CountDownLatch(1)
        val failed = AtomicBoolean()
        val worker = Thread {
            try { readAll(reader) } catch (_: IOException) { failed.set(true) } finally { finished.countDown() }
        }
        worker.start()
        reader.close()
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertTrue(failed.get())
        assertTrue(requests.get() <= 4)
    }

    @Test
    fun exactEofReturnsEmptyAndPastEofThrows() {
        endpoint("media") { exchange, start, end ->
            if (start >= payload.size) {
                exchange.responseHeaders.add("Content-Range", "bytes */${payload.size}")
                exchange.sendResponseHeaders(416, -1)
            } else respond(exchange, start, end)
        }
        val atEnd = reader("media")
        assertEquals(0L, atEnd.open(payload.size.toLong()))
        assertArrayEquals(ByteArray(0), readAll(atEnd))
        assertThrows(IOException::class.java) { reader("media").open(payload.size + 1L) }
    }

    private fun reader(vararg paths: String, connections: Int = 4): ParallelRangeReader =
        ParallelRangeReader(client, paths.map { server.url("/$it").toString() }, connections)
            .also { readers += it }

    private fun endpoint(path: String, handle: (TestExchange, Long, Long) -> Unit) {
        handlers["/$path"] = handle
    }

    private fun respond(exchange: TestExchange, start: Long, end: Long, responseStart: Long = start, total: Int = payload.size) {
        val last = minOf(end, payload.lastIndex.toLong())
        exchange.responseHeaders.add("Content-Range", "bytes $responseStart-$last/$total")
        exchange.sendResponseHeaders(206, last - start + 1)
        exchange.responseBody.use { it.write(payload, start.toInt(), (last - start + 1).toInt()) }
    }

    /** Keep response construction independent from MockWebServer's automatic Content-Length. */
    private class TestExchange {
        val responseHeaders = Headers.Builder()
        val responseBody = ByteArrayOutputStream()
        var socketPolicy = SocketPolicy.KEEP_OPEN
        private var status = 200
        private var declaredLength = -1L

        fun sendResponseHeaders(code: Int, length: Long) {
            status = code
            declaredLength = length
        }

        fun response(): MockResponse {
            val bytes = responseBody.toByteArray()
            return MockResponse().setResponseCode(status)
                .setBody(Buffer().write(bytes))
                .apply {
                    responseHeaders.build().forEach { (name, value) -> addHeader(name, value) }
                    if (declaredLength >= 0) setHeader("Content-Length", declaredLength)
                    setSocketPolicy(if (declaredLength > bytes.size) SocketPolicy.DISCONNECT_AT_END else this@TestExchange.socketPolicy)
                }
        }
    }

    private fun readAll(reader: ParallelRangeReader): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8191)
        while (true) {
            val count = reader.read(buffer, 0, buffer.size)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
