package de.carstenkeller.videodownloader

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI

enum class StreamKind { HLS, DASH }

/**
 * A fully-resolved streaming video ready to download: an optional init segment (fragmented
 * MP4/CMAF streams prepend this before the media segments) followed by the ordered list of
 * media segment URLs. [audioInitUrl]/[audioSegmentUrls] are only set for DASH streams whose
 * video and audio live in separate representations - there is no muxing here (that needs a
 * real media container library this project doesn't have), so those get downloaded and saved
 * as two separate files rather than one silently video-only or corrupt file.
 */
data class StreamDownloadPlan(
    val kind: StreamKind,
    val videoInitUrl: String?,
    val videoSegmentUrls: List<String>,
    val audioInitUrl: String? = null,
    val audioSegmentUrls: List<String> = emptyList(),
    val outputExtension: String
)

/** Result of parsing one HLS playlist - either a master (only [variantUrl] set; the caller
 * must fetch and re-parse that URL) or an already-resolved media playlist. */
data class HlsParseResult(
    val variantUrl: String? = null,
    val initSegmentUrl: String? = null,
    val segmentUrls: List<String> = emptyList(),
    val encrypted: Boolean = false
)

/**
 * Pure parsing for HLS (.m3u8) and DASH (.mpd) manifests - no network I/O; callers (see
 * MainActivity) supply already-fetched text and get back a download plan or a reason it isn't
 * supported. Detection itself happens by sniffing network requests for these extensions:
 * Android WebView's Chromium engine has no native HLS/DASH playback, so sites use a JS player
 * (hls.js, shaka-player, dash.js) that loads the manifest via fetch()/XHR into a MediaSource -
 * it essentially never appears as a plain <video src> the DOM scanner could see.
 */
object StreamManifest {

    private fun resolve(base: String, ref: String): String =
        try { URI(base).resolve(ref).toString() } catch (e: Exception) { ref }

    // --- HLS -----------------------------------------------------------------------------

    fun parseHls(manifestText: String, manifestUrl: String): HlsParseResult {
        val lines = manifestText.lines()
        if (manifestText.contains("#EXT-X-STREAM-INF")) {
            var bestBandwidth = -1L
            var bestUri: String? = null
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val uriLine = lines.getOrNull(i + 1)?.trim()
                    if (!uriLine.isNullOrBlank() && !uriLine.startsWith("#") && bandwidth > bestBandwidth) {
                        bestBandwidth = bandwidth
                        bestUri = resolve(manifestUrl, uriLine)
                    }
                }
            }
            return HlsParseResult(variantUrl = bestUri)
        }

        var encrypted = false
        var initUrl: String? = null
        val segments = mutableListOf<String>()
        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE") -> encrypted = true
                line.startsWith("#EXT-X-MAP") ->
                    Regex("URI=\"([^\"]+)\"").find(line)?.let { initUrl = resolve(manifestUrl, it.groupValues[1]) }
                line.isNotBlank() && !line.startsWith("#") -> segments.add(resolve(manifestUrl, line))
            }
        }
        return HlsParseResult(initSegmentUrl = initUrl, segmentUrls = segments, encrypted = encrypted)
    }

    // --- DASH ------------------------------------------------------------------------------

    sealed class DashResult {
        data class Plan(val plan: StreamDownloadPlan) : DashResult()
        data class Unsupported(val reason: String) : DashResult()
    }

    private data class SegTemplate(
        val media: String,
        val initialization: String?,
        val timescale: Long,
        val startNumber: Long,
        val duration: Long?,
        val timeline: MutableList<Pair<Long, Long>> = mutableListOf() // (segment duration, repeat count "r")
    )

    private data class Representation(
        val bandwidth: Long,
        val mimeType: String?,
        val segTemplate: SegTemplate?,
        val segList: List<String>,
        val initUrl: String?
    )

    private fun parseIsoDurationSeconds(s: String): Double? {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""").find(s) ?: return null
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return h * 3600 + min * 60 + sec
    }

    /**
     * Handles the two most common segment addressing modes - SegmentTemplate (with either a
     * SegmentTimeline or a fixed duration) and explicit SegmentList - across separate
     * video/audio AdaptationSets (the usual case; highest-bandwidth Representation picked from
     * each) or a single combined one. Byte-range-addressed segments (SegmentBase/indexRange)
     * are a third real-world mode this does not handle - reported as unsupported rather than
     * guessed at, since getting byte-range fetching wrong produces silently corrupt output.
     */
    fun parseDash(manifestText: String, manifestUrl: String): DashResult {
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(manifestText))

            var mediaPresentationDurationSec: Double? = null
            val videoReps = mutableListOf<Representation>()
            val audioReps = mutableListOf<Representation>()

            var adaptationMime: String? = null
            var adaptationSegTemplate: SegTemplate? = null
            var insideRepresentation = false
            var repBandwidth = -1L
            var repMime: String? = null
            var repInit: String? = null
            var repSegList = mutableListOf<String>()
            var repSegTemplate: SegTemplate? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "MPD" -> parser.getAttributeValue(null, "mediaPresentationDuration")?.let {
                            mediaPresentationDurationSec = parseIsoDurationSeconds(it)
                        }
                        "AdaptationSet" -> {
                            adaptationMime = parser.getAttributeValue(null, "mimeType")
                            adaptationSegTemplate = null
                        }
                        "Representation" -> {
                            insideRepresentation = true
                            repBandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                            repMime = parser.getAttributeValue(null, "mimeType") ?: adaptationMime
                            repInit = null
                            repSegList = mutableListOf()
                            repSegTemplate = adaptationSegTemplate
                        }
                        "SegmentTemplate" -> {
                            val media = parser.getAttributeValue(null, "media")
                            if (media != null) {
                                val tmpl = SegTemplate(
                                    media = media,
                                    initialization = parser.getAttributeValue(null, "initialization"),
                                    timescale = parser.getAttributeValue(null, "timescale")?.toLongOrNull() ?: 1L,
                                    startNumber = parser.getAttributeValue(null, "startNumber")?.toLongOrNull() ?: 1L,
                                    duration = parser.getAttributeValue(null, "duration")?.toLongOrNull()
                                )
                                if (insideRepresentation) repSegTemplate = tmpl else adaptationSegTemplate = tmpl
                            }
                        }
                        "S" -> {
                            val d = parser.getAttributeValue(null, "d")?.toLongOrNull()
                            val r = parser.getAttributeValue(null, "r")?.toLongOrNull() ?: 0L
                            if (d != null) repSegTemplate?.timeline?.add(d to r)
                        }
                        "SegmentURL" ->
                            parser.getAttributeValue(null, "media")?.let { repSegList.add(resolve(manifestUrl, it)) }
                        "Initialization" ->
                            parser.getAttributeValue(null, "sourceURL")?.let { repInit = resolve(manifestUrl, it) }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "Representation" -> {
                            insideRepresentation = false
                            val rep = Representation(repBandwidth, repMime, repSegTemplate, repSegList, repInit)
                            if (repMime?.startsWith("audio") == true) audioReps.add(rep) else videoReps.add(rep)
                        }
                    }
                }
                event = parser.next()
            }

            fun expandTemplate(tmpl: SegTemplate, repInitUrl: String?): Pair<String?, List<String>> {
                val init = tmpl.initialization?.let { resolve(manifestUrl, it) } ?: repInitUrl
                val urls = mutableListOf<String>()
                if (tmpl.timeline.isNotEmpty()) {
                    var number = tmpl.startNumber
                    for ((_, r) in tmpl.timeline) {
                        repeat((1 + r).toInt()) {
                            urls.add(resolve(manifestUrl, tmpl.media.replace("\$Number\$", number.toString())))
                            number++
                        }
                    }
                } else if (tmpl.duration != null && mediaPresentationDurationSec != null) {
                    val segDurationSec = tmpl.duration.toDouble() / tmpl.timescale
                    val count = Math.ceil(mediaPresentationDurationSec / segDurationSec).toLong()
                    for (n in tmpl.startNumber until tmpl.startNumber + count) {
                        urls.add(resolve(manifestUrl, tmpl.media.replace("\$Number\$", n.toString())))
                    }
                }
                return init to urls
            }

            fun bestOf(reps: List<Representation>): Representation? = reps.maxByOrNull { it.bandwidth }

            val bestVideo = bestOf(videoReps) ?: return DashResult.Unsupported("Keine Video-Repräsentation im Manifest gefunden")
            val (videoInit, videoSegs) = when {
                bestVideo.segTemplate != null -> expandTemplate(bestVideo.segTemplate, bestVideo.initUrl)
                bestVideo.segList.isNotEmpty() -> bestVideo.initUrl to bestVideo.segList
                else -> return DashResult.Unsupported("Nicht unterstütztes Segment-Format (evtl. byte-range-basiert)")
            }
            if (videoSegs.isEmpty()) return DashResult.Unsupported("Keine Segmente im Manifest gefunden")

            val bestAudio = bestOf(audioReps)
            var audioInit: String? = null
            var audioSegs: List<String> = emptyList()
            if (bestAudio != null) {
                val (ai, aSegs) = when {
                    bestAudio.segTemplate != null -> expandTemplate(bestAudio.segTemplate, bestAudio.initUrl)
                    bestAudio.segList.isNotEmpty() -> bestAudio.initUrl to bestAudio.segList
                    else -> null to emptyList()
                }
                audioInit = ai
                audioSegs = aSegs
            }

            DashResult.Plan(
                StreamDownloadPlan(
                    kind = StreamKind.DASH,
                    videoInitUrl = videoInit,
                    videoSegmentUrls = videoSegs,
                    audioInitUrl = audioInit,
                    audioSegmentUrls = audioSegs,
                    outputExtension = "mp4"
                )
            )
        } catch (e: Exception) {
            DashResult.Unsupported(e.message ?: "Manifest konnte nicht geparst werden")
        }
    }
}
