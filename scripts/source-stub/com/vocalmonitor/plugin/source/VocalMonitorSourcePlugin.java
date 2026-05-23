// Compile-time stub of the host's
// com.vocalmonitor.plugin.source.VocalMonitorSourcePlugin interface.
// At runtime the loaded .dex resolves this class through the host's
// own classloader (parent of DexClassLoader); this stub is purely so
// `javac` can typecheck source-plugin .java files.
//
// Keep in sync with app/src/main/java/com/vocalmonitor/plugin/source/
// VocalMonitorSourcePlugin.java in the vocal-monitor-slim app repo.
package com.vocalmonitor.plugin.source;

import java.util.List;

public interface VocalMonitorSourcePlugin {
    String id();
    String displayName();
    String version();
    void init(SourceHost host);
    List<SourceResult> search(String query, int limit, String token) throws Exception;
    void download(DownloadRequest request, String token) throws Exception;
    void cancel(String token);
    String resolveStreamUrl(String resultId) throws Exception;
    void shutdown();
}
