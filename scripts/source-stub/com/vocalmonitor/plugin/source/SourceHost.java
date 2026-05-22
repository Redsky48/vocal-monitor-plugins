// Compile-time stub — see VocalMonitorSourcePlugin.java for context.
package com.vocalmonitor.plugin.source;

import java.util.Map;

public interface SourceHost {
    byte[] fetch(String url, Map<String, String> headers, int timeoutMs) throws Exception;
    void writeChunk(byte[] bytes, boolean last) throws Exception;
    void progress(float fraction);
    void log(String level, String message);
    void requestUpdateCheck();
}
