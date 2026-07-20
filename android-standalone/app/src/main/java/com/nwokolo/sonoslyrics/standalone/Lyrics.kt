package com.nwokolo.sonoslyrics.standalone

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Native port of the Node backend's lyrics logic: lrclib.net (synced + plain)
 * with a lyrics.ovh (plain) fallback, plus a per-song in-memory cache.
 */
object Lyrics {
    private const val FETCH_MS = 12000
    private const val TTL_MS = 60L * 60L * 1000L
    private const val UA = "sonos-lyrics (local home app)"

    private data class Result(val synced: JSONArray?, val plain: String?, val source: String?)

    private val cache = HashMap<String, Pair<Long, Result>>()

    /** Returns the JSON body for /api/lyrics: { synced, plain, source, notFound }. */
    @Synchronized
    fun fetch(artist: String, title: String, album: String?, duration: Int?): JSONObject {
        val key = listOf(artist, title, album ?: "", duration?.toString() ?: "")
            .joinToString("|").lowercase()

        val now = System.currentTimeMillis()
        val cached = cache[key]
        val result = if (cached != null && now - cached.first < TTL_MS &&
            (cached.second.synced != null || cached.second.plain != null)
        ) {
            cached.second
        } else {
            fetchRemote(artist, title, album, duration).also {
                if (it.synced != null || it.plain != null) cache[key] = now to it
            }
        }

        return JSONObject().apply {
            put("synced", result.synced ?: JSONObject.NULL)
            put("plain", result.plain ?: JSONObject.NULL)
            put("source", result.source ?: JSONObject.NULL)
            put("notFound", result.synced == null && result.plain == null)
        }
    }

    private fun fetchRemote(artist: String, title: String, album: String?, duration: Int?): Result {
        // 1) lrclib.net exact get (best when duration is known)
        try {
            val url = StringBuilder("https://lrclib.net/api/get?artist_name=")
                .append(enc(artist)).append("&track_name=").append(enc(title))
            if (!album.isNullOrEmpty()) url.append("&album_name=").append(enc(album))
            if (duration != null) url.append("&duration=").append(duration)
            val body = Sonos.httpGet(url.toString(), FETCH_MS, mapOf("User-Agent" to UA))
            if (body != null) {
                val d = JSONObject(body)
                val synced = parseLrc(jstr(d, "syncedLyrics"))
                val plain = jstr(d, "plainLyrics")?.trim()?.ifEmpty { null }
                if (synced != null || plain != null) return Result(synced, plain, "lrclib")
            }
        } catch (_: Exception) { /* try search */ }

        // 2) lrclib.net search fallback
        try {
            val url = "https://lrclib.net/api/search?track_name=" + enc(title) +
                "&artist_name=" + enc(artist)
            val body = Sonos.httpGet(url, FETCH_MS, mapOf("User-Agent" to UA))
            if (body != null) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val x = arr.optJSONObject(i) ?: continue
                    val sRaw = jstr(x, "syncedLyrics")
                    val pRaw = jstr(x, "plainLyrics")
                    if (sRaw != null || pRaw != null) {
                        val synced = parseLrc(sRaw)
                        val plain = pRaw?.trim()?.ifEmpty { null }
                        if (synced != null || plain != null) return Result(synced, plain, "lrclib")
                    }
                }
            }
        } catch (_: Exception) { /* try lyrics.ovh */ }

        // 3) lyrics.ovh plain fallback
        try {
            val url = "https://api.lyrics.ovh/v1/" + enc(artist) + "/" + enc(title)
            val body = Sonos.httpGet(url, FETCH_MS)
            if (body != null) {
                val d = JSONObject(body)
                val plain = jstr(d, "lyrics")?.replace("\r\n", "\n")?.trim()?.ifEmpty { null }
                if (plain != null) return Result(null, plain, "lyrics.ovh")
            }
        } catch (_: Exception) { /* give up */ }

        return Result(null, null, null)
    }

    /** Parse an LRC string into a sorted JSON array of { time, text }. */
    private fun parseLrc(lrc: String?): JSONArray? {
        if (lrc.isNullOrEmpty()) return null
        val tagRe = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        val out = ArrayList<Pair<Double, String>>()
        for (raw in lrc.replace("\r\n", "\n").split("\n")) {
            val stamps = ArrayList<Double>()
            for (m in tagRe.findAll(raw)) {
                val min = m.groupValues[1].toInt()
                val sec = m.groupValues[2].toInt()
                val fracStr = m.groupValues[3]
                val frac = if (fracStr.isNotEmpty()) fracStr.padEnd(3, '0').toInt() / 1000.0 else 0.0
                stamps.add(min * 60 + sec + frac)
            }
            if (stamps.isEmpty()) continue
            val text = raw.replace(tagRe, "").trim()
            for (t in stamps) out.add(t to text)
        }
        if (out.isEmpty()) return null
        out.sortBy { it.first }
        val arr = JSONArray()
        for ((t, text) in out) arr.put(JSONObject().put("time", t).put("text", text))
        return arr
    }

    /** String value or null (treats JSON null / missing / empty as null). */
    private fun jstr(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.optString(key, "")
        return v.ifEmpty { null }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
