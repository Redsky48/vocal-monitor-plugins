package com.vocalmonitor.plugin;

// Compile-time-only stub of the interface the Vocal Monitor app provides
// at runtime via its parent classloader. We never ship this class — the
// .dex committed alongside each native plugin references the interface
// by name only, and the host resolves it through DexClassLoader when the
// plugin is installed.
public interface VocalMonitorNativePlugin {
    void init(int sampleRate);
    String[] parameterNames();
    float parameterMin(String name);
    float parameterMax(String name);
    float parameterDefault(String name);
    String parameterLabel(String name);
    void setParameter(String name, float value);
    void process(float[] input, float[] output);
}
