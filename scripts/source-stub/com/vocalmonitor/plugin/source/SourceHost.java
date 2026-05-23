// Compile-time stub — see VocalMonitorSourcePlugin.java for context.
package com.vocalmonitor.plugin.source;

import java.util.Map;

public interface SourceHost {
    // method = "GET" / "POST" / etc. (uppercase). body null/empty for
    // requests without a body. Non-2xx throws IOException from host.
    byte[] fetch(String method, String url, Map<String, String> headers,
                 byte[] body, int timeoutMs) throws Exception;
    void writeChunk(byte[] bytes, boolean last) throws Exception;
    void progress(float fraction);
    void log(String level, String message);
    void requestUpdateCheck();
}
