package com.vocalmonitor.plugin;

/**
 * A loaded ONNX inference session, handed to a plugin by
 * {@link PluginHost#loadModel(String)}.  The host owns the underlying
 * runtime (ONNX Runtime, NNAPI-accelerated where available) — the plugin
 * only ever sees this neutral interface, so plugin DEX never links against
 * any ML library directly.
 *
 * <p>Lifecycle: obtain one in {@code setHost} (or lazily on first use),
 * keep the reference, and call {@link #run} from {@code render()}.
 * Inference is synchronous and runs on the calling (frame) thread, so a
 * plugin that wraps a non-trivial model SHOULD throttle — e.g. run once
 * every N frames — rather than every 60 Hz frame.  The host closes all of
 * a plugin's sessions automatically when the plugin is unloaded, so
 * calling {@link #close} yourself is optional.
 *
 * <p>Designed for the common single-input / single-output float model.
 * Multi-tensor models aren't supported through this neutral surface.
 */
public interface InferenceSession {
    /**
     * Run the model on a single float input tensor.
     *
     * @param input      flattened input values, row-major, length must
     *                   equal the product of {@code inputShape}.
     * @param inputShape tensor dimensions, e.g. {@code {1, 256}} for a
     *                   batch-1 vector of 256.
     * @return the flattened output tensor, or {@code null} if inference
     *         failed (bad shape, runtime error).  Callers MUST null-check.
     */
    float[] run(float[] input, long[] inputShape);

    /** Release the session.  Optional — the host closes sessions on
     *  plugin unload.  Safe to call more than once. */
    void close();
}
