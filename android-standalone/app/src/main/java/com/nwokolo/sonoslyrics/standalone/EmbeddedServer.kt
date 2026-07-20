package com.nwokolo.sonoslyrics.standalone

import android.content.res.AssetManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Loopback HTTP server that serves the existing web frontend (copied into
 * assets/www at build time) and reimplements the Node backend's API routes
 * natively, so the WebView talks to 127.0.0.1 with no external dependency and
 * no CORS concerns.
 */
class EmbeddedServer(
    private val assets: AssetManager,
    port: Int
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri ?: "/"
            if (uri.startsWith("/api/")) handleApi(uri, session) else serveAsset(uri)
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "error"))
        }
    }

    // ---- API --------------------------------------------------------------

    private fun handleApi(uri: String, session: IHTTPSession): Response {
        val params = session.parameters // Map<String, List<String>>
        fun q(name: String): String? = params[name]?.firstOrNull()

        return when (uri) {
            "/api/devices" -> {
                val devices = Sonos.devices(refresh = q("refresh") != null)
                val arr = JSONArray()
                for (d in devices) {
                    arr.put(JSONObject().put("host", d.host).put("name", d.name)
                        .put("uuid", d.uuid ?: JSONObject.NULL))
                }
                json(Response.Status.OK, JSONObject().put("devices", arr))
            }

            "/api/groups" -> {
                val groups = Sonos.groups(refresh = q("refresh") != null)
                val arr = JSONArray()
                for (g in groups) {
                    val members = JSONArray()
                    g.members.forEach { members.put(it) }
                    arr.put(
                        JSONObject()
                            .put("host", g.host)
                            .put("coordinatorUuid", g.coordinatorUuid ?: JSONObject.NULL)
                            .put("name", g.name)
                            .put("memberCount", g.memberCount)
                            .put("members", members)
                    )
                }
                json(Response.Status.OK, JSONObject().put("groups", arr))
            }

            "/api/nowplaying" -> {
                val host = q("host")
                    ?: return json(Response.Status.BAD_REQUEST,
                        JSONObject().put("error", "Missing host query parameter"))
                val np = Sonos.nowPlaying(host)
                val out = JSONObject().put("state", np.state)
                if (np.title == null && np.artist == null) {
                    out.put("track", JSONObject.NULL)
                } else {
                    out.put("grouped", np.grouped)
                    out.put("track", JSONObject()
                        .put("title", np.title ?: JSONObject.NULL)
                        .put("artist", np.artist ?: JSONObject.NULL)
                        .put("album", np.album ?: JSONObject.NULL)
                        .put("duration", np.duration ?: 0)
                        .put("position", np.position ?: 0)
                        .put("albumArt", np.albumArt ?: JSONObject.NULL))
                }
                json(Response.Status.OK, out)
            }

            "/api/lyrics" -> {
                val artist = q("artist")
                val title = q("title")
                if (artist.isNullOrEmpty() || title.isNullOrEmpty()) {
                    return json(Response.Status.BAD_REQUEST,
                        JSONObject().put("error", "Missing artist or title"))
                }
                val duration = q("duration")?.toDoubleOrNull()?.let { Math.round(it).toInt() }
                json(Response.Status.OK, Lyrics.fetch(artist, title, q("album"), duration))
            }

            "/api/playing" -> {
                val (host, name) = Sonos.playing()
                json(Response.Status.OK, JSONObject()
                    .put("host", host ?: JSONObject.NULL)
                    .put("name", name ?: JSONObject.NULL))
            }

            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "Unknown endpoint"))
        }
    }

    // ---- Static assets ----------------------------------------------------

    private fun serveAsset(uri: String): Response {
        var path = uri.substringBefore('?').trimStart('/')
        if (path.isEmpty()) path = "index.html"
        val assetPath = "www/$path"
        return try {
            val stream = assets.open(assetPath)
            NanoHTTPD.newChunkedResponse(Response.Status.OK, mimeFor(path), stream)
        } catch (e: IOException) {
            NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found: $path")
        }
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun json(status: Response.Status, obj: JSONObject): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, "application/json", obj.toString())
        r.addHeader("Cache-Control", "no-store")
        return r
    }
}
