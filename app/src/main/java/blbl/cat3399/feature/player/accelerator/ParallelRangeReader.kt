package blbl.cat3399.feature.player.accelerator

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal class RangeUnsupportedException : IOException("Server does not support byte ranges")

/**
 * Native adaptation of BTR's ordered, validated concurrent Range downloads.
 * Only complete URLs supplied by the play API are used; signatures and hosts are never rewritten.
 * A reader owns at most [maxConnections] 256 KiB chunks, including the chunk being consumed.
 */
internal class ParallelRangeReader(
    private val client: OkHttpClient,
    candidates: List<String>,
    maxConnections: Int = 8,
    private val headers: Map<String, String> = emptyMap(),
    private val onBytes: (url: String, count: Int) -> Unit = { _, _ -> },
    private val routes: RangeRoutes = RangeRoutes(candidates),
) : AutoCloseable {
    private val urls = candidates.distinct()
    private val requestClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val connections = maxConnections.coerceIn(1, 8)
    private val executor = Executors.newFixedThreadPool(connections) { runnable ->
        Thread(runnable, "blbl-range").apply { isDaemon = true }
    }
    private val calls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    private val pending = ConcurrentSkipListMap<Long, Future<ByteArray>>()
    @Volatile private var closed = false
    @Volatile private var totalLength: Long? = null
    private var endExclusive = 0L
    private var nextPosition = 0L
    private var current = ByteArray(0)
    private var currentOffset = 0
    private var opened = false

    fun open(position: Long, length: Long? = null): Long {
        check(!opened) { "Reader may only be opened once" }
        check(!closed) { "Reader is closed" }
        require(position >= 0L && (length == null || length >= 0L))
        require(urls.isNotEmpty()) { "No media URLs" }
        opened = true
        if (length == 0L) return 0L
        val requestedEnd = if (length == null) Long.MAX_VALUE else saturatedAdd(position, length)
        current = download(position, min(saturatedAdd(position, CHUNK_SIZE.toLong()), requestedEnd), allowEof = true)
        endExclusive = min(requestedEnd, checkNotNull(totalLength))
        nextPosition = position + current.size
        fillWindow()
        return (endExclusive - position).coerceAtLeast(0)
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(opened) { "read before open" }
        checkBounds(buffer, offset, length)
        ensureActive()
        if (length == 0) return 0
        if (currentOffset == current.size) {
            current = ByteArray(0)
            currentOffset = 0
            fillWindow()
            val first = pending.entries.firstOrNull() ?: return -1
            current = await(first.value)
            pending.remove(first.key)
            fillWindow()
        }
        val count = min(length, current.size - currentOffset)
        current.copyInto(buffer, offset, currentOffset, currentOffset + count)
        currentOffset += count
        return count
    }

    private fun fillWindow() {
        ensureActive()
        // Include current in the memory budget. Queue no unbounded prefetch work.
        while (pending.size < connections - 1 && nextPosition < endExclusive) {
            val start = nextPosition
            val end = min(saturatedAdd(start, CHUNK_SIZE.toLong()), endExclusive)
            pending[start] = submit(start, end)
            nextPosition = end
        }
        // A one-connection reader still needs its next chunk after consuming the current one.
        if (current.isEmpty() && pending.isEmpty() && nextPosition < endExclusive) {
            val start = nextPosition
            val end = min(saturatedAdd(start, CHUNK_SIZE.toLong()), endExclusive)
            pending[start] = submit(start, end)
            nextPosition = end
        }
    }

    private fun submit(start: Long, end: Long): Future<ByteArray> = try {
        executor.submit(Callable { download(start, end) })
    } catch (e: RejectedExecutionException) {
        throw InterruptedIOException("Media download cancelled")
    }

    private fun download(start: Long, requestedEnd: Long, allowEof: Boolean = false): ByteArray {
        var lastError: IOException? = null
        var unsupported = false
        var received = 0
        var output: ByteArray? = null
        var chunkTotal: Long? = null
        val pieceStarted = System.nanoTime()
        // Two passes allow recovery from a transient disconnect even with a single CDN.
        val order = routes.ordered()
        for (attempt in 0 until min(order.size * 2, 8)) {
            ensureActive()
            val url = order[attempt % order.size]
            var permit = false
            routes.started(url)
            var call: Call? = null
            val started = System.nanoTime()
            val receivedBeforeAttempt = received
            try {
                while (!permit) {
                    ensureActive()
                    permit = GLOBAL_CONNECTIONS.tryAcquire(100, TimeUnit.MILLISECONDS)
                }
                ensureActive()
                val budget = TimeUnit.SECONDS.toNanos(25) - (System.nanoTime() - pieceStarted)
                if (budget <= 0) throw IOException("Media Range retry deadline exceeded")
                val requestStart = start + received
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    header("Accept-Encoding", "identity")
                    header("Range", "bytes=$requestStart-${requestedEnd - 1}")
                }.build()
                call = requestClient.newCall(request)
                call.timeout().timeout(min(budget, TimeUnit.SECONDS.toNanos(10)), TimeUnit.NANOSECONDS)
                calls.add(call)
                // close() can run between the first check and adding the call.
                if (closed) call.cancel()
                call.execute().use { response ->
                    if (response.code == 200) throw RangeUnsupportedException()
                    if (response.code == 416 && allowEof) {
                        val total = UNSATISFIED_RANGE.matchEntire(response.header("Content-Range").orEmpty())
                            ?.groupValues?.get(1)?.toLongOrNull()
                        if (total != null && start == total) {
                            acceptTotal(total)
                            return ByteArray(0)
                        }
                    }
                    if (response.code != 206) throw IOException("Media Range HTTP ${response.code}")
                    val range = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
                        ?: throw IOException("Invalid media Content-Range")
                    val actualStart = range.groupValues[1].toLongOrNull()
                    val actualEnd = range.groupValues[2].toLongOrNull()
                    val total = range.groupValues[3].toLongOrNull()
                        ?: throw IOException("Media Range has no total length")
                    val expectedEnd = min(requestedEnd, total)
                    if (actualStart != requestStart || actualEnd != expectedEnd - 1 || total <= requestStart) {
                        throw IOException("Media Range positions do not match request")
                    }
                    val encoding = response.header("Content-Encoding")
                    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                        throw IOException("Encoded media Range")
                    }
                    if (totalLength != null && totalLength != total) throw IOException("Media length changed between CDNs")
                    if (chunkTotal != null && chunkTotal != total) throw IOException("Media length changed during a Range retry")
                    chunkTotal = total
                    val size = (expectedEnd - start).toInt()
                    val body = response.body ?: throw EOFException("Missing media body")
                    if (body.contentLength() != -1L && body.contentLength() != (size - received).toLong()) {
                        throw IOException("Media Content-Length does not match range")
                    }
                    val data = output ?: ByteArray(size).also { output = it }
                    body.byteStream().use { stream ->
                        while (received < size) {
                            ensureActive()
                            val count = stream.read(data, received, size - received)
                            if (count < 0) throw EOFException("Truncated media Range")
                            if (count == 0) continue
                            received += count
                            onBytes(url, count)
                        }
                        if (stream.read() != -1) {
                            // A malformed response is not a safe prefix for a later retry.
                            received = 0
                            output = null
                            chunkTotal = null
                            throw IOException("Oversized media Range")
                        }
                    }
                    acceptTotal(total)
                    routes.succeeded(url, size - receivedBeforeAttempt, System.nanoTime() - started)
                    return data
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Media download interrupted")
            } catch (e: IOException) {
                ensureActive()
                // Metadata from a response that supplied no validated bytes must not pin
                // the total length and prevent a healthy backup CDN from taking over.
                if (received == 0 || (output != null && received == output?.size)) {
                    received = 0
                    output = null
                    chunkTotal = null
                }
                unsupported = unsupported || e is RangeUnsupportedException
                lastError = e
                routes.failed(url)
            } finally {
                call?.let { calls.remove(it) }
                if (permit) GLOBAL_CONNECTIONS.release()
                routes.finished(url)
            }
        }
        if (unsupported) throw RangeUnsupportedException()
        throw lastError ?: IOException("No working media CDN")
    }

    @Synchronized
    private fun acceptTotal(total: Long) {
        if (totalLength != null && totalLength != total) throw IOException("Media length changed between CDNs")
        totalLength = total
    }

    private fun await(future: Future<ByteArray>): ByteArray =
        try {
            future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Media download interrupted")
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException ?: IOException("Media download failed", e.cause))
        } catch (e: java.util.concurrent.CancellationException) {
            throw InterruptedIOException("Media download cancelled")
        }

    private fun ensureActive() {
        if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("Media download cancelled")
    }

    override fun close() {
        closed = true
        calls.forEach { it.cancel() }
        // Cancellation interrupts semaphore waits as well as active socket reads.
        executor.shutdownNow()
        pending.values.forEach { it.cancel(true) }
    }

    companion object {
        internal const val CHUNK_SIZE = 256 * 1024
        private val GLOBAL_CONNECTIONS = Semaphore(8, true)
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        private val UNSATISFIED_RANGE = Regex("bytes \\*/(\\d+)", RegexOption.IGNORE_CASE)

        private fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

        private fun checkBounds(buffer: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        }
    }
}

/** Small per-stream EWMA health table shared by its factory's DataSources. */
internal class RangeRoutes(private val candidates: List<String>) {
    private data class Health(var speed: Double = 0.0, var failures: Int = 0, var retryAfter: Long = 0, var active: Int = 0)
    private val health = candidates.associateWith { Health() }

    @Synchronized
    fun ordered(): List<String> {
        val now = System.nanoTime()
        return candidates.sortedWith(compareBy<String> { health.getValue(it).retryAfter > now }
            .thenBy { health.getValue(it).active }
            .thenByDescending { health.getValue(it).speed })
    }

    @Synchronized
    fun started(url: String) { health.getValue(url).active++ }

    @Synchronized
    fun finished(url: String) { health.getValue(url).active-- }

    @Synchronized
    fun succeeded(url: String, bytes: Int, nanos: Long) {
        val entry = health.getValue(url)
        val speed = bytes * 1_000_000_000.0 / nanos.coerceAtLeast(1)
        entry.speed = if (entry.speed == 0.0) speed else entry.speed * 0.65 + speed * 0.35
        entry.failures = 0
        entry.retryAfter = 0
    }

    @Synchronized
    fun failed(url: String) {
        val entry = health.getValue(url)
        entry.failures = (entry.failures + 1).coerceAtMost(5)
        entry.retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos((3L shl (entry.failures - 1)).coerceAtMost(60L))
    }
}
