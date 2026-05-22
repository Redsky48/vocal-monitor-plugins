// Compile-time stub — runtime impl lives in the host app's Kotlin code.
package com.vocalmonitor.plugin.source;

import java.util.List;

public final class DownloadRequest {

    private final String resultId;
    private final String sourceId;
    private final String title;
    private final List<String> preferredFormats;
    private final Integer maxBitrateKbps;
    private final boolean embedThumbnail;
    private final boolean embedTags;

    public DownloadRequest(String resultId, String sourceId, String title,
                           List<String> preferredFormats, Integer maxBitrateKbps,
                           boolean embedThumbnail, boolean embedTags) {
        this.resultId = resultId;
        this.sourceId = sourceId;
        this.title = title;
        this.preferredFormats = preferredFormats;
        this.maxBitrateKbps = maxBitrateKbps;
        this.embedThumbnail = embedThumbnail;
        this.embedTags = embedTags;
    }

    public String getResultId() { return resultId; }
    public String getSourceId() { return sourceId; }
    public String getTitle() { return title; }
    public List<String> getPreferredFormats() { return preferredFormats; }
    public Integer getMaxBitrateKbps() { return maxBitrateKbps; }
    public boolean getEmbedThumbnail() { return embedThumbnail; }
    public boolean getEmbedTags() { return embedTags; }
}
