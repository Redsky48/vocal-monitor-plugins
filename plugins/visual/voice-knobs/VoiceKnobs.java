package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;
import com.vocalmonitor.plugin.gamekit.ui.Button;
import com.vocalmonitor.plugin.gamekit.ui.Knob;
import com.vocalmonitor.plugin.gamekit.ui.Toggle;

import java.util.Map;

/**
 * Voice Knobs — interactive plugin demonstrating gamekit/ui/ widgets.
 * Replace the demo layout with your own controls.
 */
public final class VoiceKnobs extends GamePluginBase {

    private final Button actionBtn = new Button().fill(Palette.ACCENT_YELLOW);
    private final Toggle muteSw    = new Toggle().value(false);
    private final Knob   gainKnob  = new Knob().value01(0.5f);

    @Override
    public void onTouchDown(float x, float y) {
        actionBtn.touchDown(x, y);
        muteSw.touchDown(x, y);
        gainKnob.touchDown(x, y);
    }
    @Override
    public void onTouchMove(float x, float y) {
        actionBtn.touchMove(x, y);
        muteSw.touchMove(x, y);
        gainKnob.touchMove(x, y);
    }
    @Override
    public void onTouchUp(float x, float y) {
        actionBtn.touchUp(x, y);
        muteSw.touchUp(x, y);
        gainKnob.touchUp(x, y);
    }

    @Override
    public void render(PluginCanvas c, int width, int height, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        beginFrame(width, height, timeMs, streams);
        Gfx.clear(c, width, height, Palette.UI_BG_DEEP);
        Gfx.textCenter(c, "Voice Knobs", width / 2f, 36f * scale,
            22f * scale, Palette.UI_TEXT);

        float cy = height * 0.55f;
        float knobR = Math.min(width, height) * 0.12f;
        gainKnob.draw(c, width * 0.25f, cy, knobR, scale);
        muteSw.draw(c, width * 0.45f, cy - 16f * scale,
                       width * 0.55f, cy + 16f * scale, scale);
        if (actionBtn.draw(c, "Tap me",
                width * 0.65f, cy - 24f * scale,
                width * 0.90f, cy + 24f * scale, scale)) {
            juice.shake(4f * scale, 0.10f);
        }

        Gfx.textLeft(c,
            "Gain "  + Math.round(gainKnob.value01() * 100f) + "%",
            width * 0.20f, height * 0.78f, 13f * scale, Palette.UI_TEXT_DIM);
        Gfx.textLeft(c,
            "Muted " + (muteSw.value() ? "yes" : "no"),
            width * 0.45f, height * 0.78f, 13f * scale, Palette.UI_TEXT_DIM);

        juice.drawOverlay(c, width, height);
    }
}
