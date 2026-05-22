// Compile-time stub — runtime impl lives in the host app's Kotlin code.
package com.vocalmonitor.plugin.source;

public final class DownloadMetadata {

    private final String title;
    private final String artist;
    private final String album;
    private final String format;
    private final Integer bitrateKbps;
    private final Long totalBytes;
    private final byte[] thumbnailBytes;

    public DownloadMetadata(String title, String artist, String album, String format,
                            Integer bitrateKbps, Long totalBytes, byte[] thumbnailBytes) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.format = format;
        this.bitrateKbps = bitrateKbps;
        this.totalBytes = totalBytes;
        this.thumbnailBytes = thumbnailBytes;
    }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public String getFormat() { return format; }
    public Integer getBitrateKbps() { return bitrateKbps; }
    public Long getTotalBytes() { return totalBytes; }
    public byte[] getThumbnailBytes() { return thumbnailBytes; }
}
