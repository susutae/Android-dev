package com.example.meshdevicesearch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class TopologyNode(
    val id: Int, val ip: String, val resource: Double?, val latitude: Double?,
    val longitude: Double?, val altitude: Double?,
    val name: String,
)
internal data class NodeTelemetry(
    val rx: Double?, val tx: Double?, val temperature: Double?, val name: String?, val gpsStatus: Int?,
    val ranges: Map<Int, Double>,
)
internal data class TopologyLink(val from: Int, val to: Int, val snr: Double)
internal data class NetworkTopology(
    val nodes: List<TopologyNode>, val links: List<TopologyLink>, val sourceRanges: Map<Int, Double> = emptyMap(),
) {
    companion object {
        fun parse(body: String): NetworkTopology {
            val snapshot = JSONObject(body)
            val entries = snapshot.getJSONArray("nodeInfos")
            if (entries.length() > 128) throw IOException("Too many nodes to display")
            val nodes = List(entries.length()) { index ->
                val node = entries.getJSONObject(index)
                fun metric(key: String) = node.optDouble(key).takeIf { it.isFinite() }
                TopologyNode(node.getInt("id"), node.optString("ip"), metric("resourceRatio"),
                    metric("latitude"), metric("longitude"), metric("altitude"),
                    node.optString("name").trim().ifBlank { node.optString("nodeName").trim() })
            }
            val matrix = snapshot.optJSONArray("linkQuality")
            val links = buildList {
                nodes.indices.forEach { from ->
                    val row = matrix?.optJSONArray(from)
                    nodes.indices.forEach { to ->
                        val snr = row?.optDouble(to) ?: Double.NaN
                        if (from != to && snr.isFinite() && snr != -10.0) add(TopologyLink(from, to, snr))
                    }
                }
            }
            return NetworkTopology(nodes, links, readRanges(snapshot))
        }

        private fun readRanges(snapshot: JSONObject?): Map<Int, Double> = buildMap {
            val delays = snapshot?.optJSONArray("transmissionDelay") ?: return@buildMap
            for (index in 0 until delays.length()) {
                val entry = delays.optJSONObject(index) ?: continue
                val id = entry.optInt("id", -1)
                val delay = entry.optDouble("delay")
                if (id >= 0 && delay.isFinite() && delay >= 0) put(id, delay * 299792.458 / 100_000_000)
            }
        }

        suspend fun read(device: DiscoveredDevice): NetworkTopology = withContext(Dispatchers.IO) {
            parse(readBody(device, "/status?content=nodeInfos,linkQuality,transmissionDelay"))
        }

        suspend fun telemetry(device: DiscoveredDevice, node: TopologyNode): NodeTelemetry = withContext(Dispatchers.IO) {
            val ip = node.ip
            val parts = ip.split('.')
            require(parts.size == 4 && parts.all { part ->
                val octet = part.toIntOrNull()
                octet != null && octet in 0..255 && octet.toString() == part
            })
            val peer = device.copy(ipAddress = ip)
            fun read(path: String) = runCatching { JSONObject(readBody(peer, path)) }.getOrNull()
            val phyRequest = async { read("/statusadvanced?content=phyRxBytes,phyTxBytes") }
            val statusRequest = async { read("/status?content=temp,transmissionDelay,devices") }
            val nameRequest = async {
                node.name.ifBlank { read("/config?content=name")?.optString("name")?.trim().orEmpty() }
            }
            val phy = phyRequest.await()
            val status = statusRequest.await()
            fun rate(key: String) = phy?.keys()?.asSequence()
                ?.firstOrNull { it.trim().equals(key, ignoreCase = true) }
                ?.let { phy.optDouble(it).takeIf { value -> value.isFinite() && value >= 0 } }
                ?.times(0.000008)
            val devices = status?.optJSONArray("devices")
            val gps = (0 until (devices?.length() ?: 0)).asSequence()
                .mapNotNull { devices?.optJSONObject(it) }
                .firstOrNull { it.optString("type").equals("gps", ignoreCase = true) }
                ?.optInt("status", -1)
            NodeTelemetry(rate("phyRxBytes"), rate("phyTxBytes"),
                status?.optDouble("temp")?.takeIf { it.isFinite() }, nameRequest.await(), gps, readRanges(status))
        }

        private fun readBody(device: DiscoveredDevice, path: String): String {
            val network = device.discoveryNetwork ?: throw IOException("Run discovery again to select a network")
            val url = URL("http", device.ipAddress, path)
            val connection = network.openConnection(url, Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.useCaches = false
                val status = connection.responseCode
                if (status !in 200..299) throw IOException("HTTP $status")
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(524_289)
                    var size = 0
                    while (size < buffer.size) {
                        val count = reader.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        size += count
                    }
                    if (size == buffer.size) throw IOException("Topology response too large")
                    String(buffer, 0, size)
                }
                return body
            } finally {
                connection.disconnect()
            }
        }
    }
}

internal class TopologyGraph(context: Context, private val sourceIp: String) : View(context) {
    var onNodeSelected: (Int) -> Unit = {}
    private var topology = NetworkTopology(emptyList(), emptyList())
    private var selectedId: Int? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linkPath = Path()
    private val density = resources.displayMetrics.density
    private var points = emptyList<PointF>()
    private var radius = 0f
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val index = points.indices.minByOrNull { hypot(points[it].x - e.x, points[it].y - e.y) }
                ?: return false
            val point = points[index]
            if (hypot(point.x - e.x, point.y - e.y) > maxOf(radius, 24 * density)) return false
            onNodeSelected(topology.nodes[index].id)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    fun update(snapshot: NetworkTopology, selection: Int?) {
        if (topology == snapshot && selectedId == selection) return
        topology = snapshot
        selectedId = selection
        placeNodes(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placeNodes(w, h)
    }

    private fun placeNodes(w: Int, h: Int) {
        val ring = (minOf(w, h) / 2f - 32 * density).coerceAtLeast(0f)
        radius = minOf(18 * density, ring * 2 / topology.nodes.size.coerceAtLeast(1)).coerceAtLeast(4 * density)
        val slots = topology.nodes.indices.sortedBy { topology.nodes[it].id }.withIndex().associate { it.value to it.index }
        points = topology.nodes.indices.map { index ->
            val angle = 2 * PI * slots.getValue(index) / topology.nodes.size - PI / 2
            val distance = if (topology.nodes.size == 1) 0f else ring
            PointF(w / 2f + cos(angle).toFloat() * distance, h / 2f + sin(angle).toFloat() * distance)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(context.getColor(R.color.mesh_input))
        paint.color = context.getColor(R.color.mesh_border)
        for (x in 0 until width step (20 * density).toInt().coerceAtLeast(1)) {
            for (y in 0 until height step (20 * density).toInt().coerceAtLeast(1)) {
                canvas.drawCircle(x.toFloat(), y.toFloat(), density / 2, paint)
            }
        }
        topology.links.forEach { link ->
            val a = points[link.from]
            val b = points[link.to]
            val distance = hypot(b.x - a.x, b.y - a.y)
            if (distance <= radius * 2) return@forEach
            val dx = (b.x - a.x) / distance
            val dy = (b.y - a.y) / distance
            val startX = a.x + dx * radius
            val startY = a.y + dy * radius
            val endX = b.x - dx * radius
            val endY = b.y - dy * radius
            // Reversing the link flips the normal, separating the two directions.
            val bend = (distance * 0.11f).coerceIn(17 * density, 34 * density)
            val controlX = (startX + endX) / 2 - dy * bend
            val controlY = (startY + endY) / 2 + dx * bend
            paint.color = when {
                link.snr >= 27 -> 0xff48b9d3.toInt()
                link.snr >= 13 -> 0xff4ade80.toInt()
                link.snr >= 8 -> 0xfffacc15.toInt()
                link.snr >= 3 -> 0xfffb923c.toInt()
                else -> 0xfff87171.toInt()
            }
            paint.strokeWidth = 2 * density
            linkPath.rewind()
            linkPath.moveTo(startX, startY)
            linkPath.quadTo(controlX, controlY, endX, endY)
            paint.style = Paint.Style.STROKE
            canvas.drawPath(linkPath, paint)
            paint.style = Paint.Style.FILL
            val angle = atan2(endY - controlY, endX - controlX)
            val arrow = 7 * density
            for (side in listOf(-0.55f, 0.55f)) {
                canvas.drawLine(endX, endY, endX - cos(angle + side) * arrow, endY - sin(angle + side) * arrow, paint)
            }
            if (topology.links.size <= 18) {
                paint.textSize = 11 * density
                paint.textAlign = Paint.Align.CENTER
                val labelOffset = 8 * density
                val labelX = (startX + 2 * controlX + endX) / 4 - dy * labelOffset
                val labelY = (startY + 2 * controlY + endY) / 4 + dx * labelOffset
                canvas.drawText(link.snr.toString(), labelX, labelY - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        points.forEachIndexed { index, point ->
            val node = topology.nodes[index]
            paint.color = context.getColor(when {
                node.id == selectedId -> R.color.mesh_warning
                node.ip == sourceIp -> R.color.mesh_cyan
                else -> R.color.mesh_border
            })
            canvas.drawCircle(point.x, point.y, radius, paint)
            paint.color = context.getColor(R.color.mesh_panel)
            canvas.drawCircle(point.x, point.y, radius - density, paint)
            paint.color = Color.WHITE
            paint.textSize = minOf(13 * density, radius)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(node.id.toString(), point.x, point.y - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }
}
