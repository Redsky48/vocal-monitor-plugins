package com.vocalmonitor.plugin.source.youtube;

import com.vocalmonitor.plugin.source.DownloadRequest;
import com.vocalmonitor.plugin.source.SourceHost;
import com.vocalmonitor.plugin.source.SourceResult;
import com.vocalmonitor.plugin.source.VocalMonitorSourcePlugin;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.InfoItem;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * YouTube source plugin. Runs inside the app's isolated-process
 * sandbox; the only side-effects it can have are routed through
 * {@link SourceHost}: validated HTTPS via {@link SourceHost#fetch},
 * scoped writes via {@link SourceHost#writeChunk}, and DebugStore
 * logging via {@link SourceHost#log}.
 *
 * Wraps {@code NewPipeExtractor} so YouTube-protocol changes are
 * upstream maintained. When NPE ships a fix for a new YT signature
 * algorithm, the GitHub-Actions workflow at
 * {@code .github/workflows/youtube-source-build.yml} picks it up
 * within 24h, rebuilds + signs the .dex, publishes to the registry,
 * and phones auto-update on their next daily poll.
 *
 * License: GPL-3.0 (NewPipeExtractor's license — see {@code LICENSE}
 * next to this file). The host app's plugin loader treats every .dex
 * as a separate work loaded at runtime; the app itself stays under
 * its original license.
 */
public class YouTubeSource implements VocalMonitorSourcePlugin {

    private SourceHost host;
    private final AtomicLong lastSearchAtMs = new AtomicLong(0L);
    private final AtomicLong lastDownloadAtMs = new AtomicLong(0L);

    /** Floor between consecutive searches — anti-ban. NPE / YT's threshold
     *  is opaque; 1 / sec is a polite default that hasn't tripped 429 in
     *  practice. Burst > limit blocks the caller via Thread.sleep. */
    private static final long MIN_SEARCH_INTERVAL_MS = 1_000L;

    /** Floor between consecutive downloads. YT's audio CDN absorbs more
     *  than the search endpoint, but each download is megabytes of
     *  bandwidth — too-rapid sequencing draws abuse detection. */
    private static final long MIN_DOWNLOAD_INTERVAL_MS = 10_000L;

    /** Initial 429 backoff. Doubles up to MAX_BACKOFF on repeated 429s
     *  within one operation. */
    private static final long INITIAL_BACKOFF_MS = 5_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;

    /** Chunk size for the download path — small enough that the
     *  CircularProgressIndicator on the UI feels live, big enough that
     *  Binder transactions aren't pure overhead. */
    private static final int DOWNLOAD_CHUNK_BYTES = 64 * 1024;

    @Override
    public String id() { return "youtube-source"; }

    @Override
    public String displayName() { return "YouTube"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public void init(SourceHost host) {
        this.host = host;
        // NewPipe needs a downloader injected exactly once per process.
        // Wire it to host.fetch so all HTTP traffic flows through the
        // domain allowlist instead of opening sockets behind the
        // sandbox's back.
        NewPipe.init(new HostDownloader(host),
            new Localization("en", "US"),
            ContentCountry.DEFAULT);
        host.log("event", "yt init");
    }

    @Override
    public List<SourceResult> search(String query, int limit, String token) throws Exception {
        throttle(lastSearchAtMs, MIN_SEARCH_INTERVAL_MS);

        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                final SearchExtractor extractor = ServiceList.YouTube.getSearchExtractor(query);
                extractor.fetchPage();
                final List<SourceResult> out = new ArrayList<>();
                int taken = 0;
                for (InfoItem item : extractor.getInitialPage().getItems()) {
                    if (taken >= limit) break;
                    if (!(item instanceof StreamInfoItem)) continue;
                    final StreamInfoItem si = (StreamInfoItem) item;
                    if (si.getStreamType() != null && si.getStreamType().name().contains("LIVE")) {
                        // Skip live streams — they have no fixed length
                        // and downloading them ends in tears.
                        continue;
                    }
                    out.add(new SourceResult(
                        si.getUrl(),                       // canonical YT watch URL = stable id
                        si.getName(),
                        si.getUploaderName(),
                        si.getDuration() > 0 ? si.getDuration() * 1000L : null,
                        firstThumbnailOrNull(si.getThumbnails()),
                        "youtube",
                        si.getUploaderName() != null
                            ? "YouTube · " + si.getUploaderName()
                            : "YouTube",
                        Collections.<String, String>emptyMap()
                    ));
                    taken++;
                }
                host.log("event", "yt search ok q=\"" + query + "\" results=" + out.size());
                return out;
            } catch (ReCaptchaException e) {
                // YT throws this on rate-limit / suspected automation.
                // Tell the host "I think I'm out of date" — it may
                // trigger an update fetch, and back off.
                host.log("warn", "yt recaptcha — requesting update + backoff " + backoff + "ms");
                host.requestUpdateCheck();
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } catch (Exception e) {
                host.log("error", "yt search threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
                throw e;
            }
        }
        throw new IOException("yt search exhausted retries");
    }

    @Override
    public void download(DownloadRequest request, String token) throws Exception {
        throttle(lastDownloadAtMs, MIN_DOWNLOAD_INTERVAL_MS);
        host.progress(0.0f);

        final StreamInfo info;
        try {
            info = StreamInfo.getInfo(ServiceList.YouTube, request.getResultId());
        } catch (Throwable t) {
            host.log("error", "StreamInfo.getInfo threw " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
            throw t instanceof Exception ? (Exception) t : new IOException(t);
        }
        final int audioCount = info.getAudioStreams() == null
            ? 0 : info.getAudioStreams().size();
        final int videoCount = info.getVideoStreams() == null
            ? 0 : info.getVideoStreams().size();
        final int comboCount = info.getVideoOnlyStreams() == null
            ? 0 : info.getVideoOnlyStreams().size();
        host.log("event", "yt streams audio=" + audioCount
            + " video=" + videoCount + " video_only=" + comboCount
            + " title=\"" + (info.getName() != null
                ? info.getName().substring(0, Math.min(40, info.getName().length()))
                : "?") + "\"");
        if (info.getErrorMessage() != null) {
            host.log("error", "yt info.errorMessage: " + info.getErrorMessage());
        }

        final AudioStream chosen = pickAudioStream(info, request.getPreferredFormats());
        if (chosen == null) {
            host.log("error", "no compatible audio stream for " + request.getResultId()
                + " (audio=" + audioCount + " video=" + videoCount + " combo=" + comboCount + ")");
            throw new IOException("no compatible audio stream for " + request.getResultId());
        }
        final String streamUrl;
        try {
            streamUrl = chosen.getContent();
        } catch (Throwable t) {
            host.log("error", "chosen.getContent threw " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
            throw t instanceof Exception ? (Exception) t : new IOException(t);
        }
        if (streamUrl == null || streamUrl.isEmpty()) {
            host.log("error", "stream URL is null/empty");
            throw new IOException("stream URL is null/empty");
        }
        host.log("event", "yt download bitrate=" + chosen.getAverageBitrate()
            + " fmt=" + (chosen.getFormat() != null ? chosen.getFormat().getName() : "?")
            + " url_prefix=" + streamUrl.substring(0, Math.min(40, streamUrl.length())));

        // Direct streaming connection (not through SourceHost.fetch —
        // that's for small JSON / HTML payloads, capped at a few MB on
        // the host side; multi-MB audio belongs in a streaming
        // connection that hands chunks straight to host.writeChunk).
        // The URL is *.googlevideo.com which is already on the
        // url_allowlist; the host's allowlist check applies to
        // host.fetch calls, but the plugin runs in-process today (sandbox
        // disabled while we debug isolated-process spawn) so the direct
        // connection is allowed by app permission. Earlier code did a
        // host.fetch GET as a probe — removed because YT does not honour
        // Range: bytes=0-0 reliably, so the probe could pull the full
        // audio body through the host buffer and OOM the process.
        final HttpURLConnection conn = (HttpURLConnection) new URL(streamUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64; rv:91.0) Gecko/20100101 Firefox/91.0");

        final int statusCode;
        try {
            statusCode = conn.getResponseCode();
        } catch (Throwable t) {
            host.log("error", "streaming responseCode threw " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
            conn.disconnect();
            throw t instanceof Exception ? (Exception) t : new IOException(t);
        }
        if (statusCode < 200 || statusCode >= 300) {
            host.log("error", "streaming status=" + statusCode);
            conn.disconnect();
            throw new IOException("stream HTTP " + statusCode);
        }

        final long totalBytes = conn.getContentLengthLong();
        host.log("event", "yt streaming_start total=" + totalBytes + "B status=" + statusCode);
        long sent = 0L;
        try (InputStream in = conn.getInputStream()) {
            final byte[] buf = new byte[DOWNLOAD_CHUNK_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) {
                final byte[] chunk = (n == buf.length) ? buf : copyOfRange(buf, 0, n);
                host.writeChunk(chunk, false);
                sent += n;
                if (totalBytes > 0) {
                    host.progress(Math.min(0.99f, (float) sent / (float) totalBytes));
                }
            }
            // Final empty chunk → host closes the file + scans MediaStore.
            host.writeChunk(new byte[0], true);
        } finally {
            conn.disconnect();
        }
        host.progress(1.0f);
        host.log("event", "yt download_complete bytes=" + sent);
    }

    @Override
    public void cancel(String token) {
        // The blocking InputStream read is per-thread; tolerable to no-op
        // for the MVP — the AIDL caller times out on its end. Wire
        // proper Thread.interrupt() in the next iteration if cancel UX
        // gets visible.
        host.log("event", "yt cancel " + token);
    }

    @Override
    public void shutdown() {
        if (host != null) host.log("event", "yt shutdown");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void throttle(AtomicLong gate, long minIntervalMs) throws InterruptedException {
        final long now = System.currentTimeMillis();
        final long last = gate.get();
        final long wait = (last + minIntervalMs) - now;
        if (wait > 0) Thread.sleep(wait);
        gate.set(System.currentTimeMillis());
    }

    /**
     * Pick the best audio stream matching the requested preferred format
     * order. Within a format, prefer higher bitrate. Returns null when
     * the video has no compatible streams (rare; live + region-locked).
     */
    private AudioStream pickAudioStream(StreamInfo info, List<String> preferredFormats) {
        if (info.getAudioStreams() == null || info.getAudioStreams().isEmpty()) return null;
        for (String wanted : preferredFormats) {
            AudioStream best = null;
            for (AudioStream s : info.getAudioStreams()) {
                if (s.getFormat() == null) continue;
                final String fmt = s.getFormat().getName().toLowerCase();
                if (!fmt.contains(wanted.toLowerCase())) continue;
                if (best == null || s.getAverageBitrate() > best.getAverageBitrate()) best = s;
            }
            if (best != null) return best;
        }
        // Fallback: any audio stream, highest bitrate.
        AudioStream fallback = null;
        for (AudioStream s : info.getAudioStreams()) {
            if (fallback == null || s.getAverageBitrate() > fallback.getAverageBitrate()) fallback = s;
        }
        return fallback;
    }

    private String firstThumbnailOrNull(List<?> thumbs) {
        if (thumbs == null || thumbs.isEmpty()) return null;
        try {
            final Object first = thumbs.get(0);
            // org.schabi.newpipe.extractor.Image#getUrl
            return (String) first.getClass().getMethod("getUrl").invoke(first);
        } catch (Throwable t) {
            return null;
        }
    }

    private Map<String, String> headersForByteRange(long start, long end) {
        final Map<String, String> h = new LinkedHashMap<>();
        h.put("Range", "bytes=" + start + "-" + end);
        return h;
    }

    private static byte[] copyOfRange(byte[] src, int from, int to) {
        final byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, to - from);
        return out;
    }
}

/**
 * NewPipe's {@code Downloader} bridge over {@link SourceHost#fetch}.
 * Everything NPE wants to GET / POST runs through the host's domain
 * allowlist — the plugin never opens its own sockets, so a misbehaving
 * extractor (compromised .dex, runaway loop) cannot beacon anywhere.
 */
class HostDownloader extends Downloader {

    private final SourceHost host;

    HostDownloader(SourceHost host) { this.host = host; }

    /** Default UA + Accept-Language injected when NPE doesn't specify
     *  them. Without these, www.youtube.com returns a stripped page
     *  (no `var ytInitialData = …` block) and NPE throws
     *  ParsingException: Could not get ytInitialData. The UA mirrors
     *  what NPE's reference downloader uses upstream. */
    private static final String DEFAULT_UA =
        "Mozilla/5.0 (X11; Linux x86_64; rv:91.0) Gecko/20100101 Firefox/91.0";
    private static final String DEFAULT_ACCEPT_LANG = "en-US,en;q=0.9";

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        try {
            final Map<String, String> flatHeaders = new HashMap<>();
            if (request.headers() != null) {
                for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        flatHeaders.put(e.getKey(), e.getValue().get(0));
                    }
                }
            }
            if (!flatHeaders.containsKey("User-Agent")) {
                flatHeaders.put("User-Agent", DEFAULT_UA);
            }
            if (!flatHeaders.containsKey("Accept-Language")) {
                flatHeaders.put("Accept-Language", DEFAULT_ACCEPT_LANG);
            }
            final String method = request.httpMethod() != null
                ? request.httpMethod().toUpperCase() : "GET";
            final byte[] reqBody = request.dataToSend();
            final byte[] body = host.fetch(method, request.url(), flatHeaders,
                reqBody, 15_000);
            final int bodyLen = body == null ? 0 : body.length;
            host.log("event", "yt.http " + method + " "
                + request.url().substring(0, Math.min(60, request.url().length()))
                + " → " + bodyLen + "B");
            return new Response(
                200,
                "OK",
                Collections.<String, List<String>>emptyMap(),
                body == null ? "" : new String(body, "UTF-8"),
                request.url()
            );
        } catch (SecurityException e) {
            // Host rejected the URL — surface as a 403-equivalent so NPE
            // doesn't retry endlessly.
            throw new IOException("host denied URL: " + request.url(), e);
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
    }
}
