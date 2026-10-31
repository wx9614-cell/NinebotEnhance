package dev.ichinomiya.ninebotcast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.net.Uri;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import dev.ichinomiya.ninebotcast.core.CaptureSize;

/** Self-contained screen capture and generic RTP sender. Ninebot vehicle transport is not implemented. */
public final class MainActivity extends Activity {
    private static final int CAPTURE = 42;
    private TextView status, sizeLabel, networkLabel, logView;
    private EditText hostField, portField, widthField, heightField, dpiField, fpsField, bitrateField;
    private LinearLayout advanced;
    private int plannedWidth, plannedHeight;
    private android.content.SharedPreferences preferences;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LogStore.init(this);
        preferences = getSharedPreferences("cast_settings", MODE_PRIVATE);
        buildUi();
        LogStore.add("APP 启动：独立 MediaProjection → VirtualDisplay → MediaCodec → 通用 H.264/RTP/UDP");
        LogStore.add("车辆连接状态：未实现九号 Wi-Fi Direct 协商、车机握手和接收端自动发现");
        refreshLog();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 20, 28, 24);
        root.addView(text("九号仪表独立投屏", 24, Color.BLACK));
        root.addView(text("连接状态：目前不能连接九号车机", 19, Color.rgb(170, 35, 20)));
        root.addView(text("这个版本能独立采集手机屏幕并编码，但还没有九号车机所需的 Wi‑Fi Direct 协商、投屏握手和接收端自动发现。现在开始发送也不会让画面出现在仪表上。蓝牙扫描到车辆，不等于视频投屏链路已建立。", 15, Color.DKGRAY));
        Button why = button("查看还缺什么");
        root.addView(why);
        why.setOnClickListener(v -> showVehicleBlocker());

        Button advancedToggle = button("自建接收端测试（非九号车机）— 展开参数说明");
        root.addView(advancedToggle);
        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setPadding(0, 4, 0, 4);
        advanced.setVisibility(View.GONE);
        buildTestUi(advanced);
        root.addView(advanced);
        advancedToggle.setOnClickListener(v -> {
            boolean show = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedToggle.setText(show ? "收起自建接收端测试" : "自建接收端测试（非九号车机）— 展开参数说明");
        });

        root.addView(section("诊断日志"));
        root.addView(text("日志只说明本机采集、编码或 UDP 发送到某个地址的情况；没有车机反馈就不能证明仪表收到画面。", 13, Color.DKGRAY));
        logView = text("", 12, Color.DKGRAY);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        ScrollView logs = new ScrollView(this);
        logs.addView(logView);
        root.addView(logs, new LinearLayout.LayoutParams(-1, 220));
        LinearLayout actions = new LinearLayout(this);
        Button refresh = button("刷新日志");
        Button share = button("分享完整日志");
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        refresh.setOnClickListener(v -> refreshLog());
        share.setOnClickListener(v -> shareLog());
        Button settings = button("打开应用权限设置");
        root.addView(settings);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));

        ScrollView all = new ScrollView(this);
        all.addView(root);
        setContentView(all);
        planSize();
    }

    private void buildTestUi(LinearLayout box) {
        box.addView(section("仅用于你自己搭建的 RTP 接收端"));
        box.addView(text("只有你手上有一个明确支持 H.264/RTP/UDP 的接收程序，并知道它的地址和监听端口时，才需要这些参数。它们不是九号车机设置；不要猜车机地址或端口。", 14, Color.rgb(125, 55, 15)));

        addSetting(box, "接收端地址（IP 或主机名）", "运行你自己的接收程序那台设备的网络地址。不是车辆蓝牙地址，也不能从扫描到的车名推出来。", "host", "");
        hostField = lastField;
        addSetting(box, "UDP 端口", "接收程序实际监听的 UDP 端口。5004 只是常见的通用 RTP 测试约定，不是九号车机端口；因此这里不预填。", "port", "");
        portField = lastField;

        sizeLabel = text("正在自动检测手机屏幕尺寸…", 14, Color.DKGRAY);
        box.addView(sizeLabel);
        addSetting(box, "编码宽度（像素）", "发出的视频每帧有多少像素宽。尺寸越大越清楚，也更耗网络和编码资源。默认按手机屏幕自动计算；需与接收程序能力匹配。", "width", "");
        widthField = lastField;
        addSetting(box, "编码高度（像素）", "发出的视频每帧有多少像素高。宽高应为偶数，并与接收程序支持的画面尺寸匹配。默认按手机屏幕自动计算。", "height", "");
        heightField = lastField;
        int phoneDpi = Math.max(72, getResources().getDisplayMetrics().densityDpi);
        addSetting(box, "虚拟显示 DPI", "Android 虚拟屏的布局密度，不是网络参数，也不会自动适配车机。默认使用这台手机的系统 DPI。", "dpi", Integer.toString(phoneDpi));
        dpiField = lastField;
        addSetting(box, "帧率（FPS）", "每秒编码多少帧。数值越高运动越顺，也会增加编码和网络负载。20 FPS 只是通用测试起点，不代表车辆要求。", "fps", "20");
        fpsField = lastField;
        addSetting(box, "码率（比特/秒）", "视频数据目标发送速率。2,500,000 bps 约等于 2.5 Mbps；需要根据自建接收端和网络带宽调整，不是车机协议值。", "bitrate", "2500000");
        bitrateField = lastField;

        networkLabel = text("当前网络：检测中", 13, Color.DKGRAY);
        box.addView(networkLabel);
        updateNetwork();
        Button start = button("开始屏幕采集并发送到自建接收端");
        Button stop = button("停止测试并回收资源");
        box.addView(start);
        box.addView(stop);
        status = text("未开始（仅自建接收端测试）", 14, Color.DKGRAY);
        box.addView(status);
        start.setOnClickListener(v -> requestCapture());
        stop.setOnClickListener(v -> stopCast());
        box.addView(text("通过系统授权后：手机屏幕 → 虚拟显示 → H.264 编码 → RTP/UDP。UDP 发包不提供接收确认；此测试不会连接或控制九号车机。", 13, Color.DKGRAY));
    }

    private EditText lastField;
    private void addSetting(LinearLayout parent, String label, String explanation, String key, String fallback) {
        parent.addView(text(label, 15, Color.BLACK));
        parent.addView(text(explanation, 13, Color.DKGRAY));
        String value = preferences.getString(key, fallback);
        EditText input = field(label, value);
        if ("host".equals(key)) input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        else input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        parent.addView(input);
        lastField = input;
    }

    private void showVehicleBlocker() {
        new AlertDialog.Builder(this)
                .setTitle("为什么现在不能直接投到车机？")
                .setMessage("目前项目里能确认的只是两件事：九号官方投屏端曾使用 Wi‑Fi RTP/UDP 或蓝牙 GATT；部分仪表配置使用 Wi‑Fi Direct 和 H.264。\n\n独立 App 尚无经验证的车辆发现/配网、投屏握手字段、视频目标地址与端口，也没有车机实际显示的测试结果。通用 UDP 端口 5004 不能当作九号车机端口。\n\n因此现在不能安全地自动填写这些数据，也不能声称投屏链路已建立。需要先从官方运行日志/网络抓包或车辆实测取得协商流程和接收参数，再实现并以仪表实际显示画面验证。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void requestCapture() {
        String host = hostField.getText().toString().trim();
        int port = parse(portField, 0);
        if (host.isEmpty()) { status.setText("请填写自建接收程序提供的地址；这不是九号车机地址"); hostField.requestFocus(); return; }
        if (port < 1 || port > 65535) { status.setText("请填写自建接收程序实际监听的 UDP 端口（1–65535）"); portField.requestFocus(); return; }
        if (!saveSettings()) return;
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            if (manager == null) throw new IllegalStateException("系统屏幕录制服务不可用");
            LogStore.add("TEST CAPTURE 请求系统授权；generic RTP/UDP target=" + host + ":" + port + "（非车辆目标）");
            startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE);
            status.setText("等待系统屏幕录制授权（自建接收端测试）");
        } catch (RuntimeException e) {
            status.setText("无法请求屏幕录制授权：" + e.getClass().getSimpleName());
            LogStore.add("CAPTURE 授权请求失败：" + e.getClass().getSimpleName());
            refreshLog();
        }
    }

    private void stopCast() {
        stopService(new Intent(this, CastService.class));
        status.setText("已请求停止测试；请检查日志确认资源回收");
        LogStore.add("TEST STOP requested by user");
        refreshLog();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            status.setText("屏幕录制授权已取消");
            LogStore.add("CAPTURE 授权取消");
            refreshLog();
            return;
        }
        Intent service = new Intent(this, CastService.class)
                .putExtra(CastService.EXTRA_RESULT, result)
                .putExtra(CastService.EXTRA_DATA, data)
                .putExtra("host", hostField.getText().toString().trim())
                .putExtra("port", parse(portField, 0))
                .putExtra("width", even(parse(widthField, plannedWidth)))
                .putExtra("height", even(parse(heightField, plannedHeight)))
                .putExtra("dpi", parse(dpiField, getResources().getDisplayMetrics().densityDpi))
                .putExtra("fps", parse(fpsField, 20))
                .putExtra("bitrate", parse(bitrateField, 2500000));
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("自建接收端测试运行中；这不是九号车机投屏");
            LogStore.add("TEST CAST START submitted to self-contained service");
        } catch (RuntimeException e) {
            status.setText("无法启动采集服务：" + e.getClass().getSimpleName());
            LogStore.add("TEST CAST START failed=" + e.getClass().getSimpleName());
        }
        refreshLog();
    }

    private boolean saveSettings() {
        int w = even(parse(widthField, plannedWidth)), h = even(parse(heightField, plannedHeight));
        int dpi = parse(dpiField, getResources().getDisplayMetrics().densityDpi);
        int fps = parse(fpsField, 20), bitrate = parse(bitrateField, 2500000);
        if (w < 160 || h < 160 || w > 3840 || h > 2160) { status.setText("编码尺寸应在 160–3840 × 160–2160 像素范围内"); return false; }
        if (dpi < 72 || dpi > 1000) { status.setText("DPI 应在 72–1000 范围内"); return false; }
        if (fps < 1 || fps > 60) { status.setText("帧率应在 1–60 FPS 范围内"); return false; }
        if (bitrate < 100000 || bitrate > 50000000) { status.setText("码率应在 100000–50000000 bps 范围内"); return false; }
        preferences.edit().putString("width", Integer.toString(w)).putString("height", Integer.toString(h))
                .putString("dpi", Integer.toString(dpi)).putString("fps", Integer.toString(fps))
                .putString("bitrate", Integer.toString(bitrate)).apply();
        widthField.setText(Integer.toString(w));
        heightField.setText(Integer.toString(h));
        LogStore.add("TEST SETTINGS saved frame=" + w + "x" + h + " dpi=" + dpi + " fps=" + fps + " bitrate=" + bitrate);
        return true;
    }

    private void planSize() {
        try {
            Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
            android.graphics.Point p = new android.graphics.Point();
            display.getRealSize(p);
            CaptureSize capture = CaptureSize.fit(p.x, p.y);
            plannedWidth = even(parse(preferences.getString("width", Integer.toString(capture.width())), capture.width()));
            plannedHeight = even(parse(preferences.getString("height", Integer.toString(capture.height())), capture.height()));
            if (sizeLabel != null) sizeLabel.setText(String.format(Locale.ROOT,
                    "手机屏幕 %d×%d 像素；默认测试编码尺寸 %d×%d 像素（自动适配，不是车机要求）", p.x, p.y, plannedWidth, plannedHeight));
            if (widthField != null && !preferences.contains("width")) widthField.setText(Integer.toString(plannedWidth));
            if (heightField != null && !preferences.contains("height")) heightField.setText(Integer.toString(plannedHeight));
            LogStore.add("DISPLAY source=" + p.x + "x" + p.y + " test encoder=" + plannedWidth + "x" + plannedHeight);
        } catch (Exception e) {
            plannedWidth = 1280;
            plannedHeight = 720;
            if (sizeLabel != null) sizeLabel.setText("无法读取屏幕尺寸，测试默认 1280×720 像素；不是车辆要求");
            LogStore.add("DISPLAY size detection failed=" + e.getClass().getSimpleName());
        }
    }

    private int parse(EditText field, int fallback) {
        try { return Integer.parseInt(field.getText().toString().trim()); }
        catch (Exception ignored) { return fallback; }
    }
    private static int parse(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (Exception ignored) { return fallback; }
    }
    private static int even(int value) { return Math.max(2, value & ~1); }

    private void updateNetwork() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            String type = c != null && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : c != null && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "移动数据"
                    : c != null ? "其他网络" : "无网络";
            networkLabel.setText("当前网络：" + type + "；仅供自建接收端测试");
            LogStore.add("NETWORK current=" + type);
        } catch (Exception e) { networkLabel.setText("当前网络：无法读取"); }
    }

    private void refreshLog() { if (logView != null) logView.setText(LogStore.text()); }
    private void shareLog() {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "独立投屏诊断日志")
                .putExtra(Intent.EXTRA_TEXT, LogStore.text());
        startActivity(Intent.createChooser(share, "分享独立投屏诊断日志"));
    }
    private TextView section(String s) { return text(s, 18, Color.rgb(0, 80, 80)); }
    private Button button(String s) { Button b = new Button(this); b.setText(s); return b; }
    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }
    private TextView text(String s, int size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setPadding(0, 7, 0, 7);
        return v;
    }
}
