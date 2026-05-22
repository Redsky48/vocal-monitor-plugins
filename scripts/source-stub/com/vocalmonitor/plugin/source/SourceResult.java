// Compile-time stub matching the Kotlin @Parcelize data class in
// the host app (app/src/main/kotlin/com/vocalmonitor/plugin/source/
// SourceParcelables.kt). Kotlin's data-class constructor + auto-getters
// match these signatures byte-for-byte on the JVM, so .dex code that
// compiles against this stub binds correctly to the runtime class.
package com.vocalmonitor.plugin.source;

import java.util.Map;

public final class SourceResult {

    private final String id;
    private final String title;
    private final String artist;
    private final Long durationMs;
    private final String thumbnailUrl;
    private final String sourceId;
    private final String attribution;
    private final Map<String, String> extras;

    public SourceResult(String id, String title, String artist, Long durationMs,
                        String thumbnailUrl, String sourceId, String attribution,
                        Map<String, String> extras) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
        this.thumbnailUrl = thumbnailUrl;
        this.sourceId = sourceId;
        this.attribution = attribution;
        this.extras = extras;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public Long getDurationMs() { return durationMs; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public String getSourceId() { return sourceId; }
    public String getAttribution() { return attribution; }
    public Map<String, String> getExtras() { return extras; }
}
