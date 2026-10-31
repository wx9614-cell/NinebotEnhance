package dev.ichinomiya.ninebotcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

/** Self-contained projection, H.264 encoding and generic RTP/UDP transport service. */
public final class CastService extends Service {
    static final String ACTION_STOP = "dev.ichinomiya.ninebotcast.STOP";
    static final String EXTRA_RESULT = "result";
    static final String EXTRA_DATA = "data";

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay display;
    private MediaCodec codec;
    private RtpH264 rtp;
    private Thread worker;
    private volatile boolean running;

    @Override public int onStartCommand(Intent in, int flags, int id) {
        if (ACTION_STOP.equals(in.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) { LogStore.add("CAST 已在运行，忽略重复启动"); return START_NOT_STICKY; }
        try {
            int w = even(num(in, "width", 1280));
            int h = even(num(in, "height", 720));
            int fps = clamp(num(in, "fps", 20), 1, 60);
            int rate = clamp(num(in, "bitrate", 2500000), 100000, 50000000);
            String host = in.getStringExtra("host");
            int port = clamp(num(in, "port", 5004), 1, 65535);
            if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("未填写 UDP 目标地址");
            LogStore.add("CAST 初始化 frame=" + w + "x" + h + " dpi=" + num(in, "dpi", 160)
                    + " fps=" + fps + " bitrate=" + rate + " target=" + host + ":" + port);
            LogStore.add("CAST protocol=generic-H264-RTP-UDP; 九号车机协议未验证");
            createNotification();
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            if (manager == null) throw new IllegalStateException("系统屏幕录制服务不可用");
            Intent permission = in.getParcelableExtra(EXTRA_DATA);
            projection = manager.getMediaProjection(in.getIntExtra(EXTRA_RESULT, 0), permission);
            if (projection == null) throw new IllegalStateException("录屏授权无效");
            projectionCallback = new MediaProjection.Callback() {
                @Override public void onStop() {
                    LogStore.add("CAPTURE 系统回调：MediaProjection 已停止");
                    running = false;
                    stopSelf();
                }
            };
            projection.registerCallback(projectionCallback, new android.os.Handler(getMainLooper()));

            rtp = new RtpH264(host, port, 1200);
            codec = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", w, h);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, rate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            LogStore.add("CODEC configured type=video/avc color=surface");
            Surface surface = codec.createInputSurface();
            codec.start();
            display = projection.createVirtualDisplay("Ninebot Independent Cast", w, h, num(in, "dpi", 160),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
            if (display == null) throw new IllegalStateException("VirtualDisplay 创建失败");
            running = true;
            LogStore.add("CAPTURE running VirtualDisplay=" + w + "x" + h + "; 等待编码输出");
            worker = new Thread(this::drain, "cast-encoder");
            worker.start();
        } catch (Exception e) {
            LogStore.add("CAST 启动失败：" + e.getClass().getSimpleName() + " " + safeMessage(e));
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                int index = codec.dequeueOutputBuffer(info, 1000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    ByteBufferUtil.Config config = ByteBufferUtil.codecConfig(output);
                    if (config != null) rtp.setCodecConfig(config.data);
                    LogStore.add("CODEC output format=" + output);
                } else if (index >= 0) {
                    java.nio.ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        byte[] bytes = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.get(bytes);
                        rtp.sendAccessUnit(bytes, info.presentationTimeUs);
                    } else if (buffer != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        byte[] config = new byte[info.size];
                        buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(config);
                        rtp.setCodecConfig(config);
                    }
                    codec.releaseOutputBuffer(index, false);
                }
            }
        } catch (Exception e) {
            if (running) LogStore.add("CODEC/SEND error=" + e.getClass().getSimpleName() + " " + safeMessage(e));
        } finally {
            if (running) { running = false; LogStore.add("CAST 工作线程结束"); }
            stopSelf();
        }
    }

    private void createNotification() {
        String channel = "cast";
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(channel, "独立投屏", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("独立屏幕采集")
                .setContentText("正在发送通用 H.264/RTP UDP（未确认九号车机兼容）")
                .setOngoing(true).build();
        startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private static int num(Intent intent, String key, int fallback) {
        Object value = intent.getExtras() == null ? null : intent.getExtras().get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }
    private static int even(int value) { return Math.max(2, value & ~1); }
    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? "" : e.getMessage(); }

    @Override public void onDestroy() {
        running = false;
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        if (display != null) { display.release(); display = null; }
        if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); codec = null; }
        if (projection != null) {
            try { if (projectionCallback != null) projection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
            projection.stop(); projection = null; projectionCallback = null;
        }
        if (rtp != null) { rtp.close(); rtp = null; }
        LogStore.add("CAST 已停止并回收资源");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Tiny adapter to keep MediaFormat codec-config extraction explicit and testable. */
    private static final class ByteBufferUtil {
        static Config codecConfig(MediaFormat format) {
            java.nio.ByteBuffer csd0 = format.getByteBuffer("csd-0");
            java.nio.ByteBuffer csd1 = format.getByteBuffer("csd-1");
            if (csd0 == null) return null;
            byte[] first = new byte[csd0.remaining()]; csd0.get(first);
            if (csd1 == null) return new Config(first);
            byte[] second = new byte[csd1.remaining()]; csd1.get(second);
            byte[] merged = new byte[first.length + 4 + second.length];
            System.arraycopy(first, 0, merged, 0, first.length);
            int p = first.length; merged[p++] = 0; merged[p++] = 0; merged[p++] = 0; merged[p++] = 1;
            System.arraycopy(second, 0, merged, p, second.length);
            return new Config(merged);
        }
        static final class Config { final byte[] data; Config(byte[] data) { this.data = data; } }
    }
}
