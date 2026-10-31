package dev.ichinomiya.ninebotcast;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import android.view.Display;
import android.view.WindowManager;
import android.net.Uri;
import java.util.Locale;
import dev.ichinomiya.ninebotcast.core.CaptureSize;

/** 自包含的通用 H.264/RTP/UDP 发送器；不调用九号 App 或注入模块。 */
public final class MainActivity extends Activity {
    private static final int CAPTURE = 42;
    private TextView status, sizeLabel, networkLabel, logView;
    private EditText hostField, portField, widthField, heightField, dpiField, fpsField, bitrateField;
    private int plannedWidth, plannedHeight;
    private android.content.SharedPreferences preferences;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LogStore.init(this);
        preferences = getSharedPreferences("cast_settings", MODE_PRIVATE);
        buildUi();
        LogStore.add("APP 启动：独立 MediaProjection → VirtualDisplay → MediaCodec → H.264/RTP/UDP 链路");
        LogStore.add("兼容性状态：九号车机专用握手、目标地址发现和接收协议均未验证");
        refreshLog();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 20, 28, 24);
        root.addView(text("独立屏幕采集与 RTP 发送", 24, Color.BLACK));
        root.addView(text("本 App 自己采集、创建虚拟屏、编码 H.264 并发送 RTP/UDP；不需要 LSPosed/NinebotEnhance 或九号 App。", 15, Color.DKGRAY));
        root.addView(text("注意：这只是通用 RTP/UDP 发送链路，不代表已兼容九号车机。车机协议、握手、地址发现、RTP 参数和实车画面接收仍未验证。", 14, Color.rgb(150, 55, 0)));

        root.addView(section("1. 接收端网络目标（手动填写）"));
        root.addView(text("目标主机/IP 必须由你自己的接收端提供。端口默认 5004 仅作通用测试输入，不是九号车机协议结论。", 13, Color.DKGRAY));
        hostField = field("接收端主机名或 IP", preferences.getString("host", "")); root.addView(hostField);
        portField = field("UDP 端口", preferences.getString("port", "5004")); portField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); root.addView(portField);
        networkLabel = text("当前网络：检测中", 13, Color.DKGRAY); root.addView(networkLabel); updateNetwork();

        root.addView(section("2. 独立采集与发送"));
        root.addView(text("开始后由 Android 请求屏幕录制授权。通过授权后，服务启动虚拟显示器和编码器并向上方目标发送 H.264/RTP。UDP 本身不提供接收确认。", 13, Color.DKGRAY));
        Button start = button("开始独立采集并发送"); root.addView(start);
        Button stop = button("停止并回收会话"); root.addView(stop);
        status = text("尚未开始", 14, Color.DKGRAY); root.addView(status);
        start.setOnClickListener(v -> requestCapture());
        stop.setOnClickListener(v -> stopCast());

        root.addView(section("3. 采集与编码参数"));
        sizeLabel = text("正在计算采集尺寸…", 14, Color.DKGRAY); root.addView(sizeLabel);
        Button recalc = button("重新检测屏幕尺寸"); root.addView(recalc); recalc.setOnClickListener(v -> planSize());
        widthField = settingField(root, "编码宽度（像素，偶数）", "width", "1280");
        heightField = settingField(root, "编码高度（像素，偶数）", "height", "720");
        dpiField = settingField(root, "虚拟显示器 DPI", "dpi", "160");
        fpsField = settingField(root, "编码帧率 FPS", "fps", "20");
        bitrateField = settingField(root, "编码码率 bps", "bitrate", "2500000");
        Button save = button("保存参数"); root.addView(save); save.setOnClickListener(v -> saveSettings());

        root.addView(section("诊断日志"));
        logView = text("", 12, Color.DKGRAY); logView.setTypeface(android.graphics.Typeface.MONOSPACE); logView.setTextIsSelectable(true);
        ScrollView logs = new ScrollView(this); logs.addView(logView); root.addView(logs, new LinearLayout.LayoutParams(-1, 220));
        Button refresh = button("刷新日志"); root.addView(refresh); refresh.setOnClickListener(v -> refreshLog());
        Button share = button("分享诊断日志"); root.addView(share); share.setOnClickListener(v -> shareLog());
        Button settings = button("打开应用权限设置"); root.addView(settings);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));

        ScrollView all = new ScrollView(this); all.addView(root); setContentView(all);
        planSize();
    }

    private void requestCapture() {
        String host = hostField.getText().toString().trim();
        int port = parse(portField, 0);
        if (host.isEmpty()) { status.setText("请填写接收端主机名或 IP"); hostField.requestFocus(); return; }
        if (port < 1 || port > 65535) { status.setText("UDP 端口必须在 1–65535 之间"); portField.requestFocus(); return; }
        if (!saveSettings()) return;
        preferences.edit().putString("host", host).putString("port", Integer.toString(port)).apply();
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            if (manager == null) throw new IllegalStateException("系统屏幕录制服务不可用");
            LogStore.add("CAPTURE 请求系统授权；target=" + host + ":" + port + " protocol=generic-H264-RTP-UDP");
            startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE);
            status.setText("等待系统屏幕录制授权");
        } catch (RuntimeException e) {
            status.setText("无法请求屏幕录制授权：" + e.getClass().getSimpleName());
            LogStore.add("CAPTURE 授权请求失败：" + e);
            refreshLog();
        }
    }

    private void stopCast() {
        // 已运行的服务走 stopService，避免为“停止”再创建一个新的前台服务实例。
        stopService(new Intent(this, CastService.class));
        status.setText("已请求停止；请检查日志确认资源回收");
        LogStore.add("CAST STOP requested by user"); refreshLog();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            status.setText("屏幕录制授权已取消"); LogStore.add("CAPTURE 授权取消"); refreshLog(); return;
        }
        String host = hostField.getText().toString().trim();
        int port = parse(portField, 5004);
        Intent service = new Intent(this, CastService.class)
                .putExtra(CastService.EXTRA_RESULT, result)
                .putExtra(CastService.EXTRA_DATA, data)
                .putExtra("host", host).putExtra("port", port)
                .putExtra("width", even(parse(widthField, plannedWidth)))
                .putExtra("height", even(parse(heightField, plannedHeight)))
                .putExtra("dpi", parse(dpiField, 160))
                .putExtra("fps", parse(fpsField, 20))
                .putExtra("bitrate", parse(bitrateField, 2500000));
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("正在启动独立采集与 RTP/UDP 发送；本地 UDP 发出不等于车机已接收");
            LogStore.add("CAST START submitted to self-contained foreground service");
        } catch (RuntimeException e) {
            status.setText("无法启动采集服务：" + e.getClass().getSimpleName()); LogStore.add("CAST START failed=" + e);
        }
        refreshLog();
    }

    private boolean saveSettings() {
        int w = even(parse(widthField, plannedWidth)), h = even(parse(heightField, plannedHeight));
        int dpi = parse(dpiField, 160), fps = parse(fpsField, 20), bitrate = parse(bitrateField, 2500000);
        if (w < 160 || h < 160 || w > 3840 || h > 2160) { status.setText("编码尺寸范围应为 160–3840 × 160–2160"); return false; }
        if (dpi < 72 || dpi > 1000) { status.setText("DPI 范围应为 72–1000"); return false; }
        if (fps < 1 || fps > 60) { status.setText("帧率范围应为 1–60 FPS"); return false; }
        if (bitrate < 100000 || bitrate > 50000000) { status.setText("码率范围应为 100000–50000000 bps"); return false; }
        preferences.edit().putString("width", Integer.toString(w)).putString("height", Integer.toString(h))
                .putString("dpi", Integer.toString(dpi)).putString("fps", Integer.toString(fps))
                .putString("bitrate", Integer.toString(bitrate)).apply();
        widthField.setText(Integer.toString(w)); heightField.setText(Integer.toString(h));
        LogStore.add("SETTINGS saved frame=" + w + "x" + h + " dpi=" + dpi + " fps=" + fps + " bitrate=" + bitrate);
        Toast.makeText(this, "采集与编码参数已保存", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void planSize() {
        try {
            Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
            android.graphics.Point p = new android.graphics.Point(); display.getRealSize(p);
            CaptureSize capture = CaptureSize.fit(Math.max(p.x, p.y), Math.min(p.x, p.y));
            plannedWidth = even(parse(preferences.getString("width", Integer.toString(capture.width())), capture.width()));
            plannedHeight = even(parse(preferences.getString("height", Integer.toString(capture.height())), capture.height()));
            if (sizeLabel != null) sizeLabel.setText(String.format(Locale.ROOT, "手机屏幕 %d×%d；当前编码目标 %d×%d", p.x, p.y, plannedWidth, plannedHeight));
            if (widthField != null && !preferences.contains("width")) widthField.setText(Integer.toString(plannedWidth));
            if (heightField != null && !preferences.contains("height")) heightField.setText(Integer.toString(plannedHeight));
            LogStore.add("DISPLAY source=" + p.x + "x" + p.y + " encoder=" + plannedWidth + "x" + plannedHeight);
        } catch (Exception e) {
            plannedWidth = 1280; plannedHeight = 720;
            if (sizeLabel != null) sizeLabel.setText("无法读取屏幕尺寸，采用 1280×720 编码默认值");
            LogStore.add("DISPLAY size detection failed=" + e.getClass().getSimpleName());
        }
    }

    private EditText settingField(LinearLayout parent, String label, String key, String fallback) {
        parent.addView(text(label, 14, Color.DKGRAY));
        EditText field = field(label, preferences.getString(key, fallback)); field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); parent.addView(field); return field;
    }
    private int parse(EditText field, int fallback) { try { return Integer.parseInt(field.getText().toString().trim()); } catch (Exception ignored) { return fallback; } }
    private static int parse(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; } }
    private static int even(int value) { return Math.max(2, value & ~1); }

    private void updateNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork(); NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            String type = c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "移动数据" : c != null ? "其他网络" : "无网络";
            networkLabel.setText("当前网络：" + type + "；发送使用系统默认路由"); LogStore.add("NETWORK current=" + type);
        } catch (Exception e) { networkLabel.setText("当前网络：无法读取"); }
    }
    private void refreshLog() { if (logView != null) logView.setText(LogStore.text()); }
    private void shareLog() {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, LogStore.text());
        startActivity(Intent.createChooser(share, "分享独立投屏诊断日志"));
    }
    private TextView section(String s) { return text(s, 18, Color.rgb(0, 80, 80)); }
    private Button button(String s) { Button b = new Button(this); b.setText(s); return b; }
    private EditText field(String hint, String value) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); return e; }
    private TextView text(String s, int size, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, 7, 0, 7); return v; }
}
