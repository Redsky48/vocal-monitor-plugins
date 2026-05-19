package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;
import com.vocalmonitor.plugin.gamekit.svg.PluginShape;
import com.vocalmonitor.plugin.gamekit.svg.Svg;

import java.util.Map;

/**
 * SVG Demo — proves the {@code PluginHost.loadAssetText} → {@code
 * Svg.parse} → {@code PluginShape.draw} pipeline works end-to-end.
 *
 * Loads {@code assets/bird.svg} once on first render (host wraps a
 * folder reader on DAW, an HTTP/cache reader on slim).  Renders the
 * shape centred on the canvas, scaled to fit, with a gentle bob +
 * scale-bump driven by the mic level.
 *
 * Falls back to a placeholder if the host doesn't ship assets — so
 * the plugin still loads on older hosts.
 */
public final class SvgDemo extends GamePluginBase {

    private PluginShape bird = null;
    private boolean attempted = false;

    @Override
    public void render(PluginCanvas c, int width, int height, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        beginFrame(width, height, timeMs, streams);

        // Lazy-load the asset on the first frame we actually have a
        // canvas + host — setHost has been called by then.
        if (!attempted) {
            attempted = true;
            if (host != null) {
                String svg = host.loadAssetText("bird.svg");
                if (svg != null) bird = Svg.parse(svg);
            }
        }

        Gfx.gradientSky(c, width, height, Palette.SKY_DAY_TOP, Palette.SKY_DAY_BOT);

        if (bird == null || bird.isEmpty()) {
            // No asset path on this host — show a friendly placeholder.
            Gfx.textCenter(c, "loadAssetText not supported on this host",
                width / 2f, height / 2f, 14f * scale, Palette.UI_TEXT_INK);
            return;
        }

        // Bob + breathe based on mic level so it's obviously live.
        float level = mic.level();
        float bobY = (float) Math.sin(timeMs * 0.004) * 8f * scale;
        float pulse = 1f + Math.min(0.25f, level * 1.5f);
        float vbW = bird.viewBoxWidth();
        float vbH = bird.viewBoxHeight();
        float fit = Math.min(width / vbW, height / vbH) * 0.7f * pulse;
        float x = width  / 2f - vbW * fit / 2f;
        float y = height / 2f - vbH * fit / 2f + bobY;
        bird.draw(c, x, y, fit);

        Gfx.textCenter(c, "loaded from assets/bird.svg",
            width / 2f, height - 16f * scale, 11f * scale, Palette.UI_TEXT_INK);
    }
}
