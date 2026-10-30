package dev.ichinomiya.ninebotcast;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.List;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;
import android.widget.*;
import java.util.Locale;
import dev.ichinomiya.ninebotcast.core.CaptureSize;
import dev.ichinomiya.ninebotcast.core.DisplaySettings;

/** Standalone virtual-display sender. It deliberately has no Bluetooth/GATT dependency. */
public final class MainActivity extends Activity {
    private static final int CAPTURE = 42;
    private static final int BLE_PERMISSION = 43;
    private TextView status, sizeLabel, networkLabel, logView, vehicleLabel;
    private Button scanButton;
    private BleVehicleScanner vehicleScanner;
    private EditText widthField, heightField, virtualWidthField, virtualHeightField, dpiField, fpsField, bitrateField;
    private EditText bottomInsetField, maxSpeedField, drawNameField;
    private CheckBox autoScanBox, keepPhoneDpiBox, virtualOverrideBox, drawOverlayBox, touchInputBox;
    private int plannedWidth, plannedHeight;
    private DisplaySettings plannedDisplay;
    private android.content.SharedPreferences preferences;
    private String activeExternalRequest;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LogStore.init(this);
        preferences = getSharedPreferences("cast_settings", MODE_PRIVATE);
        buildUi();
        vehicleScanner = new BleVehicleScanner(this);
        LogStore.add("APP 启动：自动检测 BLE 车辆；接收端由九号协议协商");
        refreshLog();
        if (preferences.getBoolean("auto_scan", true)) scanVehicles();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 20, 28, 24);
        root.addView(text("独立虚拟屏投屏", 24, Color.BLACK));
        root.addView(text("独立 APK → 模块控制面 → 九号官方车机投屏会话", 15, Color.DKGRAY));
        root.addView(text("本应用只提交投屏请求和画面配置；车辆协商、虚拟屏生命周期、编码与 BLE/RTP 发送由已注入九号官方进程完成。", 13, Color.DKGRAY));

        root.addView(section("1. 自动检测车辆"));
        root.addView(text("程序自动扫描附近的九号/Segway 蓝牙设备。车辆接收端地址必须由九号投屏协议协商，不能手填普通 UDP 地址。", 13, Color.DKGRAY));
        vehicleLabel = text("尚未检测车辆", 14, Color.DKGRAY); root.addView(vehicleLabel);
        scanButton = button("自动检测蓝牙车辆"); root.addView(scanButton);
        scanButton.setOnClickListener(v -> scanVehicles());
        networkLabel = text("当前网络：检测中", 13, Color.DKGRAY); root.addView(networkLabel);
        updateNetwork();

        root.addView(section("2. 自动虚拟屏"));
        sizeLabel = text("正在计算手机屏幕尺寸…", 14, Color.DKGRAY); root.addView(sizeLabel);
        Button recalc = button("重新检测屏幕大小"); root.addView(recalc);
        recalc.setOnClickListener(v -> planSize());

        root.addView(section("3. 开始投屏"));
        root.addView(text("点击后向已注入的九号官方 App 提交车辆投屏请求。官方页面需处于前台并已完成模块授权。", 13, Color.DKGRAY));
        Button start = button("请求官方车机投屏"); root.addView(start);
        Button stop = button("停止官方投屏"); root.addView(stop);
        status = text("等待开始", 14, Color.DKGRAY); root.addView(status);
        start.setOnClickListener(v -> requestOfficialCast());
        stop.setOnClickListener(v -> stopOfficialCast());

        Button advanced = button("高级设置（自动检测、画面和编码参数）"); root.addView(advanced);
        LinearLayout details = new LinearLayout(this); details.setOrientation(LinearLayout.VERTICAL); details.setVisibility(android.view.View.GONE);
        autoScanBox = new CheckBox(this); autoScanBox.setText("启动时自动检测蓝牙车辆"); autoScanBox.setChecked(preferences.getBoolean("auto_scan", true)); details.addView(autoScanBox);
        keepPhoneDpiBox = new CheckBox(this); keepPhoneDpiBox.setText("虚拟屏保持手机 DPI"); keepPhoneDpiBox.setChecked(preferences.getBoolean("keep_phone_dpi", false)); details.addView(keepPhoneDpiBox);
        details.addView(text("车机绘制与显示", 16, Color.rgb(0, 80, 80)));
        drawOverlayBox = new CheckBox(this); drawOverlayBox.setText("启用车机绘制叠加层"); drawOverlayBox.setChecked(preferences.getBoolean("draw_overlay", true)); details.addView(drawOverlayBox);
        drawNameField = settingField(details, "绘制标题（可填姓名或车辆名称）", "draw_name", "九号车机"); drawNameField.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        maxSpeedField = settingField(details, "绘制速度满量程 km/h", "draw_max_speed", "80");
        touchInputBox = new CheckBox(this); touchInputBox.setText("启用虚拟屏触控输入"); touchInputBox.setChecked(preferences.getBoolean("touch_input", true)); details.addView(touchInputBox);
        virtualOverrideBox = new CheckBox(this); virtualOverrideBox.setText("强制使用自定义虚拟屏尺寸"); virtualOverrideBox.setChecked(preferences.getBoolean("virtual_override", false)); details.addView(virtualOverrideBox);
        details.addView(text("下面参数会保存到本机。留空或无效时使用原模块默认值；宽高必须为偶数。", 12, Color.DKGRAY));
        widthField = settingField(details, "录屏宽度", "width", "1280");
        heightField = settingField(details, "录屏高度", "height", "720");
        virtualWidthField = settingField(details, "虚拟屏宽度", "virtual_width", "640");
        virtualHeightField = settingField(details, "虚拟屏高度", "virtual_height", "440");
        bottomInsetField = settingField(details, "底部绘制预留高度", "bottom_inset", "0");
        dpiField = settingField(details, "虚拟屏 DPI", "dpi", "160");
        fpsField = settingField(details, "帧率 FPS", "fps", "20");
        bitrateField = settingField(details, "码率 bps", "bitrate", "2500000");
        Button saveAdvanced = button("保存高级设置并重新计算"); details.addView(saveAdvanced);
        saveAdvanced.setOnClickListener(v -> { saveAdvancedSettings(); planSize(); });
        logView = text("", 12, Color.DKGRAY); logView.setTypeface(android.graphics.Typeface.MONOSPACE); logView.setTextIsSelectable(true);
        ScrollView logs = new ScrollView(this); logs.addView(logView); details.addView(logs, new LinearLayout.LayoutParams(-1, 260));
        Button refresh = button("刷新日志"); details.addView(refresh);
        Button settings = button("打开应用权限设置"); details.addView(settings);
        refresh.setOnClickListener(v -> refreshLog());
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))));
        advanced.setOnClickListener(v -> { boolean show = details.getVisibility() != android.view.View.VISIBLE; details.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE); advanced.setText(show ? "收起高级设置" : "高级设置（自动检测、画面和编码参数）"); refreshLog(); });
        root.addView(details);
        ScrollView all = new ScrollView(this); all.addView(root); setContentView(all);
        planSize();
    }

    private void planSize() {
        try {
            Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
            android.graphics.Point p = new android.graphics.Point(); display.getRealSize(p);
            int sourceW = Math.max(p.x, p.y), sourceH = Math.min(p.x, p.y);
            CaptureSize capture = CaptureSize.fit(sourceW, sourceH);
            plannedWidth = intSetting("width", capture.width()); plannedHeight = intSetting("height", capture.height());
            if ((plannedWidth & 1) != 0) plannedWidth--;
            if ((plannedHeight & 1) != 0) plannedHeight--;
            int virtualWidth = intSetting("virtual_width", DisplaySettings.DEFAULT_VIRTUAL_WIDTH);
            int virtualHeight = intSetting("virtual_height", DisplaySettings.DEFAULT_VIRTUAL_HEIGHT);
            int dpi = intSetting("dpi", DisplaySettings.DEFAULT_DPI);
            int bottomInset = intSetting("bottom_inset", 0);
            try {
                plannedDisplay = new DisplaySettings(plannedWidth, plannedHeight, virtualWidth, virtualHeight, dpi,
                        DisplaySettings.DEFAULT_BACKGROUND_COLOR, preferences.getBoolean("keep_phone_dpi", false),
                        DisplaySettings.DEFAULT_LIGHT_BACKGROUND_COLOR, preferences.getBoolean("virtual_override", false), bottomInset);
            } catch (IllegalArgumentException ignored) {
                plannedWidth = capture.width(); plannedHeight = capture.height();
                plannedDisplay = DisplaySettings.defaults().withFrame(plannedWidth, plannedHeight);
                LogStore.add("DISPLAY 高级参数无效，已恢复自动默认值");
            }
            String value = String.format(Locale.ROOT,
                    "手机屏幕 %d×%d；整帧 %d×%d；虚拟屏 %d×%d；%d DPI（原模块自动规则）",
                    p.x, p.y, plannedWidth, plannedHeight, plannedDisplay.virtualWidth,
                    plannedDisplay.virtualHeight, plannedDisplay.dpi);
            if (sizeLabel != null) sizeLabel.setText(value);
            LogStore.add("DISPLAY profile=" + plannedDisplay.label());
            LogStore.add("DISPLAY auto source=" + p.x + "x" + p.y + " virtual=" + plannedWidth + "x" + plannedHeight);
        } catch (Exception e) { plannedWidth = 848; plannedHeight = 480; plannedDisplay = DisplaySettings.defaults(); if (sizeLabel != null) sizeLabel.setText("无法读取屏幕，使用原模块默认整帧 848×480、虚拟屏 640×440、160 DPI"); LogStore.add("DISPLAY auto failed=" + e); }
    }

    private EditText settingField(LinearLayout parent, String label, String key, String fallback) {
        parent.addView(text(label, 14, Color.DKGRAY));
        EditText field = field(label, preferences.getString(key, fallback));
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        parent.addView(field);
        return field;
    }

    private int intSetting(String key, int fallback) {
        try { return Integer.parseInt(preferences.getString(key, Integer.toString(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private int fieldValue(EditText field, int fallback) {
        try { return Integer.parseInt(field.getText().toString().trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private void saveAdvancedSettings() {
        preferences.edit()
                .putBoolean("auto_scan", autoScanBox.isChecked())
                .putBoolean("keep_phone_dpi", keepPhoneDpiBox.isChecked())
                .putBoolean("virtual_override", virtualOverrideBox.isChecked())
                .putBoolean("draw_overlay", drawOverlayBox.isChecked())
                .putBoolean("touch_input", touchInputBox.isChecked())
                .putString("draw_name", drawNameField.getText().toString().trim())
                .putString("draw_max_speed", Integer.toString(fieldValue(maxSpeedField, 80)))
                .putString("bottom_inset", Integer.toString(fieldValue(bottomInsetField, 0)))
                .putString("width", Integer.toString(fieldValue(widthField, 1280)))
                .putString("height", Integer.toString(fieldValue(heightField, 720)))
                .putString("virtual_width", Integer.toString(fieldValue(virtualWidthField, 640)))
                .putString("virtual_height", Integer.toString(fieldValue(virtualHeightField, 440)))
                .putString("dpi", Integer.toString(fieldValue(dpiField, 160)))
                .putString("fps", Integer.toString(fieldValue(fpsField, 20)))
                .putString("bitrate", Integer.toString(fieldValue(bitrateField, 2500000)))
                .apply();
        LogStore.add("SETTINGS 已保存高级参数");
        Toast.makeText(this, "高级设置已保存", Toast.LENGTH_SHORT).show();
    }

    private static int even(int value) { return Math.max(2, value & ~1); }

    private void requestOfficialCast() {
        if (activeExternalRequest != null) { status.setText("官方投屏请求已发送，请查看九号 App"); return; }
        activeExternalRequest = java.util.UUID.randomUUID().toString().replace("-", "");
        Intent request = new Intent("dev.ichinomiya.ninebotenhance.EXTERNAL_CAST_BEGIN")
                .setPackage("cn.ninebot.ninebot")
                .putExtra("external_cast_contract_v1", "external_cast_contract_v1")
                .putExtra("external_cast_request", activeExternalRequest)
                .putExtra("external_cast_source", "VIRTUAL")
                .putExtra("external_cast_app", preferences.getString("selected_app", ""));
        sendBroadcast(request);
        status.setText("已提交官方车机投屏请求，等待车辆检查");
        LogStore.add("EXTERNAL BEGIN submitted request=" + activeExternalRequest + " source=VIRTUAL");
        refreshLog();
    }

    private void stopOfficialCast() {
        if (activeExternalRequest == null) { status.setText("没有活动的官方投屏请求"); return; }
        Intent request = new Intent("dev.ichinomiya.ninebotenhance.EXTERNAL_CAST_STOP")
                .setPackage("cn.ninebot.ninebot")
                .putExtra("external_cast_contract_v1", "external_cast_contract_v1")
                .putExtra("external_cast_request", activeExternalRequest);
        sendBroadcast(request);
        LogStore.add("EXTERNAL STOP submitted request=" + activeExternalRequest);
        activeExternalRequest = null;
        status.setText("已请求停止官方投屏");
        refreshLog();
    }

    private void scanVehicles() {
        if (!vehicleScanner.hasPermission()) {
            requestPermissions(Build.VERSION.SDK_INT >= 31
                    ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, BLE_PERMISSION);
            return;
        }
        scanButton.setEnabled(false);
        vehicleScanner.start(new BleVehicleScanner.Listener() {
            @Override public void onState(String state) { runOnUiThread(() -> { vehicleLabel.setText(state); scanButton.setEnabled(true); }); }
            @Override public void onDevices(List<BluetoothDevice> devices) {
                runOnUiThread(() -> {
                    if (devices.isEmpty()) { vehicleLabel.setText("正在扫描附近九号/Segway 车辆…"); return; }
                    StringBuilder text = new StringBuilder("发现车辆（仅完成 BLE 发现，未完成投屏协商）：\n");
                    for (BluetoothDevice device : devices) text.append(BleVehicleScanner.label(device)).append('\n');
                    vehicleLabel.setText(text.toString());
                });
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CAPTURE) return;
        if (result != RESULT_OK || data == null) { status.setText("录屏授权已取消"); LogStore.add("录屏授权取消"); return; }
        status.setText("九号专用接收端尚未协商，未启动视频发送");
        LogStore.add("录屏授权完成，但未启动发送：缺少九号专用接收端协商");
        refreshLog();
    }

    private void updateNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork(); NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            String type = c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "移动数据" : c != null ? "其他网络" : "无网络";
            networkLabel.setText("当前网络：" + type + "；发送使用系统默认路由"); LogStore.add("NETWORK 当前=" + type);
        } catch (Exception e) { networkLabel.setText("当前网络：无法读取"); }
    }

    private void refreshLog() { if (logView != null) logView.setText(LogStore.text()); }
    private TextView section(String s) { return text(s, 18, Color.rgb(0, 80, 80)); }
    private Button button(String s) { Button b = new Button(this); b.setText(s); return b; }
    private EditText field(String hint, String value) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); return e; }
    private TextView text(String s, int size, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, 7, 0, 7); return v; }
}
