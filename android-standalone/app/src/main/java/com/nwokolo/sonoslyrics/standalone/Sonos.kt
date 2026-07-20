package com.nwokolo.sonoslyrics.standalone

import android.util.Log
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

data class SonosDevice(val host: String, val name: String, val uuid: String?)

data class SonosGroup(
    val host: String,            // coordinator host
    val coordinatorUuid: String?,
    val name: String,
    val memberCount: Int,
    val members: List<String>
)

data class NowPlaying(
    val state: String,
    val grouped: Boolean,
    val title: String?,
    val artist: String?,
    val album: String?,
    val duration: String?,
    val position: String?,
    val albumArt: String?
)

/**
 * Native reimplementation of the Node backend's Sonos logic: SSDP discovery,
 * ZoneGroupTopology, and AVTransport polling over the speakers' local UPnP/SOAP
 * interface (port 1400). No external server needed — everything runs on device.
 */
object Sonos {
    private const val TAG = "SonosStandalone"
    private const val SOAP_TIMEOUT = 4000

    /** Optional user-provided speaker IPs; when set, SSDP is skipped entirely. */
    @Volatile
    var staticHosts: List<String> = emptyList()

    @Volatile
    private var deviceCache: List<SonosDevice> = emptyList()

    private data class ParsedGroup(val coordinatorUuid: String, val members: List<SonosDevice>)

    // ---- Public API -------------------------------------------------------

    fun devices(refresh: Boolean): List<SonosDevice> {
        if (refresh || deviceCache.isEmpty()) discover()
        return deviceCache
    }

    fun groups(refresh: Boolean): List<SonosGroup> {
        if (refresh) discover()
        if (deviceCache.isEmpty()) discover()
        if (deviceCache.isEmpty()) return emptyList()

        val parsed = fetchTopologyFromAny() ?: return emptyList()
        return parsed.map { pg ->
            val coord = pg.members.find { it.uuid == pg.coordinatorUuid } ?: pg.members.first()
            val others = pg.members.filter { it.uuid != coord.uuid }
                .sortedBy { it.name.lowercase() }
            val label = if (others.isNotEmpty())
                coord.name + " + " + others.joinToString(" + ") { it.name }
            else coord.name
            SonosGroup(
                host = coord.host,
                coordinatorUuid = coord.uuid,
                name = label,
                memberCount = pg.members.size,
                members = listOf(coord.name) + others.map { it.name }
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun nowPlaying(host: String): NowPlaying {
        var h = host
        var info = getPositionInfo(h)
        var state = transportState(h)

        // If this speaker is a group follower, read from its coordinator.
        var grouped = false
        val coordHost = resolveCoordinatorHost(info.trackUri, h)
        if (coordHost != h) {
            grouped = true
            h = coordHost
            info = getPositionInfo(h)
            state = transportState(h)
        }

        return NowPlaying(
            state = state,
            grouped = grouped,
            title = info.title,
            artist = info.artist,
            album = info.album,
            duration = info.duration,
            position = info.position,
            albumArt = albumArtUrl(h, info.albumArtUri)
        )
    }

    /** Returns (host, name) of whatever is currently playing, or (null, null). */
    fun playing(): Pair<String?, String?> {
        val groups = try { groups(false) } catch (e: Exception) { emptyList() }
        val candidates: List<Pair<String, String>> = if (groups.isNotEmpty()) {
            groups.map { it.host to it.name }
        } else {
            if (deviceCache.isEmpty()) discover()
            deviceCache.map { it.host to it.name }
        }
        if (candidates.isEmpty()) return null to null

        val pool = Executors.newFixedThreadPool(minOf(8, candidates.size))
        try {
            val tasks = candidates.map { (h, name) ->
                Callable { Triple(h, name, transportState(h)) }
            }
            val results = pool.invokeAll(tasks, 8, TimeUnit.SECONDS)
            for (f in results) {
                val r = try { f.get() } catch (e: Exception) { null } ?: continue
                if (r.third == "playing") return r.first to r.second
            }
        } catch (e: Exception) {
            Log.w(TAG, "playing() error: ${e.message}")
        } finally {
            pool.shutdownNow()
        }
        return null to null
    }

    // ---- Discovery --------------------------------------------------------

    @Synchronized
    fun discover(): List<SonosDevice> {
        val hosts = if (staticHosts.isNotEmpty()) staticHosts else ssdpSearch()
        if (hosts.isEmpty()) return deviceCache

        // Prefer the full topology from any reachable speaker: one call yields
        // every zone's name/uuid/host plus grouping.
        for (h in hosts) {
            val topo = try { zoneGroupState(h) } catch (e: Exception) { null }
            if (!topo.isNullOrEmpty()) {
                val devs = topo.flatMap { it.members }
                    .distinctBy { it.uuid ?: it.host }
                    .sortedBy { it.name.lowercase() }
                if (devs.isNotEmpty()) {
                    deviceCache = devs
                    return deviceCache
                }
            }
        }

        // Fallback: describe each discovered host individually.
        val devs = hosts.map { describeHost(it) }.sortedBy { it.name.lowercase() }
        if (devs.isNotEmpty()) deviceCache = devs
        return deviceCache
    }

    private fun fetchTopologyFromAny(): List<ParsedGroup>? {
        val hosts = if (staticHosts.isNotEmpty()) staticHosts else deviceCache.map { it.host }
        for (h in hosts) {
            val parsed = try { zoneGroupState(h) } catch (e: Exception) { null }
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return null
    }

    private fun ssdpSearch(timeoutMs: Int = 2500): List<String> {
        val found = LinkedHashSet<String>()
        val msg = (
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 800
                socket.broadcast = true
                val group = InetAddress.getByName("239.255.255.250")
                val packet = DatagramPacket(msg, msg.size, group, 1900)
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                var lastSend = 0L
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (now - lastSend > 700) {
                        try { socket.send(packet) } catch (_: Exception) {}
                        lastSend = now
                    }
                    try {
                        val resp = DatagramPacket(buf, buf.size)
                        socket.receive(resp)
                        val text = String(resp.data, 0, resp.length, Charsets.US_ASCII)
                        val loc = Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)?.groupValues?.get(1)
                        hostFromLocation(loc)?.let { found.add(it) }
                    } catch (_: java.net.SocketTimeoutException) {
                        // keep looping until the overall deadline
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP error: ${e.message}")
        }
        return found.toList()
    }

    private fun describeHost(host: String): SonosDevice {
        return try {
            val xml = httpGet("http://$host:1400/xml/device_description.xml", 3000)
                ?: return SonosDevice(host, host, null)
            val name = extractTag(xml, "roomName")?.trim()?.ifEmpty { null }
                ?: extractTag(xml, "friendlyName")?.trim()?.ifEmpty { null }
                ?: host
            val uuid = extractTag(xml, "UDN")?.trim()?.removePrefix("uuid:")
            SonosDevice(host, name, uuid)
        } catch (e: Exception) {
            SonosDevice(host, host, null)
        }
    }

    // ---- Topology ---------------------------------------------------------

    private fun zoneGroupState(host: String): List<ParsedGroup> {
        val body = soapEnvelope(
            "urn:schemas-upnp-org:service:ZoneGroupTopology:1",
            "GetZoneGroupState", ""
        )
        val resp = soapCall(
            host, "/ZoneGroupTopology/Control",
            "urn:schemas-upnp-org:service:ZoneGroupTopology:1#GetZoneGroupState", body
        )
        val stateXml = extractTag(resp, "ZoneGroupState") ?: return emptyList()
        val doc = parseXml(unescape(stateXml))
        val out = ArrayList<ParsedGroup>()
        val groupNodes = doc.getElementsByTagName("ZoneGroup")
        for (i in 0 until groupNodes.length) {
            val g = groupNodes.item(i) as? org.w3c.dom.Element ?: continue
            val coord = g.getAttribute("Coordinator")
            val memberNodes = g.getElementsByTagName("ZoneGroupMember")
            val members = ArrayList<SonosDevice>()
            for (j in 0 until memberNodes.length) {
                val m = memberNodes.item(j) as? org.w3c.dom.Element ?: continue
                if (m.getAttribute("Invisible") == "1") continue // satellites/subs/bridges
                val uuid = m.getAttribute("UUID").ifEmpty { null }
                val zoneName = m.getAttribute("ZoneName").ifEmpty { "Speaker" }
                val h = hostFromLocation(m.getAttribute("Location")) ?: continue
                members.add(SonosDevice(h, zoneName, uuid))
            }
            if (members.isNotEmpty()) out.add(ParsedGroup(coord, members))
        }
        return out
    }

    // ---- AVTransport ------------------------------------------------------

    private data class PositionInfo(
        val duration: String?,
        val position: String?,
        val trackUri: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtUri: String?
    )

    private fun getPositionInfo(host: String): PositionInfo {
        return try {
            val body = soapEnvelope(
                "urn:schemas-upnp-org:service:AVTransport:1",
                "GetPositionInfo", "<InstanceID>0</InstanceID>"
            )
            val resp = soapCall(
                host, "/MediaRenderer/AVTransport/Control",
                "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo", body
            )
            val duration = normalizeTime(textTag(resp, "TrackDuration"))
            val position = normalizeTime(textTag(resp, "RelTime"))
            val trackUri = textTag(resp, "TrackURI")?.let { unescape(it) }

            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var art: String? = null
            val metaRaw = extractTag(resp, "TrackMetaData")
            if (!metaRaw.isNullOrBlank() && metaRaw != "NOT_IMPLEMENTED") {
                try {
                    val doc = parseXml(unescape(metaRaw))
                    title = firstTagText(doc, "dc:title")
                    artist = firstTagText(doc, "dc:creator") ?: firstTagText(doc, "r:albumArtist")
                    album = firstTagText(doc, "upnp:album")
                    art = firstTagText(doc, "upnp:albumArtURI")
                } catch (_: Exception) { /* leave nulls */ }
            }
            PositionInfo(duration, position, trackUri, title, artist, album, art)
        } catch (e: Exception) {
            PositionInfo(null, null, null, null, null, null, null)
        }
    }

    private fun transportState(host: String): String {
        return try {
            val body = soapEnvelope(
                "urn:schemas-upnp-org:service:AVTransport:1",
                "GetTransportInfo", "<InstanceID>0</InstanceID>"
            )
            val resp = soapCall(
                host, "/MediaRenderer/AVTransport/Control",
                "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo", body
            )
            when (textTag(resp, "CurrentTransportState")) {
                "PLAYING" -> "playing"
                "PAUSED_PLAYBACK" -> "paused"
                "STOPPED" -> "stopped"
                "TRANSITIONING" -> "transitioning"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun resolveCoordinatorHost(trackUri: String?, requestHost: String): String {
        if (trackUri.isNullOrEmpty()) return requestHost
        val m = Regex("^x-rincon:(RINCON_[0-9A-Fa-f]+)").find(trackUri) ?: return requestHost
        val uuid = m.groupValues[1]
        return deviceCache.find { it.uuid == uuid }?.host ?: requestHost
    }

    private fun albumArtUrl(host: String, uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(uri)) return uri
        return "http://$host:1400" + (if (uri.startsWith("/")) "" else "/") + uri
    }

    // ---- HTTP / SOAP helpers ---------------------------------------------

    fun httpGet(urlStr: String, timeoutMs: Int, headers: Map<String, String> = emptyMap()): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (code in 200..299) text else null
        } finally {
            conn.disconnect()
        }
    }

    private fun soapEnvelope(service: String, action: String, inner: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
        "<s:Body><u:$action xmlns:u=\"$service\">$inner</u:$action></s:Body></s:Envelope>"

    private fun soapCall(
        host: String,
        path: String,
        soapAction: String,
        body: String,
        timeoutMs: Int = SOAP_TIMEOUT
    ): String {
        val conn = URL("http://$host:1400$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$soapAction\"")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("SOAP $soapAction -> HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ---- XML helpers ------------------------------------------------------

    /** Inner content of the first <tag ...>...</tag> occurrence (DOTALL). */
    private fun extractTag(xml: String, tag: String): String? =
        Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)

    private fun textTag(xml: String, tag: String): String? =
        extractTag(xml, tag)?.trim()?.ifEmpty { null }

    private fun firstTagText(doc: Document, tag: String): String? {
        val nodes = doc.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        val t = nodes.item(0).textContent?.trim()
        return if (t.isNullOrEmpty()) null else t
    }

    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun hostFromLocation(loc: String?): String? =
        try { if (loc.isNullOrBlank()) null else URL(loc).host } catch (e: Exception) { null }

    /** Treat NOT_IMPLEMENTED / blank durations as unknown (0). */
    private fun normalizeTime(t: String?): String? {
        if (t.isNullOrBlank() || t == "NOT_IMPLEMENTED") return null
        return t
    }
}
