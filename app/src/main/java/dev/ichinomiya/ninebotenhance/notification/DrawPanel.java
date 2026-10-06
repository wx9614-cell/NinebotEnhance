package dev.ichinomiya.ninebotenhance.notification;

import android.graphics.*;
import android.text.TextPaint;
import dev.ichinomiya.ninebotenhance.core.DashboardProfile;
import dev.ichinomiya.ninebotenhance.core.DrawLayout;
import dev.ichinomiya.ninebotenhance.core.DrawSettings;
import dev.ichinomiya.ninebotenhance.core.HudPalette;
import java.util.Locale;
import java.util.Objects;

/**
 * The drawn picture source: a speed gauge and up to three fields painted straight into the frame, no app and no capture. The
 * frame's dashboard profile keeps the rows the dashboard paints over free; the values come from the same telemetry the cards use.
 */
public final class DrawPanel {
    /** What is on screen; NaN and -1 mean unknown and are shown as "--". */
    public record Values(float speedKmh, float watts, float volts, int soc, float frontBar, float rearBar) {
        public static final Values NONE = new Values(Float.NaN, Float.NaN, Float.NaN, -1, Float.NaN, Float.NaN);
    }
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    public void draw(Canvas canvas, int width, int height, HudPalette p, int background, DrawSettings settings, Values v) {
        DashboardProfile profile = DashboardProfile.of(width, height);
        int[] fields = settings.shown();
        DrawLayout l = DrawLayout.of(width, height, profile.topInset(), profile.bottomInset(), fields.length);
        canvas.drawColor(background);
        box.set(l.centerX() - l.radius(), l.centerY() - l.radius(), l.centerX() + l.radius(), l.centerY() + l.radius());
        arc.setStyle(Paint.Style.STROKE); arc.setStrokeCap(Paint.Cap.ROUND); arc.setStrokeWidth(l.stroke());
        arc.setColor(p.track()); canvas.drawArc(box, DrawLayout.START_ANGLE, DrawLayout.SWEEP, false, arc);
        float sweep = DrawLayout.sweepFor(v.speedKmh(), settings.maxSpeed());
        if (sweep > 0) { arc.setColor(p.accent()); canvas.drawArc(box, DrawLayout.START_ANGLE, sweep, false, arc); }
        text.setTextAlign(Paint.Align.CENTER);
        String speed = Float.isNaN(v.speedKmh()) ? "--" : String.valueOf(Math.round(v.speedKmh()));
        text.setColor(p.text()); text.setTextSize(l.valueSize()); text.setFakeBoldText(true);
        float baseline = l.centerY() + l.valueSize() * 0.36f;
        canvas.drawText(speed, l.centerX(), baseline, text);
        text.setFakeBoldText(false); text.setColor(p.unit()); text.setTextSize(l.unitSize());
        canvas.drawText("km/h", l.centerX(), baseline + l.unitSize() * 1.35f, text);
        // The scale's two ends, just outside the arc's open side.
        text.setColor(p.label()); text.setTextSize(l.scaleSize());
        float endY = l.centerY() + l.radius() * 0.72f + l.scaleSize() * 1.2f, endX = l.radius() * 0.72f;
        canvas.drawText("0", l.centerX() - endX, endY, text);
        canvas.drawText(String.valueOf(settings.maxSpeed()), l.centerX() + endX, endY, text);
        for (int i = 0; i < fields.length; i++) {
            float x = l.fieldCenter(i);
            text.setColor(p.text()); text.setTextSize(l.fieldValueSize()); text.setFakeBoldText(true);
            canvas.drawText(value(fields[i], v), x, l.fieldTop() + l.fieldValueSize() * 1.15f, text);
            text.setFakeBoldText(false); text.setColor(p.label()); text.setTextSize(l.fieldLabelSize());
            canvas.drawText(DrawSettings.fieldName(fields[i]), x, l.fieldTop() + l.fieldValueSize() * 1.15f + l.fieldLabelSize() * 1.5f, text);
        }
    }
    public static String value(int field, Values v) {
        switch (field) {
            case DrawSettings.VOLTAGE: return Float.isNaN(v.volts()) ? "--" : String.format(Locale.ROOT, "%.1f V", v.volts());
            case DrawSettings.POWER: return Float.isNaN(v.watts()) ? "--" : Math.round(v.watts()) + " W";
            case DrawSettings.SPEED: return Float.isNaN(v.speedKmh()) ? "--" : Math.round(v.speedKmh()) + " km/h";
            case DrawSettings.TYRES: return (Float.isNaN(v.frontBar()) ? "--" : String.format(Locale.ROOT, "%.1f", v.frontBar())) + " / "
                    + (Float.isNaN(v.rearBar()) ? "--" : String.format(Locale.ROOT, "%.1f", v.rearBar())) + " bar";
            case DrawSettings.BMS_SOC: return v.soc() < 0 ? "--" : v.soc() + "%";
            default: return "";
        }
    }
    /** A stable stamp of what the picture shows, so unchanged frames are handed back to the encoder untouched. */
    public static int stamp(DrawSettings settings, Values v, boolean dark) {
        return Objects.hash(settings, dark, Math.round(v.speedKmh() * 10), Math.round(v.watts()), Math.round(v.volts() * 10), v.soc(),
                Math.round(v.frontBar() * 10), Math.round(v.rearBar() * 10));
    }
}
