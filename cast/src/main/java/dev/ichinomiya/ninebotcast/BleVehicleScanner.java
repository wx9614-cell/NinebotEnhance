package dev.ichinomiya.ninebotcast;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Scans nearby BLE advertisements and reports likely Ninebot/Segway vehicles. */
public final class BleVehicleScanner {
    public interface Listener {
        void onState(String state);
        void onDevices(List<BluetoothDevice> devices);
    }

    private static final long SCAN_MS = 10000L;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    private Listener listener;
    private boolean scanning;

    public BleVehicleScanner(Context context) { this.context = context.getApplicationContext(); }

    public boolean hasPermission() {
        if (Build.VERSION.SDK_INT < 31) return check(Manifest.permission.ACCESS_FINE_LOCATION);
        return check(Manifest.permission.BLUETOOTH_SCAN) && check(Manifest.permission.BLUETOOTH_CONNECT);
    }

    private boolean check(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    public void start(Listener listener) {
        stop();
        this.listener = listener;
        if (!hasPermission()) { state("需要蓝牙扫描权限"); return; }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { state("手机不支持蓝牙"); return; }
        if (!adapter.isEnabled()) { state("请先开启蓝牙"); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { state("蓝牙扫描器不可用"); return; }
        found.clear(); scanning = true; state("正在自动检测九号车辆…"); publish();
        try { scanner.startScan(callback); }
        catch (RuntimeException e) { scanning = false; state("蓝牙扫描失败：" + e.getClass().getSimpleName()); return; }
        main.postDelayed(this::finish, SCAN_MS);
    }

    public void stop() {
        if (scanner != null && scanning && hasPermission()) {
            try { scanner.stopScan(callback); } catch (RuntimeException ignored) {}
        }
        scanning = false; scanner = null; main.removeCallbacks(this::finish);
    }

    private void finish() {
        if (!scanning) return;
        stop();
        state(found.isEmpty() ? "未发现九号/Segway 蓝牙设备" : "已发现蓝牙车辆，但投屏接收端尚未协商");
        publish();
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            BluetoothDevice device = result == null ? null : result.getDevice();
            if (device == null || !likelyVehicle(device, result)) return;
            String key;
            try { key = device.getAddress(); } catch (SecurityException e) { return; }
            found.put(key, device); publish();
        }
        @Override public void onScanFailed(int errorCode) { scanning = false; state("蓝牙扫描失败，错误码 " + errorCode); }
    };

    private boolean likelyVehicle(BluetoothDevice device, ScanResult result) {
        String name = null;
        try { name = device.getName(); } catch (SecurityException ignored) {}
        if (name == null && result != null && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
        if (name == null) return false;
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("ninebot") || value.contains("segway") || value.contains("九号") || value.contains("纳恩博");
    }

    private void publish() { if (listener != null) listener.onDevices(new ArrayList<>(found.values())); }
    private void state(String value) { if (listener != null) listener.onState(value); }
    public static String label(BluetoothDevice device) {
        try { return device.getName() + "\n" + device.getAddress(); }
        catch (SecurityException e) { return "九号车辆（蓝牙权限不足）"; }
    }
}
