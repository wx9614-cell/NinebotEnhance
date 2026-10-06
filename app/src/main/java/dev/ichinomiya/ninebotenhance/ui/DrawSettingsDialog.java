package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.DrawSettings;
import dev.ichinomiya.ninebotenhance.core.HudPalette;
import dev.ichinomiya.ninebotenhance.notification.DrawPanel;

/** The drawn picture: the gauge's full scale and the three field slots, with the picture itself previewed above the controls. */
public final class DrawSettingsDialog {
    private static final int STEP = DrawSettings.MAX_SPEED_STEP;
    public static void show(Activity activity, FrameClient frames, View reference) {
        MirrorUi theme = new MirrorUi(activity, reference); DrawSettings current = frames.drawSettings();
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        int pad = MirrorUi.dp(activity, 20), gap = MirrorUi.dp(activity, 8); content.setPadding(pad, gap, pad, gap);
        Preview preview = new Preview(activity, theme.dark, frames.cachedSettings().background(theme.dark), frames.frameWidth(), frames.frameHeight());
        preview.setClipToOutline(true); preview.setBackground(theme.background(activity, theme.input, 12, false));
        content.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(WidgetOptionsDialog.caption(activity, theme, "最大速度"), new LinearLayout.LayoutParams(0, -2, 1));
        TextView shown = new TextView(activity); shown.setTextColor(theme.text); shown.setTextSize(13); shown.setText(current.maxSpeed() + " km/h"); header.addView(shown);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2); headerParams.topMargin = gap; content.addView(header, headerParams);
        SeekBar speed = new SeekBar(activity); speed.setMin(DrawSettings.MIN_MAX_SPEED / STEP); speed.setMax(DrawSettings.MAX_MAX_SPEED / STEP);
        speed.setProgress(current.maxSpeed() / STEP); speed.setContentDescription("最大速度");
        speed.setProgressTintList(ColorStateList.valueOf(theme.accent)); speed.setThumbTintList(ColorStateList.valueOf(theme.accent));
        speed.setProgressBackgroundTintList(ColorStateList.valueOf(theme.input));
        content.addView(speed, new LinearLayout.LayoutParams(-1, MirrorUi.dp(activity, 44)));
        Spinner[] fields = new Spinner[3]; int[] chosen = {current.first(), current.second(), current.third()};
        for (int i = 0; i < 3; i++) {
            content.addView(WidgetOptionsDialog.caption(activity, theme, "信息 " + (i + 1)));
            ChoiceSpinner spinner = new ChoiceSpinner(activity, theme, "信息 " + (i + 1));
            spinner.setAdapter(new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, DrawSettings.FIELD_NAMES) {
                private View row(int position, View convertView) {
                    TextView text = convertView instanceof TextView ? (TextView) convertView : new TextView(activity);
                    text.setText(getItem(position)); text.setTextColor(theme.text); text.setTextSize(15);
                    text.setGravity(Gravity.CENTER_VERTICAL); text.setPadding(gap, gap, gap, gap); text.setMinHeight(MirrorUi.dp(activity, 44));
                    text.setBackgroundColor(android.graphics.Color.TRANSPARENT); return text;
                }
                @Override public View getView(int position, View view, ViewGroup parent) { return row(position, view); }
                @Override public View getDropDownView(int position, View view, ViewGroup parent) { return row(position, view); }
            });
            spinner.setBackground(theme.background(activity, theme.input, 14, false)); spinner.setClipToOutline(true);
            spinner.setSelection(chosen[i]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = gap / 2;
            content.addView(spinner, params); fields[i] = spinner;
        }
        java.util.function.Supplier<DrawSettings> edited = () -> new DrawSettings(speed.getProgress() * STEP,
                fields[0].getSelectedItemPosition(), fields[1].getSelectedItemPosition(), fields[2].getSelectedItemPosition());
        preview.settings = current;
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) { shown.setText(progress * STEP + " km/h"); preview.settings = edited.get(); preview.invalidate(); }
            public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) {}
        });
        for (Spinner spinner : fields) spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { preview.settings = edited.get(); preview.invalidate(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        TextView title = new TextView(activity); title.setText("绘制管理"); title.setTextSize(20); title.setTextColor(theme.text); title.setPadding(pad, pad, pad, pad / 2);
        AlertDialog dialog = new AlertDialog.Builder(activity).setCustomTitle(title).setView(scroll).setNegativeButton("关闭", null).setPositiveButton("保存", null).create();
        dialog.show(); dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 22, false));
        dialog.getButton(-1).setTextColor(theme.accent); dialog.getButton(-2).setTextColor(theme.accent);
        dialog.getButton(-1).setOnClickListener(v -> { frames.saveDrawSettings(edited.get()); dialog.dismiss(); });
    }
    /** The picture at the frame's aspect ratio, filled with sample values. */
    private static final class Preview extends View {
        private final DrawPanel panel = new DrawPanel();
        private final boolean dark; private final int background, frameWidth, frameHeight;
        DrawSettings settings = DrawSettings.DEFAULT;
        Preview(Context context, boolean dark, int background, int frameWidth, int frameHeight) {
            super(context); this.dark = dark; this.background = background;
            this.frameWidth = Math.max(160, frameWidth); this.frameHeight = Math.max(160, frameHeight);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec); setMeasuredDimension(width, Math.round(width * (float) frameHeight / frameWidth));
        }
        @Override protected void onDraw(Canvas canvas) {
            int save = canvas.save(); float scale = getWidth() / (float) frameWidth; canvas.scale(scale, scale);
            panel.draw(canvas, frameWidth, frameHeight, HudPalette.of(dark), background, settings,
                    new DrawPanel.Values(settings.maxSpeed() * 0.6f, 850, 79.2f, 88, 2.4f, 2.6f,-1));
            canvas.restoreToCount(save);
        }
    }
    private DrawSettingsDialog() {}
}
