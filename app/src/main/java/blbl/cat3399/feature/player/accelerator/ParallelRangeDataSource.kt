@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player.accelerator

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.io.IOException

/** VOD-only transport. HLS/live sources must continue to use the original factory. */
internal class ParallelRangeDataSource private constructor(
    private val factory: Factory,
) : DataSource {
    private val listeners = ArrayList<TransferListener>(2)
    private var reader: ParallelRangeReader? = null
    private var fallback: DataSource? = null
    private var spec: DataSpec? = null
    private var remaining = 0L
    private var consumed = 0L
    private var started = false
    @Volatile private var closed = false
    @Volatile private var activeSession: Any? = null

    internal class Factory(
        internal val client: OkHttpClient,
        internal val candidates: List<Uri>,
        maxConnections: Int = 8,
        internal val defaultRequestProperties: Map<String, String> = emptyMap(),
        fallbackFactory: DataSource.Factory? = null,
    ) : DataSource.Factory {
        internal val connections = maxConnections.coerceIn(1, 8)
        internal val directFallback = OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(defaultRequestProperties)
        internal val fallback = fallbackFactory ?: directFallback
        internal val routes = RangeRoutes(candidates.map { it.toString() }.distinct())
        @Volatile internal var disabledUntilNanos = 0L

        override fun createDataSource(): DataSource = ParallelRangeDataSource(this)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        fallback?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        closed = false
        spec = dataSpec
        consumed = 0L
        val session = Any()
        activeSession = session
        // Tiny DASH initialization/index requests gain nothing from a parallel window.
        if (System.nanoTime() < factory.disabledUntilNanos || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET ||
            (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length < 128 * 1024) ||
            dataSpec.uri !in factory.candidates || dataSpec.uri.scheme !in listOf("http", "https")
        ) return openFallback(dataSpec)

        listeners.forEach { it.onTransferInitializing(this, dataSpec, true) }
        val rangeReader = ParallelRangeReader(
            client = factory.client,
            candidates = factory.candidates.map { it.toString() }.distinct(),
            maxConnections = factory.connections,
            headers = factory.defaultRequestProperties + dataSpec.httpRequestHeaders,
            onBytes = { _, count ->
                // Report bytes as they arrive, including prefetched bytes, instead of counting
                // memory copies to the decoder as additional network traffic.
                synchronized(listeners) {
                    if (activeSession === session) {
                        listeners.forEach { it.onBytesTransferred(this, dataSpec, true, count) }
                    }
                }
            },
            routes = factory.routes,
        )
        reader = rangeReader
        started = true
        listeners.forEach { it.onTransferStart(this, dataSpec, true) }
        return try {
            rangeReader.open(
                dataSpec.position,
                dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() },
            ).also { remaining = it }
        } catch (e: RangeUnsupportedException) {
            factory.disabledUntilNanos = System.nanoTime() + 60_000_000_000L
            activeSession = null
            rangeReader.close()
            reader = null
            endTransfer()
            if (closed) throw IOException("Media download cancelled", e)
            openFallback(dataSpec)
        } catch (e: IOException) {
            activeSession = null
            rangeReader.close()
            reader = null
            endTransfer()
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        fallback?.let { return it.read(buffer, offset, length) }
        val rangeReader = checkNotNull(reader) { "read before open" }
        return try {
            rangeReader.read(buffer, offset, length).also {
                if (it > 0) {
                    consumed += it
                    remaining -= it
                }
            }
        } catch (e: RangeUnsupportedException) {
            factory.disabledUntilNanos = System.nanoTime() + 60_000_000_000L
            activeSession = null
            rangeReader.close()
            reader = null
            endTransfer()
            if (closed) throw IOException("Media download cancelled", e)
            val resume = checkNotNull(spec).subrange(consumed, remaining)
            openFallback(resume)
            checkNotNull(fallback).read(buffer, offset, length)
        }
    }

    private fun openFallback(dataSpec: DataSpec): Long {
        // Subtitle/manifest requests must never be replaced by a media candidate URL.
        val upstream = (if (dataSpec.uri in factory.candidates) factory.fallback else factory.directFallback).createDataSource()
        fallback = upstream
        listeners.forEach { upstream.addTransferListener(it) }
        return try {
            upstream.open(dataSpec)
        } catch (e: IOException) {
            runCatching { upstream.close() }
            fallback = null
            throw e
        }
    }

    override fun getUri(): Uri? = fallback?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = fallback?.responseHeaders ?: emptyMap()

    override fun close() {
        closed = true
        activeSession = null
        reader?.close()
        reader = null
        runCatching { fallback?.close() }
        fallback = null
        endTransfer()
        spec = null
    }

    private fun endTransfer() {
        synchronized(listeners) {
            if (started) {
                started = false
                spec?.let { dataSpec -> listeners.forEach { it.onTransferEnd(this, dataSpec, true) } }
            }
        }
    }
}
