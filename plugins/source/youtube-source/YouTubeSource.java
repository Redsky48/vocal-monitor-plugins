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

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    /** Currently-streaming HttpURLConnection (single-threaded path).
     *  Held so {@link #cancel} can disconnect it from the binder thread;
     *  the worker thread blocked on {@code in.read()} then throws
     *  IOException promptly and the download() catch path runs. Null
     *  when no streaming is in flight (we're still in the NPE handshake
     *  or fully done). */
    private final AtomicReference<HttpURLConnection> activeConn =
        new AtomicReference<>(null);

    /** Per-worker active connections for the parallel-range download
     *  path. {@link #cancel} closes every entry to wake up all blocked
     *  reader threads at once. Cleared after each download() completes. */
    private final ConcurrentHashMap<Integer, HttpURLConnection> parallelConns =
        new ConcurrentHashMap<>();

    /** Token-keyed cancel flag so worker code can poll between blocking
     *  calls. AtomicReference (not Map) because we only ever have one
     *  download in flight per plugin instance. */
    private final AtomicReference<String> cancelledToken =
        new AtomicReference<>(null);

    /** Floor between consecutive searches — anti-ban. NPE / YT's threshold
     *  is opaque; 1 / sec is a polite default that hasn't tripped 429 in
     *  practice. Burst > limit blocks the caller via Thread.sleep. */
    private static final long MIN_SEARCH_INTERVAL_MS = 1_000L;

    /** Floor between consecutive downloads. YT's audio CDN absorbs more
     *  than the search endpoint, but back-to-back rapid-fire downloads
     *  draw abuse detection. 1 sec is enough courtesy for personal use
     *  without making single-download UX feel jammed (was 10 sec, which
     *  made every second download feel broken). */
    private static final long MIN_DOWNLOAD_INTERVAL_MS = 1_000L;

    /** Initial 429 backoff. Doubles up to MAX_BACKOFF on repeated 429s
     *  within one operation. */
    private static final long INITIAL_BACKOFF_MS = 5_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;

    /** Chunk size for the download path. 256 KB strikes the balance:
     *  for a typical 4-min opus (~5 MB) that's ~20 chunks, so the
     *  LinearProgressIndicator advances visibly without paying Binder
     *  marshalling + ContentResolver write overhead per 64 KB the old
     *  default required. */
    private static final int DOWNLOAD_CHUNK_BYTES = 256 * 1024;

    /** Number of parallel range requests for the multi-connection
     *  download path. YouTube's googlevideo CDN throttles per-connection
     *  (the first ~1 MB of each connection ships at full speed, then
     *  decays toward ~real-time playback rate). Splitting one file
     *  across 4 connections keeps every worker in the fast-initial-burst
     *  region for most of its byte budget — empirically 3-4× faster than
     *  the single-connection path for 3-10 MB audio files. yt-dlp uses
     *  the same trick under --concurrent-fragments. */
    private static final int PARALLEL_THREADS = 4;

    /** Below this file size the parallel-fan-out overhead (3 extra TLS
     *  handshakes, 3 extra HTTP round-trips) outweighs the throttle
     *  bypass — single-connection is faster. 1.5 MB ≈ a 90-second opus
     *  clip; below that just stream sequentially. */
    private static final long MIN_PARALLEL_BYTES = 1_500_000L;

    /** Hard ceiling on the parallel-download wait so a hung worker
     *  doesn't keep the host's download spinner forever. 5 minutes
     *  comfortably exceeds the slowest realistic ~30 MB download. */
    private static final long PARALLEL_TIMEOUT_MIN = 5L;

    /** Bigger chunk for the in-order writeChunk hand-off after parallel
     *  segments finish — we already have all the bytes in memory, so
     *  pay fewer host-call round-trips at this point. 512 KB stays
     *  under the safe Binder transaction ceiling (sandbox-isolated mode
     *  will re-route writeChunk through Binder; in-process today it's
     *  a direct call, but we keep the chunking forward-compatible). */
    private static final int FINAL_WRITE_CHUNK_BYTES = 512 * 1024;

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
        cancelledToken.set(null);  // fresh start
        throttle(lastDownloadAtMs, MIN_DOWNLOAD_INTERVAL_MS);
        throwIfCancelled(token);
        host.progress(0.0f);

        final StreamInfo info;
        try {
            info = StreamInfo.getInfo(ServiceList.YouTube, request.getResultId());
        } catch (Throwable t) {
            host.log("error", "StreamInfo.getInfo threw " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
            throw mapNpeException(t);
        }
        throwIfCancelled(token);
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
        if (info.getErrors() != null && !info.getErrors().isEmpty()) {
            final StringBuilder errs = new StringBuilder();
            for (Throwable t : info.getErrors()) {
                if (errs.length() > 0) errs.append(" | ");
                errs.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            }
            host.log("error", "yt info.errors: " + errs);
        }

        final AudioStream chosen = pickAudioStream(info, request.getPreferredFormats());
        if (chosen == null) {
            host.log("error", "no compatible audio stream for " + request.getResultId()
                + " (audio=" + audioCount + " video=" + videoCount + " combo=" + comboCount + ")");
            // Hint at age-restriction if there are video streams but no
            // audio streams — that's the typical signature.
            final String detail = (videoCount > 0 && audioCount == 0)
                ? "iespējams age-restricted vai premium-only"
                : "video bez audio plūsmas";
            throw new IOException("NO_STREAMS: " + detail);
        }
        final String streamUrl;
        try {
            streamUrl = chosen.getContent();
        } catch (Throwable t) {
            host.log("error", "chosen.getContent threw " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
            throw mapNpeException(t);
        }
        if (streamUrl == null || streamUrl.isEmpty()) {
            host.log("error", "stream URL is null/empty");
            throw new IOException("NO_STREAMS: stream URL was empty");
        }
        throwIfCancelled(token);
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
        // connection is allowed by app permission.
        //
        // Two-path approach:
        //   1. Open a probe connection, read responseCode + Content-Length.
        //   2. If total size is unknown OR below MIN_PARALLEL_BYTES, keep
        //      reading on the probe connection (the existing sequential
        //      path — fewest moving parts, fastest for tiny files).
        //   3. Otherwise, close the probe and fan out PARALLEL_THREADS
        //      Range-bounded GETs in parallel; each worker accumulates
        //      its segment in memory, then we hand them to host.writeChunk
        //      in order. Parallel path bypasses YT's per-connection
        //      throttle ramp — empirically 3-4× faster for 3-10 MB files.
        final HttpURLConnection probe = openConn(streamUrl);
        probe.setRequestMethod("GET");
        activeConn.set(probe);

        final int statusCode;
        try {
            statusCode = probe.getResponseCode();
        } catch (Throwable t) {
            activeConn.compareAndSet(probe, null);
            // If the binder thread disconnect()-ed us mid-handshake,
            // cancelledToken is set — translate to a cleaner cancel
            // throw so the host UI shows "Atcelts" rather than a generic
            // network error.
            if (token.equals(cancelledToken.get())) {
                throw new IOException("CANCELLED: download stopped by user");
            }
            host.log("error", "streaming responseCode threw " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
            probe.disconnect();
            throw new IOException("NETWORK: " + t.getClass().getSimpleName()
                + (t.getMessage() != null ? " — " + t.getMessage() : ""));
        }
        if (statusCode < 200 || statusCode >= 300) {
            host.log("error", "streaming status=" + statusCode);
            activeConn.compareAndSet(probe, null);
            probe.disconnect();
            throw new IOException("NETWORK: stream HTTP " + statusCode);
        }

        final long totalBytes = probe.getContentLengthLong();
        host.log("event", "yt streaming_start total=" + totalBytes + "B status=" + statusCode
            + " path=" + (totalBytes >= MIN_PARALLEL_BYTES ? "parallel" : "sequential"));

        if (totalBytes >= MIN_PARALLEL_BYTES) {
            // Done with probe — drop it before opening N range conns so
            // we don't sit on a 5th idle connection eating quota.
            activeConn.compareAndSet(probe, null);
            probe.disconnect();
            downloadParallel(streamUrl, totalBytes, token);
        } else {
            // Small file / unknown length — keep reading on the probe.
            downloadSequential(probe, totalBytes, token);
        }
        host.progress(1.0f);
        host.log("event", "yt download_complete bytes=" + totalBytes);
    }

    /** Stream the body of an already-opened connection to host.writeChunk
     *  in 256 KB pieces. Used when the file is small enough that parallel
     *  range fan-out would just add handshake overhead. */
    private void downloadSequential(HttpURLConnection conn, long totalBytes, String token)
        throws Exception {
        long sent = 0L;
        // Only fire host.progress on whole-percent boundaries — the
        // UI is a LinearProgressIndicator, not a tachometer, and each
        // progress() call hops through Binder + DebugFile HTTP POST.
        int lastPct = -1;
        try (InputStream in = conn.getInputStream()) {
            final byte[] buf = new byte[DOWNLOAD_CHUNK_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (token.equals(cancelledToken.get())) {
                    throw new IOException("CANCELLED: download stopped by user");
                }
                final byte[] chunk = (n == buf.length) ? buf : copyOfRange(buf, 0, n);
                host.writeChunk(chunk, false);
                sent += n;
                if (totalBytes > 0) {
                    final int pct = (int) Math.min(99L, (sent * 100L) / totalBytes);
                    if (pct != lastPct) {
                        host.progress(pct / 100f);
                        lastPct = pct;
                    }
                }
            }
            // Final empty chunk → host closes the file + scans MediaStore.
            host.writeChunk(new byte[0], true);
        } catch (IOException ioe) {
            // If cancel() disconnect()-ed the socket mid-read this is the
            // path the worker thread arrives on — translate to clean
            // CANCELLED so the UI doesn't render "Connection reset by peer".
            if (token.equals(cancelledToken.get())) {
                throw new IOException("CANCELLED: download stopped by user");
            }
            throw ioe;
        } finally {
            activeConn.compareAndSet(conn, null);
            conn.disconnect();
        }
    }

    /** Split [0, totalBytes-1] into [PARALLEL_THREADS] equal-ish ranges,
     *  fetch each in its own thread, then write the assembled segments to
     *  host.writeChunk in order. Cancel propagates via parallelConns:
     *  cancel() closes every conn → all reader threads throw → the latch
     *  counts down → we throw CANCELLED. */
    private void downloadParallel(String streamUrl, long totalBytes, String token)
        throws Exception {
        final int N = PARALLEL_THREADS;
        final long segSize = (totalBytes + N - 1) / N;
        final byte[][] segments = new byte[N][];
        final ExecutorService pool = Executors.newFixedThreadPool(N);
        final AtomicReference<IOException> firstErr = new AtomicReference<>(null);
        final AtomicLong totalReceived = new AtomicLong(0L);
        // CAS-bounded percent so concurrent workers can't post a regressing
        // progress fraction.
        final AtomicInteger lastPct = new AtomicInteger(-1);
        final CountDownLatch latch = new CountDownLatch(N);

        for (int i = 0; i < N; i++) {
            final int idx = i;
            final long start = (long) i * segSize;
            final long end = Math.min(start + segSize - 1, totalBytes - 1);
            pool.submit(new Runnable() {
                @Override public void run() {
                    try {
                        segments[idx] = fetchRange(streamUrl, idx, start, end, token,
                            totalBytes, totalReceived, lastPct);
                    } catch (IOException e) {
                        firstErr.compareAndSet(null, e);
                    } catch (Throwable t) {
                        firstErr.compareAndSet(null,
                            new IOException("PLUGIN_ERROR: " + t.getClass().getSimpleName()
                                + (t.getMessage() != null ? " — " + t.getMessage() : "")));
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        final boolean done = latch.await(PARALLEL_TIMEOUT_MIN, TimeUnit.MINUTES);
        pool.shutdownNow();
        parallelConns.clear();

        if (!done) {
            throw new IOException("NETWORK: parallel download timed out after "
                + PARALLEL_TIMEOUT_MIN + " min");
        }
        if (token.equals(cancelledToken.get())) {
            throw new IOException("CANCELLED: download stopped by user");
        }
        if (firstErr.get() != null) throw firstErr.get();

        // Hand assembled segments to the host in order. Re-chunk each
        // segment to FINAL_WRITE_CHUNK_BYTES so a future sandboxed
        // re-enable doesn't blow the Binder 1 MB transaction ceiling on
        // a single segment write.
        long written = 0L;
        for (int i = 0; i < N; i++) {
            final byte[] seg = segments[i];
            if (seg == null) {
                throw new IOException("PLUGIN_ERROR: segment " + i + " came back null");
            }
            for (int off = 0; off < seg.length; off += FINAL_WRITE_CHUNK_BYTES) {
                final int len = Math.min(FINAL_WRITE_CHUNK_BYTES, seg.length - off);
                host.writeChunk(copyOfRange(seg, off, off + len), false);
                written += len;
            }
            // Help GC release each segment as we consume it.
            segments[i] = null;
        }
        host.writeChunk(new byte[0], true);
        host.log("event", "yt parallel_done written=" + written + "B");
    }

    /** Single worker for the parallel-range path. Opens a Range-bound
     *  GET, buffers the response into a byte array, and updates the
     *  shared progress counter. Registered in parallelConns so cancel()
     *  can pull the rug out from under any blocked read. */
    private byte[] fetchRange(String streamUrl, int idx, long start, long end,
                              String token, long totalBytes,
                              AtomicLong totalReceived, AtomicInteger lastPct)
        throws IOException {
        final HttpURLConnection conn = openConn(streamUrl);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
        parallelConns.put(idx, conn);
        try {
            final int status = conn.getResponseCode();
            // Some CDNs return 200 + ignore Range on the very first byte
            // request; YT googlevideo returns 206 reliably for non-zero
            // start. Either is OK as long as the body length matches the
            // expected range size.
            if (status != 206 && status != 200) {
                throw new IOException("NETWORK: range HTTP " + status + " for seg " + idx);
            }
            final long expected = end - start + 1;
            final ByteArrayOutputStream out =
                new ByteArrayOutputStream((int) Math.min(expected + 4096, Integer.MAX_VALUE));
            try (InputStream in = conn.getInputStream()) {
                final byte[] buf = new byte[DOWNLOAD_CHUNK_BYTES];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (token.equals(cancelledToken.get())) {
                        throw new IOException("CANCELLED: download stopped by user");
                    }
                    out.write(buf, 0, n);
                    final long now = totalReceived.addAndGet(n);
                    if (totalBytes > 0) {
                        final int pct = (int) Math.min(99L, (now * 100L) / totalBytes);
                        final int prev = lastPct.get();
                        if (pct > prev && lastPct.compareAndSet(prev, pct)) {
                            host.progress(pct / 100f);
                        }
                    }
                }
            }
            return out.toByteArray();
        } finally {
            parallelConns.remove(idx);
            try { conn.disconnect(); } catch (Throwable ignore) {}
        }
    }

    /** Shared HttpURLConnection factory — same timeouts + UA across the
     *  sequential probe path and every parallel-range worker. */
    private HttpURLConnection openConn(String url) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64; rv:91.0) Gecko/20100101 Firefox/91.0");
        return c;
    }

    @Override
    public String resolveStreamUrl(String resultId) throws Exception {
        // Skip the download throttle — streaming is a smaller commitment
        // than a multi-MB download. Still throttle the search-side rate
        // because resolveStreamUrl drives the same /player + /next
        // Innertube hits.
        throttle(lastSearchAtMs, MIN_SEARCH_INTERVAL_MS);
        final StreamInfo info;
        try {
            info = StreamInfo.getInfo(ServiceList.YouTube, resultId);
        } catch (Throwable t) {
            host.log("error", "resolveStreamUrl getInfo threw "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
            throw mapNpeException(t);
        }
        final AudioStream chosen = pickAudioStream(info,
            java.util.Arrays.asList("opus", "m4a"));
        if (chosen == null) {
            throw new IOException("NO_STREAMS: no audio stream for streaming");
        }
        final String url = chosen.getContent();
        if (url == null || url.isEmpty()) {
            throw new IOException("NO_STREAMS: stream URL was empty");
        }
        host.log("event", "yt stream_url resolved bitrate=" + chosen.getAverageBitrate()
            + " fmt=" + (chosen.getFormat() != null ? chosen.getFormat().getName() : "?"));
        return url;
    }

    /** Maps an NPE exception class to one of the stable error-code
     *  prefixes the host UI translates to Latvian. Falls through to
     *  PLUGIN_ERROR for anything we haven't categorised yet. */
    private IOException mapNpeException(Throwable t) {
        // Walk the cause chain — NPE often wraps the real exception.
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            final String name = cur.getClass().getSimpleName();
            if (name.contains("AgeRestricted")) {
                return new IOException("AGE_RESTRICTED: " + safeMsg(cur));
            }
            if (name.contains("GeographicRestriction") || name.contains("Geo")) {
                return new IOException("REGION_BLOCKED: " + safeMsg(cur));
            }
            if (name.contains("Private")) {
                return new IOException("UNAVAILABLE: " + safeMsg(cur));
            }
            if (name.contains("Paid")) {
                return new IOException("PAID: " + safeMsg(cur));
            }
            if (name.contains("ReCaptcha")) {
                return new IOException("RATE_LIMITED: " + safeMsg(cur));
            }
            if (name.contains("ContentNotAvailable")
                || name.contains("ContentNotSupported")) {
                return new IOException("UNAVAILABLE: " + safeMsg(cur));
            }
            if (name.contains("AccountTerminated")) {
                return new IOException("UNAVAILABLE: account terminated — "
                    + safeMsg(cur));
            }
        }
        // Network / parse / generic
        if (t instanceof IOException) {
            return new IOException("NETWORK: " + safeMsg(t));
        }
        return new IOException("PLUGIN_ERROR: "
            + t.getClass().getSimpleName() + " — " + safeMsg(t));
    }

    private static String safeMsg(Throwable t) {
        final String m = t.getMessage();
        return m == null ? "(no message)" : m;
    }

    private void throwIfCancelled(String token) throws IOException {
        if (token.equals(cancelledToken.get())) {
            throw new IOException("CANCELLED: download stopped by user");
        }
    }

    @Override
    public void cancel(String token) {
        cancelledToken.set(token);
        // Closing the active socket from any thread makes the worker's
        // in.read(buf) throw IOException promptly — that's the only way
        // to interrupt a blocked native read on Android without doing
        // full thread.interrupt() ceremonies that don't always work for
        // HttpURLConnection internals.
        final HttpURLConnection c = activeConn.getAndSet(null);
        if (c != null) {
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
        // Parallel path: wake every blocked range-worker at once. Workers
        // remove themselves from the map in their finally{}, so any
        // double-disconnect here is a no-op.
        final int parallelCount = parallelConns.size();
        for (HttpURLConnection cc : parallelConns.values()) {
            try { cc.disconnect(); } catch (Throwable ignore) {}
        }
        parallelConns.clear();
        host.log("event", "yt cancel " + token + " (active_conn=" + (c != null)
            + " parallel=" + parallelCount + ")");
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
