package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.HiddenFeatures;
import dev.ichinomiya.ninebotenhance.core.LockScreenSettings;

/** Hidden features are grouped into navigable pages so related options can grow without a flat dialog. */
public final class HiddenFeatureDialog {
    public static void show(Activity activity, FrameClient frames, View reference) {
        showRoot(activity, frames, reference);
    }

    private static void showRoot(Activity activity, FrameClient frames, View reference) {
        MirrorUi theme = new MirrorUi(activity, reference);
        HiddenFeatures current = frames.hiddenFeatures();
        LinearLayout content = page(activity, theme);
        CheckBox throttle = check(activity, theme, content, "双向转把选项", current.throttle());
        CheckBox hardkey = check(activity, theme, content, "仪表按键卡片", current.hardkey());
        CheckBox cruise = check(activity, theme, content, "原版巡航入口", current.cruise());
        Button lock = pageButton(activity, theme, "锁屏设置");
        content.addView(lock, rowParams(activity));
        AlertDialog dialog = dialog(activity, theme, "隐藏功能", content);
        lock.setOnClickListener(v -> {
            frames.saveHiddenFeatures(new HiddenFeatures(throttle.isChecked(), hardkey.isChecked(), cruise.isChecked()));
            dialog.dismiss();
            showLockMenu(activity, frames, reference);
        });
        dialog.show();
    }

    private static void showLockMenu(Activity activity, FrameClient frames, View reference) {
        MirrorUi theme = new MirrorUi(activity, reference);
        LinearLayout content = page(activity, theme);
        TextView summary = new TextView(activity);
        summary.setText("控制手机熄屏后车机画面的显示方式");
        summary.setTextColor(theme.secondary);
        summary.setTextSize(14);
        summary.setPadding(0, 0, 0, MirrorUi.dp(activity, 12));
        content.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        TextView mode = new TextView(activity);
        mode.setText(frames.lockScreenModeSummary());
        mode.setTextColor(theme.secondary);
        mode.setTextSize(14);
        mode.setPadding(0, 0, 0, MirrorUi.dp(activity, 12));
        content.addView(mode, new LinearLayout.LayoutParams(-1, -2));
        Button options = pageButton(activity, theme, "锁屏显示选项");
        content.addView(options, rowParams(activity));
        AlertDialog dialog = dialog(activity, theme, "锁屏设置", content);
        options.setOnClickListener(v -> {
            if (!frames.virtualDisplayAvailable()) {
                Toast.makeText(activity, "锁屏设置仅在虚拟屏已连接并正常运行时可用；当前虚拟屏已断开或当前不是虚拟屏，无法启用。", Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            showLockOptions(activity, frames, reference);
        });
        dialog.show();
    }

    private static void showLockOptions(Activity activity, FrameClient frames, View reference) {
        MirrorUi theme = new MirrorUi(activity, reference);
        LockScreenSettings current = frames.lockScreenSettings();
        LinearLayout content = page(activity, theme);
        CheckBox time = check(activity, theme, content, "显示时间", current.showTime());
        CheckBox date = check(activity, theme, content, "显示日期", current.showDate());
        CheckBox temperature = check(activity, theme, content, "显示温度", current.showTemperature());
        CheckBox temperatureUnit = check(activity, theme, content, "显示温度符号", current.showTemperatureUnit());
        CheckBox refresh = check(activity, theme, content, "锁屏后持续刷新应用内容", current.keepAppRefreshing());
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("锁屏显示选项")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(theme.accent);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(theme.accent);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!frames.virtualDisplayAvailable()) {
                    Toast.makeText(activity, "虚拟屏已断开或当前不是虚拟屏，锁屏设置未启用。请先重新连接并保持虚拟屏运行。", Toast.LENGTH_LONG).show();
                    return;
                }
                frames.saveLockScreenSettings(new LockScreenSettings(time.isChecked(), refresh.isChecked(), true,
                        date.isChecked(), temperature.isChecked(), temperatureUnit.isChecked()));
                if (!frames.cachedSource().virtual()) {
                    Toast.makeText(activity, "当前是绘制或投屏模式，锁屏覆盖层不会启用；设置已保留。", Toast.LENGTH_LONG).show();
                } else if (!frames.virtualDisplayAvailable()) {
                    Toast.makeText(activity, "虚拟屏尚未运行，连接并运行后锁屏覆盖层会自动启用。", Toast.LENGTH_LONG).show();
                }
                dialog.dismiss();
            });
        });
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 22, false));
    }

    private static LinearLayout page(android.app.Activity activity, MirrorUi theme) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = MirrorUi.dp(activity, 20);
        content.setPadding(pad, MirrorUi.dp(activity, 8), pad, MirrorUi.dp(activity, 8));
        return content;
    }

    private static Button pageButton(android.app.Activity activity, MirrorUi theme, String label) {
        Button button = new Button(activity);
        button.setText(label + "  ›");
        button.setTextColor(theme.text);
        button.setTextSize(15);
        button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        button.setAllCaps(false);
        button.setPadding(0, MirrorUi.dp(activity, 8), 0, MirrorUi.dp(activity, 8));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        return button;
    }

    private static LinearLayout.LayoutParams rowParams(android.app.Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = MirrorUi.dp(activity, 8);
        return params;
    }

    private static AlertDialog dialog(android.app.Activity activity, MirrorUi theme, String title, LinearLayout content) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(theme.accent);
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 22, false));
        });
        return dialog;
    }

    private static CheckBox check(android.app.Activity activity, MirrorUi theme, LinearLayout parent, String label, boolean checked) {
        CheckBox box = new CheckBox(activity);
        box.setText(label);
        box.setTextColor(theme.text);
        box.setTextSize(15);
        box.setButtonTintList(ColorStateList.valueOf(theme.accent));
        box.setPadding(0, MirrorUi.dp(activity, 8), 0, MirrorUi.dp(activity, 8));
        box.setChecked(checked);
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private HiddenFeatureDialog() {}
}
