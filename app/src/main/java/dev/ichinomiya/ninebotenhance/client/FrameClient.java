package dev.ichinomiya.ninebotenhance.client;

import dev.ichinomiya.ninebotenhance.core.HudPalette;

import dev.ichinomiya.ninebotenhance.core.DrawSettings;

import dev.ichinomiya.ninebotenhance.core.PictureSource;

import dev.ichinomiya.ninebotenhance.core.AppRecoveryState;
import dev.ichinomiya.ninebotenhance.core.DirectSession;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;
import dev.ichinomiya.ninebotenhance.core.FramePacer;
import dev.ichinomiya.ninebotenhance.core.DebugMode;
import dev.ichinomiya.ninebotenhance.core.CalibrationPattern;
import dev.ichinomiya.ninebotenhance.core.CaptureSize;
import dev.ichinomiya.ninebotenhance.core.PrivilegeMode;
import dev.ichinomiya.ninebotenhance.core.Geometry;
import dev.ichinomiya.ninebotenhance.core.KeyboardPolicy;
import dev.ichinomiya.ninebotenhance.core.PendingStops;
import dev.ichinomiya.ninebotenhance.core.PixelPacking;
import dev.ichinomiya.ninebotenhance.core.TireTelemetry;
import dev.ichinomiya.ninebotenhance.core.BatteryTelemetry;
import dev.ichinomiya.ninebotenhance.core.WidgetSettings;
import dev.ichinomiya.ninebotenhance.core.WidgetCondition;
import dev.ichinomiya.ninebotenhance.core.RegisterProbe;
import dev.ichinomiya.ninebotenhance.core.RideState;
import dev.ichinomiya.ninebotenhance.core.NaviUpdate;
import dev.ichinomiya.ninebotenhance.navi.NaviUpdates;
import dev.ichinomiya.ninebotenhance.diagnostics.Diagnostics;
import dev.ichinomiya.ninebotenhance.diagnostics.LogDigest;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.platform.AppCatalog;
import dev.ichinomiya.ninebotenhance.client.AppIconLoader;

import android.content.*;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.Surface;
import android.view.View;
import android.view.MotionEvent;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.ichinomiya.ninebotenhance.diagnostics.StreamStats;
import dev.ichinomiya.ninebotenhance.diagnostics.StreamOverlay;
import dev.ichinomiya.ninebotenhance.core.EncoderOverride;
import dev.ichinomiya.ninebotenhance.core.BmsCard;
import dev.ichinomiya.ninebotenhance.core.DashboardLayout;
import dev.ichinomiya.ninebotenhance.core.DashboardProfile;
import dev.ichinomiya.ninebotenhance.core.HiddenFeatures;
import dev.ichinomiya.ninebotenhance.diagnostics.EncodingDiagnostics;

/** Ninebot owns the consumer Surface: compositor -> RGBA -> original encoder. No JPEG IPC. */
public final class FrameClient {
    private final Handler main = new Handler(Looper.getMainLooper()), worker, controlWorker, metadataWorker, hudWorker;
    private final ServiceBridge bridge = new ServiceBridge(this::report);
    private final LogExporter logExporter = new LogExporter(bridge);
    private final AppIconLoader appIcons = new AppIconLoader(bridge);
    private final PendingStops pendingStops = new PendingStops();
    private final AtomicBoolean metadataBusy = new AtomicBoolean();
    private final AtomicInteger pendingInput = new AtomicInteger();
    private volatile WeakReference<View> inlinePreview = new WeakReference<>(null);
    private long lastControlError;
    private final Object frameLock = new Object();
    private final ArrayDeque<String> reports = new ArrayDeque<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayText = new Paint(Paint.ANTI_ALIAS_FLAG), overlayBack = new Paint();
    private record FrameStamp(int picture, long hud) {}
    private final Map<Bitmap, FrameStamp> supplied = new WeakHashMap<>();
    private final dev.ichinomiya.ninebotenhance.notification.DashboardHud hud = new dev.ichinomiya.ninebotenhance.notification.DashboardHud();
    private final TireTelemetry tires = new TireTelemetry();
    private final BatteryTelemetry battery = new BatteryTelemetry();
    private volatile java.util.function.BiConsumer<String, WidgetSettings> vehiclePulse = (vehicle, settings) -> {};
    private volatile Runnable vehicleStop = () -> {};
    /** Dashboard navigation test: scripted command-113 writes while a vehicle session runs, only when the user switch is on. */
    private volatile java.util.function.Consumer<String> naviTestPulse = vehicle -> {};
    private volatile Runnable naviTestStop = () -> {};
    private volatile boolean naviTest;
    /** Live turn-by-turn state relayed from a phone navigation app through the module service; polled once a second during a session. */
    private volatile java.util.function.BiConsumer<String, NaviUpdate> naviLivePulse = (vehicle, update) -> {};
    private volatile boolean naviLive = true;
    /** Dashboard theme chosen in the preview toolbar: the TFT day/night flag, the palette of the module's cards and the frame background. */
    private volatile boolean dashboardDark = true;
    private volatile java.util.function.BiConsumer<String, Boolean> themeSender = (vehicle, dark) -> {};
    private volatile NaviUpdate liveNavi;
    private volatile long liveNaviPolled, liveNaviLogged;
    private volatile java.util.function.Supplier<String> vehicleReadSummary = () -> "inactive";
    private volatile WidgetSettings widgets=WidgetSettings.DEFAULT;
    private volatile EncoderOverride encoderOverride=EncoderOverride.NONE;
    private volatile HiddenFeatures hiddenFeatures=HiddenFeatures.NONE;
    private volatile DashboardLayout dashboardLayout=DashboardLayout.DEFAULT;
    private volatile String layoutVehicle="";
    private volatile java.util.function.Consumer<EncoderOverride> overrideApplier=o->{};
    private volatile String compatibility="";
    private final RideState ride=new RideState();
    private final RegisterProbe probe=new RegisterProbe();private volatile java.util.Set<String> probeSelection=RegisterProbe.all();private volatile java.util.Set<String> probeRawModules=new java.util.LinkedHashSet<>();
    private final DebugMode debugMode = new DebugMode();
    private int pictureRevision;
    private volatile Bitmap calibration;
    private final String process;
    private Context context;
    private volatile String beginAccepted;
    private volatile String moduleDigest = "尚未取得模块进程日志", brokerIdentity = "尚未取得模块进程标识";
    private volatile DisplaySettings savedSettings = DisplaySettings.defaults();
    private volatile String savedApp = "";
    private volatile PrivilegeMode savedPrivilege = PrivilegeMode.ROOT;
    private volatile PictureSource savedSource = PictureSource.CAST;
    /** The running session's source: the capture is passed through, the drawn picture is painted here, the virtual display gets the cards. */
    private volatile boolean screenCapture, drawing;
    private volatile DrawSettings drawSettings = DrawSettings.DEFAULT;
    private final dev.ichinomiya.ninebotenhance.notification.DrawPanel drawPanel = new dev.ichinomiya.ninebotenhance.notification.DrawPanel();
    private volatile int appRecovery;
    private volatile String appRecoveryDetail = "";
    private long lastDiagnostics;
    private volatile String ownerRequest, state = "等待模块连接", summary = "等待业务类加载";
    private volatile String brokerStatus = "尚未取得模块会话状态";
    private volatile boolean active, displayReady;
    private volatile boolean previewRotated;
    /** A cast is bound to the running display: its frames go to the capture hooks and the dashboard senders run. */
    private volatile boolean casting;
    private volatile String castRequest;
    private volatile long lastPoll;
    private volatile DisplaySettings settings = DisplaySettings.defaults();
    private ImageReader reader;
    private final FramePacer framePacer = new FramePacer(); // Accessed only on the frame worker.
    private Runnable pendingImageRead;
    private ByteBuffer packed;
    private volatile Bitmap latest;
    private volatile long lastImage, replacements, earlyFrames, deduplicated;
    private long lastStats;
    private volatile StreamStats streamStats;
    private final EncodingDiagnostics encoding = new EncodingDiagnostics(this::report);
    private volatile String observedRequest;
    private final Object observationLock = new Object();
    private final dev.ichinomiya.ninebotenhance.core.TouchMarks touchMarks = new dev.ichinomiya.ninebotenhance.core.TouchMarks();
    private final Paint markFill = new Paint(Paint.ANTI_ALIAS_FLAG), markRing = new Paint(Paint.ANTI_ALIAS_FLAG), markOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetText = new Paint(Paint.ANTI_ALIAS_FLAG), targetHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    { markFill.setColor(Color.WHITE); markRing.setColor(Color.WHITE); markRing.setStyle(Paint.Style.STROKE); markOutline.setColor(Color.BLACK); markOutline.setStyle(Paint.Style.STROKE);
      targetText.setColor(Color.WHITE); targetHalo.setColor(Color.BLACK); targetHalo.setStyle(Paint.Style.STROKE); }
    /** The module reports whether a touch panel is bound and whether the daemon has it open right now; calibration runs from the preview toolbar while a session is live. */
    private volatile boolean touchBound, touchPresent, calibrating;
    private int calibrationStep;
    private final float[][] calibrationRaw = new float[dev.ichinomiya.ninebotenhance.core.TouchCalibration.TARGETS.length][];
    public boolean touchBound() { return touchBound; }
    public boolean touchPresent() { return touchPresent; }
    public boolean calibrating() { return calibrating; }
    /** The preview toolbar's calibration button: "取消校准" while a run is on, "校准" while the panel is present, nothing otherwise. */
    public String calibrationAction() { return calibrating ? "取消校准" : touchPresent ? "校准" : null; }
    /** Preview toolbar: start tapping the frame's targets on the panel, or abandon the run. */
    public void toggleTouchCalibration(String request) {
        if (!request.equals(ownerRequest) || !touchBound) return;
        boolean next;
        synchronized (touchMarks) { next = !calibrating; calibrating = next; calibrationStep = 0; }
        Bundle payload = new Bundle(); payload.putBoolean("calibrating", next);
        control(request, Protocol.TOUCH_CALIBRATE, null, payload);
        report("CALIBRATE " + (next ? "begin" : "cancel"));
        View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation();
    }
    private void acceptCalibrationSample(float[] raw) {
        String request = ownerRequest; if (request == null || raw == null || raw.length != 2) return;
        dev.ichinomiya.ninebotenhance.core.TouchCalibration solved = null; boolean failed = false; int taken;
        synchronized (touchMarks) {
            if (!calibrating) return;
            calibrationRaw[calibrationStep++] = raw; taken = calibrationStep;
            if (calibrationStep >= calibrationRaw.length) {
                try { solved = dev.ichinomiya.ninebotenhance.core.TouchCalibration.solve(calibrationRaw, dev.ichinomiya.ninebotenhance.core.TouchCalibration.TARGETS); calibrating = false; }
                catch (IllegalArgumentException e) { failed = true; report("CALIBRATE rejected: " + e.getMessage()); }
                calibrationStep = 0;
            }
        }
        report("CALIBRATE sample " + taken + " raw=" + raw[0] + "," + raw[1]);
        if (solved != null) {
            Bundle payload = new Bundle(); payload.putString("calibration", solved.encode());
            control(request, Protocol.TOUCH_CALIBRATION, null, payload);
            report("CALIBRATE solved " + solved.encode());
            main.post(() -> { if (context != null) android.widget.Toast.makeText(context, "校准完成", android.widget.Toast.LENGTH_SHORT).show(); });
        } else if (failed) main.post(() -> { if (context != null) android.widget.Toast.makeText(context, "校准失败，请重新点靶点", android.widget.Toast.LENGTH_SHORT).show(); });
        View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation();
    }
    /** Liveness for the module and the daemon; UI frames remain inside Ninebot. The daemon also posts the touch panel's contacts and calibration taps here. */
    private final IBinder owner = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(Protocol.DESCRIPTOR); return true; }
            if (code != Protocol.OWNER_TOUCH_MARKS && code != Protocol.OWNER_TOUCH_SAMPLE) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(Protocol.DESCRIPTOR);
            int caller = Binder.getCallingUid();
            if (caller != 2000 && caller != 0) throw new SecurityException("仅允许虚拟屏辅助进程");
            Bundle args = data.readBundle(getClass().getClassLoader());
            if (args != null && args.getString(Protocol.REQUEST, "").equals(ownerRequest)) {
                if (code == Protocol.OWNER_TOUCH_SAMPLE) acceptCalibrationSample(args.getFloatArray("raw"));
                else {
                    touchMarks.accept(args.getFloatArray("points"), SystemClock.elapsedRealtime());
                    View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation();
                }
            }
            reply.writeNoException(); reply.writeBundle(new Bundle()); return true;
        }
    };
    /** Rings at the panel's contacts (frame pixels), drawn over the frame after the HUD. */
    private void drawTouchMarks(Canvas canvas, int fittedWidth, long now) {
        float[] points = touchMarks.visible(now); DisplaySettings current = settings;
        if (points.length < 2 || current == null || current.width <= 0) return;
        float scale = fittedWidth / (float) current.width, alpha = touchMarks.alpha(now);
        float radius = dev.ichinomiya.ninebotenhance.core.TouchMarks.RADIUS * scale * (2f - alpha);
        markFill.setAlpha(Math.round(90 * alpha)); markRing.setAlpha(Math.round(235 * alpha)); markOutline.setAlpha(Math.round(130 * alpha));
        markRing.setStrokeWidth(3f * scale); markOutline.setStrokeWidth(5.5f * scale);
        for (int i = 0; i + 1 < points.length; i += 2) {
            float cx = points[i] * scale, cy = points[i + 1] * scale;
            canvas.drawCircle(cx, cy, radius, markFill); canvas.drawCircle(cx, cy, radius, markOutline); canvas.drawCircle(cx, cy, radius, markRing);
        }
    }
    /** The target to tap next while calibrating: a crosshair ring with its number, on both the frame and the phone preview. */
    private void drawCalibrationTarget(Canvas canvas, int fittedWidth, int fittedHeight) {
        if (!calibrating) return;
        int step; synchronized (touchMarks) { step = calibrationStep; }
        float[][] targets = dev.ichinomiya.ninebotenhance.core.TouchCalibration.TARGETS; DisplaySettings current = settings;
        float[] t = targets[Math.min(step, targets.length - 1)];
        float scale = fittedWidth / (float) Math.max(1, current == null ? fittedWidth : current.width);
        float cx = t[0] * fittedWidth, cy = t[1] * fittedHeight, r = 18f * scale, arm = 1.7f * r;
        markRing.setAlpha(255); markOutline.setAlpha(170); markRing.setStrokeWidth(2.5f * scale); markOutline.setStrokeWidth(5.5f * scale);
        for (Paint paint : new Paint[]{markOutline, markRing}) {
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawLine(cx - arm, cy, cx + arm, cy, paint); canvas.drawLine(cx, cy - arm, cx, cy + arm, paint);
        }
        String label = (step + 1) + " / " + targets.length;
        targetText.setTextSize(16f * scale); targetHalo.setTextSize(16f * scale); targetHalo.setStrokeWidth(3f * scale);
        float tx = t[0] < 0.5f ? cx + arm + 6f * scale : cx - arm - 6f * scale - targetText.measureText(label), ty = cy + targetText.getTextSize() / 3f;
        canvas.drawText(label, tx, ty, targetHalo); canvas.drawText(label, tx, ty, targetText);
    }
    public FrameClient(String process) {
        this.process = process;
        HandlerThread thread = new HandlerThread("Ninebot-VirtualFrames"); thread.start(); worker = new Handler(thread.getLooper());
        HandlerThread controls = new HandlerThread("Ninebot-VirtualInput"); controls.start(); controlWorker = new Handler(controls.getLooper());
        HandlerThread metadata = new HandlerThread("Ninebot-Metadata"); metadata.start(); metadataWorker = new Handler(metadata.getLooper());
        HandlerThread overlays = new HandlerThread("Ninebot-PhoneHud"); overlays.start(); hudWorker = new Handler(overlays.getLooper());
    }
    public void attach(Context app) {
        main.post(() -> {
            if (context != null) return;
            context = app.getApplicationContext() != null ? app.getApplicationContext() : app;
            try {
                SharedPreferences p = context.getSharedPreferences("dev.ichinomiya.ninebotenhance.cached_display", Context.MODE_PRIVATE);
                savedSettings = DisplaySettings.read(p::getInt);
                savedApp = p.getString(AppCatalog.SELECTED, "");
                savedPrivilege = PrivilegeMode.read(p.getString("privilege_mode", null), PrivilegeMode.ROOT);
                savedSource = PictureSource.read(p.getString(PictureSource.KEY, null), PictureSource.CAST);
            } catch (RuntimeException e) { report("SETTINGS cache " + Ipc.error(e)); }
            // Developer options live for one run of the host: the version taps, calibration, register probe and navigation test
            // all start hidden and off, whatever an earlier run left behind.
            debugMode.restore(false, false);
            try {
                SharedPreferences saved=context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE);
                widgets=WidgetSettings.migrate(saved.getInt("version",1),saved.getInt("mask",WidgetSettings.ALL),saved.getInt("tyre_interval",WidgetSettings.DEFAULT_TYRE_SECONDS),saved.getInt("voltage_interval_ms",saved.getInt("voltage_interval",1)*1000),saved.getInt("music_hide",WidgetSettings.DEFAULT_MUSIC_HIDE_SECONDS),saved.getInt("chart_seconds",WidgetSettings.DEFAULT_CHART_SECONDS),saved.getInt("hold_power",WidgetSettings.DEFAULT_HOLD_POWER),saved.getInt("hold_speed",WidgetSettings.DEFAULT_HOLD_SPEED),saved.getInt("speed_interval_ms",saved.getInt("speed_interval",1)*1000),saved.getInt("power_interval_ms",saved.getInt("power_interval",1)*1000),saved.getInt("hold_power_max",WidgetSettings.DEFAULT_HOLD_POWER_MAX),saved.getInt("hold_seconds",WidgetSettings.DEFAULT_HOLD_SECONDS),saved.getInt("speed_chart_seconds",WidgetSettings.DEFAULT_CHART_SECONDS),saved.getInt("power_chart_seconds",WidgetSettings.DEFAULT_CHART_SECONDS),WidgetSettings.parseOrder(saved.getString("widget_order","")),loadConditions(saved));
                widgets=widgets.with(WidgetSettings.REGISTER_PROBE,false);
                hud.setWidgets(widgets);
                drawSettings=DrawSettings.read(saved::getInt);
                encoderOverride=new EncoderOverride(saved.getInt("encoder_bitrate_kbps",0),saved.getInt("encoder_fps",0),saved.getBoolean("preview_stats",false),saved.getInt("encoder_frame_width",0),saved.getInt("encoder_frame_height",0));
                hiddenFeatures=new HiddenFeatures(saved.getBoolean("unhide_throttle",false),saved.getBoolean("unhide_hardkey",false),saved.getBoolean("unhide_cruise",false));
                naviTest=false;
                naviLive=saved.getBoolean("navi_live",true);
                bmsLayout=BmsCard.parse(saved.getString("bms_layout",""));hud.setBmsLayout(bmsLayout);
                dashboardDark=saved.getBoolean("dashboard_dark",true);hud.setDark(dashboardDark);
                java.util.Set<String> chosen=saved.getStringSet("probe_registers",null); if(chosen!=null) probeSelection=new java.util.LinkedHashSet<>(chosen);
                java.util.Set<String> raw=saved.getStringSet("probe_raw_modules",null); if(raw!=null) probeRawModules=new java.util.LinkedHashSet<>(raw);
            }
            catch (RuntimeException e) { report("WIDGET settings " + Ipc.error(e)); }
            bridge.attach(context); worker.post(poll); worker.post(hudPulse); hudWorker.post(hudPoll); controlWorker.post(stopRetry);
        });
    }
    public EncodingDiagnostics encoding() { return encoding; }
    public TireTelemetry tireData() { return tires; }
    public BatteryTelemetry batteryData() { return battery; }
    /** Both observers follow the same selected vehicle; a cast pins it until the session ends. */
    public void selectVehicle(String key) { tires.select(key); battery.select(key); metadataWorker.post(() -> loadDashboardLayout(key)); }
    /** Frame size and dashboard occlusions from the selected vehicle's cached cast configuration; the calibrated default until one is read. */
    public DashboardLayout dashboardLayout() { return dashboardLayout; }
    /** The composed frame: the user override when set, otherwise the size read from the cast configuration. */
    public int frameWidth() { EncoderOverride o = encoderOverride; return o.overridesFrame() ? o.frameWidth() : dashboardLayout.frameWidth(); }
    public int frameHeight() { EncoderOverride o = encoderOverride; return o.overridesFrame() ? o.frameHeight() : dashboardLayout.frameHeight(); }
    /** Portrait frames (half-screen dashboards) take the single-column HUD layout without dodge or dashboard occlusions. */
    public boolean halfScreen() { return dev.ichinomiya.ninebotenhance.core.SidebarLayout.halfScreen(frameWidth(), frameHeight()); }
    /** The frame's dashboard profile has a measured hill-hold toast to dodge; half-screen and seven-inch frames do not. */
    public boolean hillHoldSupported() { return DashboardProfile.of(frameWidth(), frameHeight()).hillHold(); }
    /** Ninebot caches the TFT board's configuration as {@code <SN>_screen_cast_config.nb} in its external files directory; read-only here. */
    private void loadDashboardLayout(String vehicle) {
        if (context == null || vehicle == null || vehicle.isEmpty()) return;
        try {
            java.io.File file = new java.io.File(context.getExternalFilesDir(null), vehicle + "_screen_cast_config.nb");
            if (!file.isFile() || file.length() > 262144) { if (!vehicle.equals(layoutVehicle)) report("LAYOUT no cached cast configuration for the selected vehicle; calibrated default"); layoutVehicle = vehicle; return; }
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            DashboardLayout parsed = DashboardLayout.parse(text);
            boolean changed = !parsed.equals(dashboardLayout) || !vehicle.equals(layoutVehicle);
            dashboardLayout = parsed; layoutVehicle = vehicle; hud.setOcclusions(parsed.referenceOcclusions());
            if (changed) {
                report("LAYOUT " + parsed.describe() + " profile=" + DashboardProfile.of(parsed.frameWidth(), parsed.frameHeight()).describe() + (dev.ichinomiya.ninebotenhance.core.SidebarLayout.halfScreen(parsed.frameWidth(), parsed.frameHeight()) ? " layout=half-screen" : "")
                        + (dev.ichinomiya.ninebotenhance.core.SidebarLayout.fits(parsed.frameWidth(), parsed.frameHeight()) ? "" : " cards=hidden (frame below " + Math.round(dev.ichinomiya.ninebotenhance.core.SidebarLayout.MIN_FIT * 100) + "% of the reference)"));
                View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation();
            }
        } catch (RuntimeException | java.io.IOException e) { report("LAYOUT unusable cast configuration " + Ipc.error(e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e))); }
    }
    /** Installed by the hook layer: pulse while a vehicle session may read, stop resets the read schedule. */
    public void setVehicleReader(java.util.function.BiConsumer<String, WidgetSettings> pulse, Runnable stop, java.util.function.Supplier<String> summary) { vehiclePulse = pulse; vehicleStop = stop; vehicleReadSummary = summary; }
    public void setNaviTest(java.util.function.Consumer<String> pulse, Runnable stop) { naviTestPulse = pulse; naviTestStop = stop; }
    public boolean naviTest(){return naviTest;}
    /** The BMS card layout lives with the other host-side widget settings. */
    private volatile BmsCard.Layout bmsLayout=BmsCard.DEFAULT;
    public BmsCard.Layout bmsLayout(){return bmsLayout;}
    public void saveBmsLayout(BmsCard.Layout value){
        bmsLayout=value;hud.setBmsLayout(value);
        if(context!=null)context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE).edit().putString("bms_layout",value.encode()).apply();
        report("BMS layout "+value.encode());View preview=inlinePreview.get();if(preview!=null)preview.postInvalidateOnAnimation();
    }
    public void setNaviLive(java.util.function.BiConsumer<String, NaviUpdate> pulse) { naviLivePulse = pulse; }
    /** Installed by the hook layer: (vehicle, dark) once per pulse during a vehicle session, null dark when the session ends. */
    public void setThemeSender(java.util.function.BiConsumer<String, Boolean> sender) { themeSender = sender; }
    public boolean naviLive(){return naviLive;}
    public NaviUpdate liveNavi(){NaviUpdate u=liveNavi;return u!=null&&u.fresh(SystemClock.elapsedRealtime())?u:null;}
    public void saveNaviLive(boolean value){
        naviLive=value;
        if(context!=null)context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE).edit().putBoolean("navi_live",value).apply();
        report("NAVI live switch "+(value?"on":"off"));
    }
    public void saveNaviTest(boolean value){
        naviTest=value;
        report("NAVITEST switch "+(value?"on":"off"));
    }
    /** The one-time open-source sentence lives with the other host-side settings, so it is asked once per Ninebot install. */
    public boolean noticeAccepted(){return context!=null&&context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE).getBoolean(dev.ichinomiya.ninebotenhance.core.OpenSourceNotice.KEY,false);}
    public void saveNoticeAccepted(){
        if(context!=null)context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE).edit().putBoolean(dev.ichinomiya.ninebotenhance.core.OpenSourceNotice.KEY,true).apply();
        report("NOTICE accepted");
    }
    public WidgetSettings widgetSettings(){return widgets;}
    public boolean dashboardDark(){return dashboardDark;}
    public void toggleDashboardTheme(){
        boolean dark=!dashboardDark;dashboardDark=dark;hud.setDark(dark);
        if(context!=null)context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE).edit().putBoolean("dashboard_dark",dark).apply();
        report("THEME "+(dark?"dark":"light"));
        if(casting)themeSender.accept(battery.selectedKey(),dark);
        View preview=inlinePreview.get();if(preview!=null)preview.postInvalidateOnAnimation();
    }
    /** Forced encoder bitrate / frame rate and the preview statistics switch; applied by the encoding hooks. */
    public EncoderOverride encoderOverride(){return encoderOverride;}
    /** Ninebot settings entries forced visible by the feature hooks. */
    public HiddenFeatures hiddenFeatures(){return hiddenFeatures;}
    public void saveHiddenFeatures(HiddenFeatures value){
        hiddenFeatures=value;
        if(context!=null)context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE).edit().putBoolean("unhide_throttle",value.throttle()).putBoolean("unhide_hardkey",value.hardkey()).putBoolean("unhide_cruise",value.cruise()).apply();
        report("FEATURE saved "+value.describe());
        main.post(dynamicPageListener);
    }
    /** Ninebot's vehicle-page view factory as observed by the feature hooks: the companion object, its generateView method and the page's device identity. */
    public record DynamicViewFactory(Object factory,java.lang.reflect.Method generate,int deviceId,String deviceTag){
        public View create(android.view.ViewGroup parent,String type,String json)throws ReflectiveOperationException{return (View)generate.invoke(factory,parent,type,json,deviceId,deviceTag);}
    }
    private volatile DynamicViewFactory dynamicViewFactory;
    private volatile Runnable dynamicPageListener=()->{};
    /** The module relayed the daemon's injection refusal; each distinct message is handed to the host UI once, on the main thread. */
    private volatile java.util.function.Consumer<String> inputDeniedListener=error->{};
    private String inputDeniedNotified="";
    public DynamicViewFactory dynamicViewFactory(){return dynamicViewFactory;}
    public void setDynamicPageListener(Runnable listener){dynamicPageListener=listener==null?()->{}:listener;}
    public void setInputDeniedListener(java.util.function.Consumer<String> listener){inputDeniedListener=listener==null?error->{}:listener;}
    public void dynamicViewFactory(DynamicViewFactory value){
        DynamicViewFactory previous=dynamicViewFactory;dynamicViewFactory=value;
        if(previous==null||previous.deviceId()!=value.deviceId()||!previous.deviceTag().equals(value.deviceTag())){report("FEATURE page factory deviceId="+value.deviceId());main.post(dynamicPageListener);}
    }
    public void setEncoderOverrideApplier(java.util.function.Consumer<EncoderOverride> applier){overrideApplier=applier;}
    public void saveEncoderOverride(EncoderOverride value){
        encoderOverride=value;
        if(context!=null)context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE).edit().putInt("encoder_bitrate_kbps",value.bitrateKbps()).putInt("encoder_fps",value.fps()).putBoolean("preview_stats",value.previewStats()).putInt("encoder_frame_width",value.frameWidth()).putInt("encoder_frame_height",value.frameHeight()).apply();
        report("OVERRIDE saved "+value.describe());
        try{overrideApplier.accept(value);}catch(RuntimeException e){report("OVERRIDE apply "+Ipc.error(e));}
        View preview=inlinePreview.get();if(preview!=null)preview.postInvalidateOnAnimation();
    }
    /** Target frame rate shown on the preview: the override when set, otherwise the accepted encoder configuration. */
    public Double targetFps(){EncoderOverride o=encoderOverride;return o.overridesFps()?Double.valueOf(o.fps()):encoding.targetFps();}
    /** Speed and motor power polled by the vehicle hooks; the HUD infers hill hold from them. */
    public RideState rideData(){return ride;}
    /** Debug register probe: the value table drawn by the HUD and the registers the vehicle hooks poll. */
    public RegisterProbe registerProbe(){return probe;}
    public java.util.Set<String> probeSelection(){return probeSelection;}
    public void saveProbeSelection(java.util.Set<String> names){if(context==null)return;java.util.Set<String> copy=new java.util.LinkedHashSet<>(names);context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE).edit().putStringSet("probe_registers",copy).apply();probeSelection=copy;}
    /** Boards whose full index range the probe reads through injected commands; empty by default. */
    public java.util.Set<String> probeRawModules(){return probeRawModules;}
    public void saveProbeRawModules(java.util.Set<String> modules){if(context==null)return;java.util.Set<String> copy=new java.util.LinkedHashSet<>(modules);context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE).edit().putStringSet("probe_raw_modules",copy).apply();probeRawModules=copy;}
    /** Outcome of the hook catalog check, filled once the target application is attached. */
    public String compatibility(){return compatibility;}
    private volatile String targetVersion="";private volatile boolean versionTested=true;
    /** The running Ninebot build as "name (code)" and whether its hook targets were verified by hand. */
    public void targetVersion(String name,long code,boolean tested){targetVersion=name+" ("+code+")";versionTested=tested;}
    public String targetVersion(){return targetVersion;}
    public boolean versionTested(){return versionTested;}
    public void compatibility(String text){compatibility=text==null?"":text;}
    private static java.util.Map<Integer,WidgetCondition> loadConditions(SharedPreferences saved){
        java.util.LinkedHashMap<Integer,WidgetCondition> map=new java.util.LinkedHashMap<>();
        for(int w:WidgetSettings.CONDITIONAL){String text=saved.getString("condition_"+w,null);if(text!=null)map.put(w,WidgetCondition.parse(text));}
        return map;
    }
    public void saveWidgetSettings(WidgetSettings settings){
        if(context==null)return;
        SharedPreferences.Editor editor=context.getSharedPreferences(Protocol.MODULE+".widgets",Context.MODE_PRIVATE).edit().putInt("version",WidgetSettings.PREFERENCE_VERSION).putInt("mask",settings.mask()).putInt("tyre_interval",settings.tyreIntervalSeconds()).putInt("voltage_interval_ms",settings.voltageIntervalMs()).putInt("music_hide",settings.musicHideSeconds()).putInt("chart_seconds",settings.chartSeconds()).putInt("hold_power",settings.holdPowerMin()).putInt("hold_speed",settings.holdSpeedMax()).putInt("speed_interval_ms",settings.speedIntervalMs()).putInt("power_interval_ms",settings.powerIntervalMs()).putInt("hold_power_max",settings.holdPowerMax()).putInt("hold_seconds",settings.holdSeconds()).putInt("speed_chart_seconds",settings.speedChartSeconds()).putInt("power_chart_seconds",settings.powerChartSeconds()).putString("widget_order",settings.encodeOrder());
        for(int w:WidgetSettings.CONDITIONAL){WidgetCondition c=settings.conditions().get(w);if(c==null)editor.remove("condition_"+w);else editor.putString("condition_"+w,c.encode());}
        editor.apply();
        widgets=settings;hud.setWidgets(settings);View preview=inlinePreview.get();if(preview!=null)preview.postInvalidateOnAnimation();
    }
    /** A cast attempt opens its encoding diagnostics before the vehicle check, so the cruise start is captured. */
    public void beginCastObservations(String castRequest) {
        synchronized (observationLock) {
            if (castRequest.equals(observedRequest)) return;
            observedRequest = castRequest;
            streamStats = new StreamStats(SystemClock.elapsedRealtime());
            encoding.begin(castRequest, true, SystemClock.elapsedRealtime());
        }
    }
    private void endCastObservations(String castRequest) {
        synchronized (observationLock) {
            if (!castRequest.equals(observedRequest)) return;
            encoding.stop(castRequest, SystemClock.elapsedRealtime(), statistics());
            if (streamStats != null) streamStats.stop(SystemClock.elapsedRealtime());
        }
    }
    /** Binds a cast to the running display: from now on the capture hooks get its frames and the dashboard senders run. */
    public boolean attachCast(String displayRequest, String castRequest) {
        synchronized (frameLock) {
            if (!displayRequest.equals(ownerRequest) || !active) return false;
            this.castRequest = castRequest; casting = true;
        }
        // The vehicle being cast is pinned now, not when the display was started.
        tires.beginSession(); battery.beginSession();
        report("CAST attached " + castRequest + " to display " + displayRequest);
        View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation();
        return true;
    }
    /** The cast lets go; the display and its frames continue for the phone preview. */
    public void detachCast(String castRequest) {
        boolean owned;
        synchronized (frameLock) { owned = castRequest.equals(this.castRequest); if (owned) { casting = false; this.castRequest = null; } }
        endCastObservations(castRequest);
        if (!owned) return;
        themeSender.accept("", null); naviTestStop.run();
        report("CAST detached " + castRequest);
        View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation();
    }
    public boolean casting() { return casting; }
    public void startDirect(String request, Consumer<String> done) { startDirect(request, null, done); }
    /** Starts the display session; {@code activity} hosts the recording consent in recording mode and is otherwise unused. */
    public void startDirect(String request, Activity activity, Consumer<String> done) {
        tires.beginSession(); battery.beginSession(); hud.reset(request); probe.clear(); ride.clear(); hud.acceptTires(tires.snapshot()); hud.acceptBattery(battery.snapshot());
        touchMarks.clear(); synchronized (touchMarks) { calibrating = false; calibrationStep = 0; }
        ownerRequest = request; active = true; displayReady = false; previewRotated = false; touchPresent = false; state = "正在连接虚拟屏";
        appRecovery = AppRecoveryState.HIDDEN; appRecoveryDetail = "";
        beginAccepted = null; lastPoll = 0; bridge.ensure();
        AtomicBoolean completed = new AtomicBoolean();
        main.postDelayed(() -> {
            if (completed.compareAndSet(false, true) && request.equals(ownerRequest)) {
                report("VD BEGIN timed out before acknowledgement"); stopDirect(request);
                done.accept("模块服务连接或启动请求超时，请在设置中查看日志");
            }
        }, 15000);
        worker.post(new Runnable() {
            @Override public void run() {
                if (completed.get() || !request.equals(ownerRequest)) return;
                if (!bridge.connected() || pendingStops.size() > 0) {
                    bridge.ensure(); controlWorker.post(FrameClient.this::flushStops);
                    state = "正在等待模块服务连接及上一会话清理"; worker.postDelayed(this, 150); return;
                }
                try {
                    Bundle config = bridge.call(Protocol.SETTINGS, new Bundle());
                    DisplaySettings value = Ipc.settings(config); String selected = config.getString(AppCatalog.SELECTED, "");
                    cachePrivilege(config); applyBmsConfig(config);
                    PictureSource source = savedSource;
                    screenCapture = source.captures(); drawing = source.draws();
                    if (!source.virtual() && activity == null)
                        throw new IllegalArgumentException("投屏和绘制只用于车辆投屏");
                    if (source.virtual() && selected.isEmpty()) throw new IllegalArgumentException("请先在设置中选择启动应用并保存");
                    if (completed.get() || !request.equals(ownerRequest)) return;
                    loadDashboardLayout(battery.selectedKey());
                    settings = value.withFrame(frameWidth(), frameHeight()); cacheSettings(value, selected); closeFrames();
                    if (settings != value) report("LAYOUT frame " + settings.width + "x" + settings.height + " from the cast configuration");
                    renderPlan = null;
                    int width = settings.virtualWidth, height = settings.virtualHeight;
                    if (!screenCapture && settings.keepPhoneDpi) {
                        // Keep-DPI: the display is created at the phone's density and the plan's logical size and the capture path
                        // scales it back.
                        DisplaySettings.RenderPlan plan = settings.renderPlan(phoneDensityDpi());
                        if (plan != null) { renderPlan = plan; width = plan.width(); height = plan.height(); report("RENDER plan " + width + "x" + height + "@" + plan.dpi() + " scaled into " + settings.virtualWidth + "x" + settings.virtualHeight); }
                    }
                    if (screenCapture) {
                        Rect bounds = activity.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
                        CaptureSize capture = CaptureSize.fit(bounds.width(), bounds.height()); width = capture.width(); height = capture.height();
                        state = "正在准备系统录屏授权";
                    }
                    Surface surface = null;
                    if (!drawing) {
                        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
                        packed = ByteBuffer.allocateDirect((screenCapture ? width * height : settings.width * settings.height) * 4);
                        ImageReader current = reader; current.setOnImageAvailableListener(image -> scheduleImage(request, image), worker);
                        surface = current.getSurface();
                        try { surface.setFrameRate(FramePacer.TARGET_FPS, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT); }
                        catch (RuntimeException e) { report("RGBA surface frame-rate hint unavailable " + Ipc.error(e)); }
                        report("RGBA pacing targetFps=" + FramePacer.TARGET_FPS + " intervalMs=" + FramePacer.INTERVAL_MS + " policy=defer-latest");
                    } else { state = "正在启动绘制"; report("DRAW frame " + settings.width + "x" + settings.height + " " + drawSettings.label()); }
                    if (completed.get() || !request.equals(ownerRequest)) { closeFrames(); return; }
                    Bundle args = Ipc.request(request); Ipc.settings(args, settings); if (surface != null) args.putParcelable("surface", surface); args.putBinder("owner", owner);
                    args.putString(PictureSource.KEY, source.name());
                    args.putString(AppCatalog.SELECTED, selected);
                    args.putBoolean(Protocol.SCREEN_CAPTURE, screenCapture);
                    args.putInt(Protocol.CAPTURE_WIDTH, width); args.putInt(Protocol.CAPTURE_HEIGHT, height);
                    if (renderPlan != null) args.putInt("render_dpi", renderPlan.dpi());
                    Bundle status = bridge.call(Protocol.BEGIN, args);
                    if (completed.get() || !request.equals(ownerRequest)) { stopDirect(request); return; }
                    beginAccepted = request; acceptStatus(request, status);
                    hud.accept(request, status.getBundle("hud"), SystemClock.elapsedRealtime());
                    PendingIntent consent = Ipc.parcelable(status, Protocol.CAPTURE_CONSENT, PendingIntent.class);
                    main.post(() -> {
                        if (!completed.compareAndSet(false, true) || !request.equals(ownerRequest)) return;
                        try {
                            if (screenCapture) {
                                if (consent == null || activity == null || activity.isFinishing() || activity.isDestroyed())
                                    throw new IllegalStateException("录屏授权入口已失效，请重新开始投屏");
                                activity.startIntentSenderForResult(consent.getIntentSender(), -1, null, 0, 0, 0, Ipc.launchOptions());
                            }
                            done.accept(null);
                        } catch (Exception e) { stopDirect(request); done.accept(Ipc.error(e)); }
                    });
                } catch (Exception e) {
                    String error = Ipc.error(e); report("VD BEGIN " + error); stopDirect(request);
                    if (completed.compareAndSet(false, true)) main.post(() -> done.accept(error));
                }
            }
        });
    }
    private void scheduleImage(String request, ImageReader source) {
        if (source != reader || !request.equals(ownerRequest)) return;
        FramePacer.Ticket ticket = framePacer.schedule(SystemClock.elapsedRealtime());
        if (ticket == null) return; // Coalesce callbacks into one pending read; retain no acquired Image.
        Runnable read = () -> {
            if (!framePacer.dispatch(ticket)) return; // Closed/replaced reader or cancelled session.
            pendingImageRead = null;
            readImage(request, source);
        };
        pendingImageRead = read;
        if (ticket.delayMs == 0) read.run();
        else worker.postDelayed(read, ticket.delayMs);
    }
    private void readImage(String request, ImageReader source) {
        if (source != reader || !request.equals(ownerRequest)) return;
        StreamStats samples = streamStats;
        try (Image image = source.acquireLatestImage()) {
            if (image == null || source != reader || !request.equals(ownerRequest)) return;
            long now = SystemClock.elapsedRealtime();
            Image.Plane plane = image.getPlanes()[0];
            int width=source.getWidth(),height=source.getHeight();
            int frameWidth=screenCapture?width:settings.width,frameHeight=screenCapture?height:settings.height;
            Bitmap bitmap;
            if (renderPlan != null && !screenCapture) bitmap = composeScaled(plane, width, height, frameWidth, frameHeight);
            else {
                PixelPacking.compose(plane.getBuffer(),plane.getRowStride(),plane.getPixelStride(),width,height,packed,frameWidth,frameHeight,frameHeight-settings.bottomInset-height,settings.background(dashboardDark),false);
                bitmap = Bitmap.createBitmap(frameWidth,frameHeight,Bitmap.Config.ARGB_8888); bitmap.copyPixelsFromBuffer(packed);
            }
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            if(lastImage==0)report("RGBA first frame "+frameWidth+"x"+frameHeight+" content="+width+"x"+height+" position=0,"+(frameHeight-height)+" source="+(screenCapture?"MediaProjection":"VirtualDisplay"));
            synchronized (frameLock) { latest = bitmap; lastImage = now; }
            framePacer.captured(now);
            if (samples != null) samples.captured(now);
            View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation();
        } catch (IllegalStateException e) { /* reader closed by cancellation */ }
        catch (RuntimeException e) { report("RGBA " + Ipc.error(e)); }
    }
    public void attachInline(String request, View view) {
        if (!request.equals(ownerRequest)) return;
        inlinePreview = new WeakReference<>(view); view.postInvalidateOnAnimation();
    }
    public void detachInline(View view) { if (inlinePreview.get() == view) inlinePreview.clear(); }
    public DisplaySettings displaySettings() { return settings; }
    public StreamStats.Snapshot statistics() { StreamStats value=streamStats;return value==null?null:value.snapshot(SystemClock.elapsedRealtime()); }
    public StreamStats transportStats() { return ready() && casting ? streamStats : null; }
    public boolean previewRotated(String request) { return request.equals(ownerRequest) && previewRotated; }
    public void setPreviewRotated(String request, boolean value) { if (request.equals(ownerRequest)) previewRotated = value; }
    public void drawInline(String request, Canvas canvas, int width, int height, boolean rotated) {
        canvas.drawColor(Color.BLACK);
        // Published frames are never mutated/recycled; the UI must not wait for encoder scaling under frameLock.
        if (request.equals(ownerRequest) && drawing && ready() && !debugMode.enabled() && width >= 1 && height >= 1) {
            int save = canvas.save();
            if (rotated) { canvas.translate(width, 0); canvas.rotate(90); int swap = width; width = height; height = swap; }
            float[] r = Geometry.fit(settings.width, settings.height, width, height);
            canvas.translate(r[0], r[1]); float scale = (r[2] - r[0]) / settings.width; canvas.scale(scale, scale);
            drawPanel.draw(canvas, settings.width, settings.height, HudPalette.of(dashboardDark), settings.background(dashboardDark), drawSettings, drawValues(SystemClock.elapsedRealtime()));
            canvas.restoreToCount(save);
            if (encoderOverride.previewStats()) drawStatistics(canvas, r[0], r[1]);
            return;
        }
        Bitmap snapshot = request.equals(ownerRequest) && ready() ? (debugMode.enabled() ? calibration : latest) : null;
        if (snapshot == null || width < 1 || height < 1) return;
        int save = canvas.save();
        if (rotated) { canvas.translate(width, 0); canvas.rotate(90); int swap = width; width = height; height = swap; }
        float[] r = Geometry.fit(snapshot.getWidth(), snapshot.getHeight(), width, height);
        canvas.drawBitmap(snapshot, null, new RectF(r[0], r[1], r[2], r[3]), paint);
        if (!debugMode.enabled() && savedSource.virtual()) {
            int overlaySave = canvas.save(); canvas.translate(r[0], r[1]);
            hud.draw(canvas, Math.round(r[2]-r[0]), Math.round(r[3]-r[1]), SystemClock.elapsedRealtime());
            drawTouchMarks(canvas, Math.round(r[2]-r[0]), SystemClock.elapsedRealtime());
            drawCalibrationTarget(canvas, Math.round(r[2]-r[0]), Math.round(r[3]-r[1]));
            // Without a cast the preview shows, on top of everything, what the vehicle dashboard itself paints over the frame.
            if (!casting && !dev.ichinomiya.ninebotenhance.core.SidebarLayout.halfScreen(Math.round(r[2]-r[0]), Math.round(r[3]-r[1]))) dev.ichinomiya.ninebotenhance.notification.DashboardOcclusion.draw(canvas, Math.round(r[2]-r[0]), Math.round(r[3]-r[1]), hud.hillHold(SystemClock.elapsedRealtime()), hud.occlusions());
            canvas.restoreToCount(overlaySave);
        }
        if (!debugMode.enabled() && encoderOverride.previewStats()) drawStatistics(canvas, r[0], r[1]);
        canvas.restoreToCount(save);
        // A phone repaint is not an encoder capture: do not advance replacement counters.
    }
    /** Phone-only statistics panel at the top-left of the picture; drawn after the HUD so it never enters an encoded frame. */
    private void drawStatistics(Canvas canvas, float left, float top) {
        java.util.List<String> lines = StreamOverlay.lines(statistics(), targetFps(), casting);
        float density = context == null ? 2.5f : context.getResources().getDisplayMetrics().density;
        overlayText.setColor(Color.WHITE); overlayText.setTextSize(11 * density); overlayBack.setColor(0xA0000000);
        float pad = 5 * density, lineHeight = overlayText.getFontSpacing(), width = 0;
        for (String line : lines) width = Math.max(width, overlayText.measureText(line));
        float x = left + 4 * density, y = top + 4 * density;
        canvas.drawRoundRect(new RectF(x, y, x + width + pad * 2, y + lineHeight * lines.size() + pad * 2), 4 * density, 4 * density, overlayBack);
        float baseline = y + pad - overlayText.ascent();
        for (String line : lines) { canvas.drawText(line, x + pad, baseline, overlayText); baseline += lineHeight; }
    }
    public void back(String request) { control(request, Protocol.UI_BACK, null); }
    public boolean screenCapture() { return screenCapture; }
    public Bundle hudTouch(String request,float x,float y){return readyFor(request)&&!debugMode.enabled()&&savedSource.virtual()?hud.touch(x,y,settings.width,settings.height,SystemClock.elapsedRealtime()):null;}
    public int appRecoveryFor(String request) {
        return !debugMode.enabled() && captureActiveFor(request) && displayReady ? appRecovery : AppRecoveryState.HIDDEN;
    }
    public String appRecoveryDetail() { return appRecoveryDetail; }
    public void restartApp(String request) {
        if (appRecoveryFor(request) == AppRecoveryState.MISSING) control(request, Protocol.UI_RESTART_APP, null);
    }
    /** Takes ownership of an already mapped event, including on failure or stale-session paths. */
    public void input(String request, MotionEvent event) { control(request, Protocol.UI_INPUT, event); }
    public void text(String request, String value) {
        try { KeyboardPolicy.text(value); } catch (IllegalArgumentException e) { return; }
        if (value.isEmpty()) return;
        Bundle args = new Bundle(); args.putString("text", value); control(request, Protocol.UI_TEXT, null, args);
    }
    public void deleteText(String request, int before, int after) {
        KeyboardPolicy.deletion(before, after); Bundle args = new Bundle(); args.putInt("before", before); args.putInt("after", after);
        control(request, Protocol.UI_DELETE, null, args);
    }
    public void typingKey(String request, android.view.KeyEvent event) {
        if (!KeyboardPolicy.key(event.getKeyCode()) || (event.getAction() != 0 && event.getAction() != 1)) return;
        Bundle args = new Bundle(); args.putInt("key", event.getKeyCode()); args.putInt("action", event.getAction()); args.putInt("meta", event.getMetaState());
        control(request, Protocol.UI_TYPING_KEY, null, args);
    }
    private void control(String request, int code, MotionEvent event) {
        control(request, code, event, new Bundle());
    }
    private void control(String request, int code, MotionEvent event, Bundle payload) {
        if (!savedSource.virtual() || !request.equals(ownerRequest) || (event != null && event.getActionMasked() == MotionEvent.ACTION_MOVE && pendingInput.get() >= 3)) {
            if (event != null) event.recycle(); return;
        }
        pendingInput.incrementAndGet();
        controlWorker.post(() -> {
            try {
                if (!request.equals(ownerRequest)) return;
                Bundle args = new Bundle(payload); args.putString(Protocol.REQUEST, request); if (event != null) args.putParcelable("event", event);
                bridge.call(code, args);
            } catch (Exception e) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastControlError > 5000) {
                    lastControlError = now; report("INLINE CONTROL " + Ipc.error(e));
                    String detail = Ipc.error(e);
                    main.post(() -> { if (request.equals(ownerRequest)) dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(inlinePreview.get(), "虚拟屏操作失败\n" + detail); });
                }
            } finally { pendingInput.decrementAndGet(); if (event != null) event.recycle(); }
        });
    }
    private void closeFrames() {
        if (pendingImageRead != null) { worker.removeCallbacks(pendingImageRead); pendingImageRead = null; }
        framePacer.reset();
        if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); reader = null; }
        packed = null;
        synchronized (frameLock) { latest = null; calibration = null; lastImage = 0; supplied.clear(); }
        View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation();
    }
    /** Whether one of the host's screens is visible; the module holds its lamp and BMS links only while it is or a session runs. */
    private volatile boolean hostVisible;
    public void setHostVisible(boolean visible) {
        if (hostVisible == visible) return;
        hostVisible = visible; report("HOST " + (visible ? "visible" : "hidden"));
        if (context == null) return;
        // Tell the module now rather than on the next scheduled poll; running it on the worker keeps a single poll loop.
        worker.post(() -> { worker.removeCallbacks(poll); poll.run(); });
    }
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            synchronized (observationLock) { encoding.tick(SystemClock.elapsedRealtime(), statistics()); }
            try {
                if (bridge.connected()) {
                    Bundle args = new Bundle(); args.putBoolean("foreground", hostVisible);
                    Bundle status = bridge.call(Protocol.READ, args);
                    observeBroker(status);
                    String request = ownerRequest;
                    if (request != null && request.equals(beginAccepted)) {
                        acceptStatus(request, status);
                        if (!active) hud.reset();
                    }
                    refreshCalibration();
                    for (int i = 0; i < 8; i++) {
                        String message; synchronized (reports) { message = reports.pollFirst(); } if (message == null) break;
                        sendReport(process + " " + message);
                    }
                    if (active && SystemClock.elapsedRealtime() - lastStats > 3000) {
                        sendReport("STAT " + summary + " replaced=" + replacements + " early=" + earlyFrames + " dedup=" + deduplicated);
                        lastStats = SystemClock.elapsedRealtime();
                    }
                    if (SystemClock.elapsedRealtime() - lastDiagnostics > 10000 && !metadataBusy.get()) {
                        lastDiagnostics = SystemClock.elapsedRealtime(); refreshModuleDiagnostics();
                    }
                } else { bridge.ensure(); if (ownerRequest != null) state = "服务暂时断开，正在自动重连"; }
            } catch (Exception e) { state = "模块连接异常：" + Ipc.error(e); report(state); }
            // Hidden and idle, the host has nothing to learn from the module every second; a visibility change polls at once.
            worker.postDelayed(this, active ? 300 : hostVisible ? 1000 : 3000);
        }
    };
    private void acceptStatus(String request, Bundle status) {
        if (!request.equals(ownerRequest)) return;
        observeBroker(status);
        String observed = "active=" + status.getBoolean("active") + " ready=" + status.getBoolean("ready")
                + " displayId=" + status.getInt("displayId", -1) + " " + status.getString("state", "")
                + " appRecovery=" + status.getInt(Protocol.APP_RECOVERY)
                + " appLayout=" + status.getString(Protocol.APP_LAYOUT_POLICY, "unreported") + " backend=" + status.getString("backend", "unreported");
        if (!observed.equals(brokerStatus)) { brokerStatus = observed; report("VD STATUS " + observed); }
        if (request.equals(status.getString(Protocol.REQUEST))) {
            active = status.getBoolean("active"); displayReady = status.getBoolean("ready"); state = status.getString("state", "");
            appRecovery = status.getInt(Protocol.APP_RECOVERY); appRecoveryDetail = status.getString(Protocol.APP_RECOVERY_DETAIL, "");
            touchBound = status.getBoolean("touch_bound"); touchPresent = status.getBoolean("touch_present");
            String denied = status.getString("input_denied", "");
            if (!denied.isEmpty() && !denied.equals(inputDeniedNotified)) { inputDeniedNotified = denied; report("INPUT denied: " + denied); main.post(() -> inputDeniedListener.accept(denied)); }
        } else {
            active = displayReady = false; state = "模块服务已重启或会话已失效，请重新开始投屏";
            appRecovery = AppRecoveryState.HIDDEN; appRecoveryDetail = ""; touchBound = touchPresent = false;
            report("BRIDGE requested session absent after reconnect");
        }
        lastPoll = SystemClock.elapsedRealtime();
        if (active && displayReady && screenCapture && reader != null) resizeRecording(request, status);
        if (!active && reader != null) closeFrames();
    }
    private final Runnable hudPulse = new Runnable() {
        @Override public void run() {
            if (active) {
                hud.acceptTires(tires.snapshot()); hud.acceptBattery(battery.snapshot());
                WidgetSettings current = widgets;
                hud.acceptProbe(current.enabled(WidgetSettings.REGISTER_PROBE) ? probe.snapshot(probeSelection, probeRawModules) : null);
                hud.acceptRide(ride.snapshot());
                // Local simulation reads too when the vehicle happens to be connected, so reads can be checked without casting.
                // The drawn picture reads what its gauge and fields show; the passed-through capture reads nothing.
                WidgetSettings reads = drawing ? drawReads(current) : current;
                if (!screenCapture && (reads.readsTyres() || reads.readsVoltage() || reads.readsSpeed() || reads.readsPower() || reads.enabled(WidgetSettings.REGISTER_PROBE))) vehiclePulse.accept(battery.selectedKey(), reads);
                else vehicleStop.run();
                if (casting) themeSender.accept(battery.selectedKey(), dashboardDark);
                if (casting && naviTest) naviTestPulse.accept(battery.selectedKey());
                else {
                    NaviUpdate live = liveNavi;
                    if (casting && naviLive && live != null && live.fresh(SystemClock.elapsedRealtime())) naviLivePulse.accept(battery.selectedKey(), live);
                    else naviTestStop.run();
                }
            } else { vehicleStop.run(); naviTestStop.run(); }
            View preview = inlinePreview.get();
            if (preview != null && active && !debugMode.enabled()) preview.postInvalidateOnAnimation();
            worker.postDelayed(this, active ? 50 : 1000);
        }
    };
    private final Runnable hudPoll = new Runnable() {
        @Override public void run() {
            String request = ownerRequest;
            if (request != null && request.equals(beginAccepted) && active && bridge.connected()) {
                try {
                    Bundle args = Ipc.request(request); hud.request(args);
                    Bundle result = bridge.call(Protocol.HUD_SNAPSHOT, args);
                    hud.accept(request, result.getBundle("hud"), SystemClock.elapsedRealtime());
                    long now = SystemClock.elapsedRealtime();
                    if (naviLive && now - liveNaviPolled >= 1000) {
                        liveNaviPolled = now;
                        NaviUpdate update = NaviUpdates.fromBundle(bridge.call(Protocol.NAVI_SNAPSHOT, new Bundle()));
                        liveNavi = update;
                        if (update != null && update.fresh(now) && now - liveNaviLogged >= 30000) { liveNaviLogged = now; report("NAVI live " + update.describe()); }
                    }
                } catch (Exception ignored) { /* Capture and control must remain independent of phone metadata. */ }
            }
            hudWorker.postDelayed(this, active ? 200 : 1000);
        }
    };
    private void resizeRecording(String request, Bundle status) {
        int width = status.getInt(Protocol.CAPTURE_WIDTH), height = status.getInt(Protocol.CAPTURE_HEIGHT);
        if (reader.getWidth() == width && reader.getHeight() == height) return;
        ImageReader next = null;
        try {
            CaptureSize size = new CaptureSize(width, height);
            next = ImageReader.newInstance(size.width(), size.height(), PixelFormat.RGBA_8888, 3);
            Bundle args = Ipc.request(request); args.putParcelable("surface", next.getSurface());
            args.putInt(Protocol.CAPTURE_WIDTH, width); args.putInt(Protocol.CAPTURE_HEIGHT, height);
            args.putInt(Protocol.CAPTURE_REVISION, status.getInt(Protocol.CAPTURE_REVISION));
            boolean accepted = bridge.call(Protocol.PROJECTION_SURFACE, args).getBoolean("accepted");
            if (!accepted || !request.equals(ownerRequest)) return;
            closeFrames(); reader = next; next = null;
            packed = ByteBuffer.allocateDirect(width * height * 4);
            reader.setOnImageAvailableListener(source -> scheduleImage(request, source), worker);
            scheduleImage(request, reader);
            report("PROJECTION receiving " + width + "x" + height);
        } catch (Exception e) { report("PROJECTION resize " + Ipc.error(e)); stopDirect(request); }
        finally { if (next != null) next.close(); }
    }
    private void observeBroker(Bundle status) {
        String identity = "pid=" + status.getInt("brokerPid", -1) + " version=" + status.getString("brokerVersion", "旧版未报告");
        if (!identity.equals(brokerIdentity)) {
            brokerIdentity = identity; report("BRIDGE module process " + identity); lastDiagnostics = 0;
        }
    }
    private void sendReport(String message) throws RemoteException {
        Bundle args = new Bundle(); args.putString("message", message); bridge.call(Protocol.REPORT, args);
    }
    public void report(String value) {
        Diagnostics.add(process + " " + value);
        synchronized (reports) { reports.addLast(value); while (reports.size() > 120) reports.removeFirst(); }
    }
    public void summary(String value) { summary = value; }
    public String status() { return bridge.status() + "\n" + state + "\n" + summary; }
    public boolean serviceConnected() { return bridge.connected(); }
    public String serviceStatus() { return bridge.status(); }
    public String serviceAdvice() { return bridge.advice(); }
    /** The module's cached GitHub release lookup; {@code refresh} asks it to look again now. */
    public void checkUpdate(boolean refresh, Consumer<Bundle> done, Consumer<String> failed) {
        Bundle args = new Bundle(); args.putBoolean("refresh", refresh);
        metadataCall(Protocol.UPDATE_CHECK, args, done, failed);
    }
    public long replacementCount() { synchronized (frameLock) { return replacements; } }
    public boolean readyFor(String request) { synchronized (frameLock) { return request.equals(ownerRequest) && ready(); } }
    public boolean captureActiveFor(String request) { return request.equals(ownerRequest) && active && SystemClock.elapsedRealtime() - lastPoll < 4000; }
    public boolean failedFor(String request) { return request.equals(ownerRequest) && !active && lastPoll != 0; }
    private boolean ready() { return active && displayReady && (debugMode.enabled() || latest != null || drawing) && SystemClock.elapsedRealtime() - lastPoll < 4000; }
    public void stopDirect(String request) {
        synchronized (observationLock) {
            if (request != null && request.equals(observedRequest)) {
                encoding.stop(request, SystemClock.elapsedRealtime(), statistics());
                if (streamStats != null) streamStats.stop(SystemClock.elapsedRealtime());
            }
        }
        if (request == null) return;
        boolean owns = request.equals(ownerRequest);
        if (owns) { ownerRequest = null; beginAccepted = null; active = displayReady = false; casting = false; castRequest = null; tires.endSession(); battery.endSession(); hud.reset(); vehicleStop.run(); naviTestStop.run(); themeSender.accept("", null); }
        pendingStops.add(request); bridge.ensure(); controlWorker.post(this::flushStops);
        worker.post(() -> { if (owns && ownerRequest == null) closeFrames(); });
    }
    private final Runnable stopRetry = new Runnable() {
        @Override public void run() { flushStops(); controlWorker.postDelayed(this, 1000); }
    };
    private void flushStops() {
        if (!bridge.connected()) { if (pendingStops.size() > 0) bridge.ensure(); return; }
        for (int count = 0; count < 8; count++) {
            PendingStops.Entry stop = pendingStops.first(); if (stop == null) return;
            try { bridge.call(Protocol.STOP_DIRECT, Ipc.request(stop.request)); pendingStops.acknowledged(stop); report("STOP acknowledged"); }
            catch (Exception e) { report("STOP pending retry " + Ipc.error(e)); return; }
        }
    }
    public boolean draw(Canvas canvas, int width, int height) {
        synchronized (frameLock) {
            if (!casting || !ready() || width < 1 || height < 1 || (long)width * height > 4096L * 2160) return false;
            boolean debug = debugMode.enabled();
            int saved = canvas.save();
            try { canvas.clipRect(0, 0, width, height); canvas.drawColor(Color.BLACK);
                if (drawing && !debug) {
                    drawPanel.draw(canvas, width, height, HudPalette.of(dashboardDark), settings.background(dashboardDark), drawSettings, drawValues(SystemClock.elapsedRealtime()));
                } else {
                    Bitmap picture = debug ? calibrationFrame() : latest;
                    float[] r = debug ? new float[]{0, 0, width, height} : Geometry.fit(picture.getWidth(), picture.getHeight(), width, height);
                    canvas.drawBitmap(picture, null, new RectF(r[0], r[1], r[2], r[3]), paint);
                    // Cards, touch marks and the calibration target belong to the virtual display; the capture is passed through as it is.
                    if(!debug&&savedSource.virtual()){int overlaySave=canvas.save();canvas.translate(r[0],r[1]);hud.draw(canvas,Math.round(r[2]-r[0]),Math.round(r[3]-r[1]),SystemClock.elapsedRealtime());drawTouchMarks(canvas,Math.round(r[2]-r[0]),SystemClock.elapsedRealtime());drawCalibrationTarget(canvas,Math.round(r[2]-r[0]),Math.round(r[3]-r[1]));canvas.restoreToCount(overlaySave);}
                }
            } finally { canvas.restoreToCount(saved); }
            replacements++; if (streamStats != null) streamStats.replaced(); return true;
        }
    }
    public Bitmap replacement(int width, int height, int density, boolean early) {
        synchronized (frameLock) {
            if (!casting || !ready() || width <= 0 || height <= 0 || (long)width * height > 4096L * 2160) return null;
            Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); output.setDensity(density);
            if (!draw(new Canvas(output), width, height)) { output.recycle(); return null; }
            supplied.put(output, stamp()); if (early) earlyFrames++;
            return output; // Encoder owns this object; never recycle or overwrite it from the producer.
        }
    }
    public Bitmap replace(Bitmap original) {
        synchronized (frameLock) {
            if (!casting || original == null || original.isRecycled() || !ready()) return null;
            if (stamp().equals(supplied.get(original))) { deduplicated++; return original; }
            return replacement(original.getWidth(), original.getHeight(), original.getDensity(), false);
        }
    }
    public DisplaySettings cachedSettings() { return savedSettings; }
    private FrameStamp stamp() {
        long now = SystemClock.elapsedRealtime();
        return new FrameStamp(pictureRevision, debugMode.enabled() ? 0 : drawing ? dev.ichinomiya.ninebotenhance.notification.DrawPanel.stamp(drawSettings, drawValues(now), dashboardDark) : hud.revision(now));
    }
    /** Opens the module's own lamp screen; its Bluetooth permissions belong to the module, not to Ninebot. */
    public void lampSettings(Activity activity, boolean dark) {
        Bundle args = new Bundle(); args.putBoolean("dark", dark);
        metadataCall(Protocol.LAMP_SETTINGS, args, result -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            try {
                PendingIntent intent = Ipc.parcelable(result, "lamp_intent", PendingIntent.class);
                if (intent == null) throw new IllegalStateException("模块版本不匹配，请更新并重启九号出行");
                activity.startIntentSenderForResult(intent.getIntentSender(), -1, null, 0, 0, 0, Ipc.launchOptions());
            } catch (Exception e) { dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(activity, null, "无法打开大灯控制", Ipc.error(e)); }
        }, error -> dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(activity, null, "无法打开大灯控制", error));
    }
    /** Opens the module's own BMS screen; its Bluetooth permissions belong to the module, not to Ninebot. */
    public void bmsSettings(Activity activity,boolean dark){
        Bundle args=new Bundle();args.putBoolean("dark",dark);
        metadataCall(Protocol.BMS_SETTINGS,args,result->{
            if(activity.isFinishing()||activity.isDestroyed())return;
            try{
                PendingIntent intent=Ipc.parcelable(result, "bms_intent", PendingIntent.class);
                if(intent==null)throw new IllegalStateException("模块版本不匹配，请更新并重启九号出行");
                activity.startIntentSenderForResult(intent.getIntentSender(),-1,null,0,0,0,Ipc.launchOptions());
            }catch(Exception e){dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(activity,null,"无法打开 BMS 管理",Ipc.error(e));}
        },error->dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(activity,null,"无法打开 BMS 管理",error));
    }
    /** Opens the module's own touch panel screen; the panel is read and held by the module, never by Ninebot. */
    public void touchSettings(Activity activity,boolean dark){
        Bundle args=new Bundle();args.putBoolean("dark",dark);
        metadataCall(Protocol.TOUCH_SETTINGS,args,result->{
            if(activity.isFinishing()||activity.isDestroyed())return;
            try{
                PendingIntent intent=Ipc.parcelable(result, "touch_intent", PendingIntent.class);
                if(intent==null)throw new IllegalStateException("模块版本不匹配，请更新并重启九号出行");
                activity.startIntentSenderForResult(intent.getIntentSender(),-1,null,0,0,0,Ipc.launchOptions());
            }catch(Exception e){dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(activity,null,"无法打开触摸屏管理",Ipc.error(e));}
        },error->dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(activity,null,"无法打开触摸屏管理",error));
    }
    public void notificationSettings(Activity activity, boolean dark) {
        Bundle args = new Bundle(); args.putBoolean("dark", dark);
        metadataCall(Protocol.NOTIFICATION_SETTINGS, args, result -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            try {
                PendingIntent intent = Ipc.parcelable(result, "settings_intent", PendingIntent.class);
                if (intent == null) throw new IllegalStateException("模块版本不匹配，请更新并重启九号出行");
                activity.startIntentSenderForResult(intent.getIntentSender(), -1, null, 0, 0, 0, Ipc.launchOptions());
            } catch (Exception e) { dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(activity, null, "无法打开通知设置", Ipc.error(e)); }
        }, error -> dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(activity, null, "无法打开通知设置", error));
    }
    public void pickLaunchApp(Activity activity, boolean dark, String selected, Consumer<Bundle> chosen) {
        Bundle args = new Bundle(); args.putBoolean("dark", dark); args.putString(AppCatalog.SELECTED, selected);
        args.putParcelable(dev.ichinomiya.ninebotenhance.ui.LaunchAppPickerActivity.RESULT, new ResultReceiver(main) {
            private boolean received;
            @Override protected void onReceiveResult(int code, Bundle app) {
                if (received) return; received = true;
                if (code == Activity.RESULT_OK && app != null && !activity.isFinishing() && !activity.isDestroyed()) chosen.accept(app);
            }
        });
        metadataCall(Protocol.LAUNCH_APP_PICKER, args, result -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            try {
                PendingIntent intent = Ipc.parcelable(result, "picker_intent", PendingIntent.class);
                if (intent == null) throw new IllegalStateException("模块版本不匹配，请更新并重启九号出行");
                activity.startIntentSenderForResult(intent.getIntentSender(), -1, null, 0, 0, 0, Ipc.launchOptions());
            } catch (Exception e) { dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(activity, null, "无法打开应用列表", Ipc.error(e)); }
        }, error -> dev.ichinomiya.ninebotenhance.ui.ErrorDialog.show(activity, null, "无法打开应用列表", error));
    }
    public boolean debugModeUnlocked() { return debugMode.unlocked(); }
    public boolean debugModeEnabled() { return debugMode.enabled(); }
    public boolean debugVersionTap() { return debugMode.tapVersion(); }
    public void setDebugMode(boolean enabled) {
        synchronized (frameLock) {
            if (!debugMode.setEnabled(enabled)) return;
            pictureRevision++; calibration = null;
        }
        report("DEBUG calibration enabled=" + debugMode.enabled());
        worker.post(this::refreshCalibration);
        View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation();
    }
    private void refreshCalibration() {
        Bitmap previous = calibration;
        synchronized (frameLock) {
            if (!active || !displayReady || !debugMode.enabled()) return;
            calibrationFrame();
        }
        if (previous != calibration) { View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation(); }
    }
    /** Called under frameLock. The cached chart is immutable and never recycled while a preview may use it. */
    private Bitmap calibrationFrame() {
        int[] encoded = encoding.frameSize();
        int width = encoded == null ? DisplaySettings.defaults().width : encoded[0];
        int height = encoded == null ? DisplaySettings.defaults().height : encoded[1];
        if (calibration == null || calibration.getWidth() != width || calibration.getHeight() != height) {
            calibration = Bitmap.createBitmap(CalibrationPattern.render(width, height), width, height, Bitmap.Config.ARGB_8888);
            calibration.setDensity(Bitmap.DENSITY_NONE);
            pictureRevision++;
            report("DEBUG calibration size=" + width + "x" + height + " coordinates=" + (encoded == null ? "fallback" : "codec")
                    + " origin=top-left minor=10 major=50 cell=100 canvasAndHud=bypassed");
        }
        return calibration;
    }
    public PrivilegeMode cachedPrivilege() { return savedPrivilege; }
    public PictureSource cachedSource() { return savedSource; }
    private volatile Bundle bmsConfig = new Bundle();
    public Bundle cachedBmsConfig() { return bmsConfig; }
    /** The module's BMS configuration; its source choice is mirrored into the widget flags so cards, conditions and vehicle reads follow it. */
    private void applyBmsConfig(Bundle result) {
        if (result == null || !result.containsKey("bms_prefer")) return;
        bmsConfig = new Bundle(result);
        boolean prefer = result.getBoolean("bms_prefer");
        WidgetSettings current = widgets;
        if (current.enabled(WidgetSettings.VOLTAGE_FROM_BMS) != prefer || current.enabled(WidgetSettings.POWER_FROM_BMS) != prefer) {
            saveWidgetSettings(current.with(WidgetSettings.VOLTAGE_FROM_BMS, prefer).with(WidgetSettings.POWER_FROM_BMS, prefer));
            report("BMS source " + (prefer ? "bms" : "dashboard"));
        }
    }
    public void bmsConfig(Bundle args, Consumer<Bundle> done, Consumer<String> failed) {
        metadataCall(Protocol.BMS_CONFIG, args, result -> { applyBmsConfig(result); done.accept(result); }, failed);
    }
    public boolean drawing() { return drawing; }
    public DrawSettings drawSettings() { return drawSettings; }
    public void saveDrawSettings(DrawSettings value) {
        drawSettings = value;
        try {
            if (context != null) context.getSharedPreferences(Protocol.MODULE + ".widgets", Context.MODE_PRIVATE).edit().putInt("draw_max_speed", value.maxSpeed())
                    .putInt("draw_field_1", value.first()).putInt("draw_field_2", value.second()).putInt("draw_field_3", value.third()).apply();
        } catch (RuntimeException e) { report("DRAW settings save " + Ipc.error(e)); }
        report("DRAW settings " + value.label());
        View preview = inlinePreview.get(); if (preview != null) preview.postInvalidateOnAnimation();
    }
    /** What the drawn picture shows right now: the same telemetry the cards read, with the cards' freshness limits. */
    private dev.ichinomiya.ninebotenhance.notification.DrawPanel.Values drawValues(long now) {
        WidgetSettings w = widgets;
        RideState.Snapshot r = ride.snapshot();
        dev.ichinomiya.ninebotenhance.core.BmsState bms = hud.bmsIfFresh(now);
        boolean boardVoltage = w.enabled(WidgetSettings.VOLTAGE_FROM_BMS) && bms.data().known(), boardPower = w.enabled(WidgetSettings.POWER_FROM_BMS) && bms.data().known();
        float speed = r.speedAt() == 0 || r.speedTenths() < 0 || now - r.speedAt() > w.speedLimitMs() ? Float.NaN : r.speedKmh();
        float watts = boardPower ? bms.data().watts() : !r.hasPower() || now - r.powerAt() > w.powerLimitMs() ? Float.NaN : r.power();
        dev.ichinomiya.ninebotenhance.core.BatteryTelemetry.Value v = battery.snapshot().voltage();
        float volts = boardVoltage ? bms.data().volts() : v == null || now - v.elapsedTime() > w.voltageLimitMs() ? Float.NaN : v.number();
        dev.ichinomiya.ninebotenhance.core.TireTelemetry.Snapshot t = tires.snapshot();
        return new dev.ichinomiya.ninebotenhance.notification.DrawPanel.Values(speed, watts, volts, bms.data().known() ? bms.data().soc() : -1,
                tyre(t.front().pressure(), now, w), tyre(t.rear().pressure(), now, w));
    }
    private static float tyre(dev.ichinomiya.ninebotenhance.core.TireTelemetry.Value value, long now, WidgetSettings w) {
        return value == null || now - value.elapsedTime() > w.tyreLimitMs() ? Float.NaN : value.number();
    }
    /** The vehicle reads the drawn picture needs: the gauge's speed always, the rest as its fields ask. */
    private WidgetSettings drawReads(WidgetSettings current) {
        return current.with(WidgetSettings.SPEED, true).with(WidgetSettings.POWER, drawSettings.uses(DrawSettings.POWER))
                .with(WidgetSettings.VOLTAGE, drawSettings.uses(DrawSettings.VOLTAGE))
                .with(WidgetSettings.TYRES | WidgetSettings.TYRE_FRONT | WidgetSettings.TYRE_REAR, drawSettings.uses(DrawSettings.TYRES));
    }
    public String cachedApp() { return savedApp == null ? "" : savedApp; }
    public boolean serviceBindRefused() { return bridge.bindRefused(); }
    /** Keep-DPI: the size and density the display was created at for this session; null when no scaling is needed. */
    private DisplaySettings.RenderPlan renderPlan;
    private ByteBuffer scaledTight;private Bitmap scaledSource;
    private final android.graphics.Paint scaledPaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
    private int phoneDensityDpi() {
        try {
            android.view.Display phone = context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY);
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); phone.getRealMetrics(metrics); return metrics.densityDpi;
        } catch (RuntimeException e) { report("RENDER phone density unavailable " + Ipc.error(e)); return 0; }
    }
    /** Keep-DPI: the plan-sized RGBA buffer is packed tight, then filtered down into the virtual area of a fresh frame bitmap. */
    private Bitmap composeScaled(Image.Plane plane, int width, int height, int frameWidth, int frameHeight) {
        int bytes = width * height * 4;
        if (scaledTight == null || scaledTight.capacity() < bytes) scaledTight = ByteBuffer.allocateDirect(bytes);
        PixelPacking.rgba(plane.getBuffer(), plane.getRowStride(), plane.getPixelStride(), width, height, scaledTight);
        if (scaledSource == null || scaledSource.getWidth() != width || scaledSource.getHeight() != height) scaledSource = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        scaledSource.copyPixelsFromBuffer(scaledTight);
        Bitmap frame = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame); canvas.drawColor(settings.background(dashboardDark));
        canvas.drawBitmap(scaledSource, null, new android.graphics.Rect(0, frameHeight - settings.bottomInset - settings.virtualHeight, settings.virtualWidth, frameHeight - settings.bottomInset), scaledPaint);
        return frame;
    }
    public void appIcon(String component, android.widget.ImageView target) { appIcons.load(component, target); }
    private void cacheSettings(DisplaySettings value, String selected) {
        savedSettings = value; savedApp = selected;
        try {
            if (context != null) context.getSharedPreferences("dev.ichinomiya.ninebotenhance.cached_display", Context.MODE_PRIVATE).edit()
                    .putInt("width",value.width).putInt("height",value.height).putInt("dpi",value.dpi)
                    .putInt("layout_version",DisplaySettings.LAYOUT_VERSION).putInt("virtual_width",value.virtualWidth).putInt("virtual_height",value.virtualHeight)
                    .putInt("background_color",value.backgroundColor).putInt("keep_phone_dpi",value.keepPhoneDpi?1:0).putInt("virtual_override",value.virtualOverride?1:0).putInt("light_background_color",value.lightBackgroundColor).putInt("bottom_inset",value.bottomInset).remove("top_inset").remove("top_color")
                    .putString(AppCatalog.SELECTED, selected).apply();
        } catch (RuntimeException e) { report("SETTINGS cache write " + Ipc.error(e)); }
    }
    /** One bounded worker for settings/log RPC. A stuck transact cannot block the UI's timeout or local log viewing. */
    private void metadataCall(int code, Bundle args, Consumer<Bundle> done, Consumer<String> failed) {
        if (!metadataBusy.compareAndSet(false, true)) {
            main.post(() -> failed.accept("上一条设置或日志请求尚未返回，可先查看本地日志，再稍后重新读取")); return;
        }
        AtomicBoolean completed = new AtomicBoolean(); bridge.ensure();
        Runnable timeout = () -> {
            if (completed.compareAndSet(false, true)) {
                report("METADATA timeout code=" + code);
                failed.accept("服务响应超时，结果尚未确认；请重连后重新读取核对");
            }
        };
        main.postDelayed(timeout, 3500);
        metadataWorker.post(new Runnable() {
            @Override public void run() {
                if (completed.get()) { metadataBusy.set(false); return; }
                if (!bridge.connected()) { bridge.ensure(); metadataWorker.postDelayed(this, 150); return; }
                Bundle response = null; String failure = null;
                try { response = bridge.call(code, args); }
                catch (Exception e) { failure = Ipc.error(e); report("METADATA code=" + code + " " + failure); }
                finally { metadataBusy.set(false); }
                // Release the slot before invoking UI callbacks, which may immediately read after saving a mode.
                Bundle result = response; String error = failure;
                main.post(() -> {
                    if (!completed.compareAndSet(false, true)) return;
                    main.removeCallbacks(timeout);
                    if (error == null) done.accept(result); else failed.accept(error);
                });
            }
        });
    }
    public void getSettings(Consumer<Bundle> done, Consumer<String> failed) {
        Bundle args = new Bundle(); args.putBoolean("include_apps", true);
        metadataCall(Protocol.SETTINGS, args, result -> {
            try { cachePrivilege(result); applyBmsConfig(result); cacheSettings(Ipc.settings(result), result.getString(AppCatalog.SELECTED, "")); done.accept(result); }
            catch (RuntimeException e) { failed.accept(Ipc.error(e)); }
        }, failed);
    }
    private void cachePrivilege(Bundle result) {
        savedPrivilege = PrivilegeMode.read(result.getString("privilege_mode"), PrivilegeMode.ROOT);
        savedSource = PictureSource.read(result.getString(PictureSource.KEY), savedSource);
        if (context != null) context.getSharedPreferences("dev.ichinomiya.ninebotenhance.cached_display", Context.MODE_PRIVATE)
                .edit().putString("privilege_mode", savedPrivilege.name()).putString(PictureSource.KEY, savedSource.name()).apply();
    }
    public void privilege(Bundle args, Consumer<Bundle> done, Consumer<String> failed) {
        metadataCall(Protocol.PRIVILEGE, args, result -> {
            try { cachePrivilege(result); done.accept(result); } catch (RuntimeException e) { failed.accept(Ipc.error(e)); }
        }, failed);
    }
    public void saveSettings(DisplaySettings value, String selected, Consumer<String> done) {
        Bundle args = new Bundle(); args.putBoolean("save", true); Ipc.settings(args, value);
        args.putString(AppCatalog.SELECTED, selected);
        metadataCall(Protocol.SETTINGS, args, result -> {
            try { cacheSettings(Ipc.settings(result), result.getString(AppCatalog.SELECTED, "")); done.accept(null); }
            catch (RuntimeException e) { done.accept(Ipc.error(e)); }
        }, done);
    }
    private void refreshModuleDiagnostics() {
        metadataCall(Protocol.LOG, new Bundle(), result -> {
            String collected = "采集时间：" + new Date() + "\n";
            String full = result.getString("text", "无模块日志");
            moduleDigest = collected + result.getString("compact", LogDigest.recent(full, 1000));
        }, error -> {});
    }
    private String diagnosticHeader() {
        return "Ninebot Enhance " + Protocol.VERSION + " / Android " + Build.VERSION.RELEASE + " / " + Build.MANUFACTURER + " " + Build.MODEL
                + "\n当前连接：\n" + status() + "\nprocess=" + process + " pid=" + android.os.Process.myPid()
                + " casting=" + casting + " captureSource=" + (screenCapture ? "MediaProjection" : "VirtualDisplay")
                + " debugMode=" + debugMode.enabled()
                + "\nHUD " + hud.summary(SystemClock.elapsedRealtime())
                + "\nTIRE " + tires.summary(SystemClock.elapsedRealtime())
                + "\nBATTERY " + battery.summary(SystemClock.elapsedRealtime())
                + "\nVEHICLE READ " + vehicleReadSummary.get()
                + " selectedApp=" + savedApp + " pendingStops=" + pendingStops.size() + " metadataBusy=" + metadataBusy.get()
                + "\n模块进程：" + brokerIdentity + "\n最后模块会话状态：" + brokerStatus
                + "\nreplaced=" + replacements + " early=" + earlyFrames + " dedup=" + deduplicated
                + " statusAgeMs=" + (lastPoll == 0 ? -1 : SystemClock.elapsedRealtime() - lastPoll)
                + " frameAgeMs=" + (lastImage == 0 ? -1 : SystemClock.elapsedRealtime() - lastImage);
    }
    public void diagnostics(Consumer<String> done) {
        // Never wait for the frame/control/metadata workers: all three may be waiting on another process.
        main.post(() -> done.accept(LogDigest.head(diagnosticHeader(), 900)
                + "\n\n原会话编码配置：\n" + encoding.summary()
                + "\n\n最近本地事件（新到旧，已略去 Hook 签名）：\n" + Diagnostics.recent(1500)
                + "\n\n模块缓存（可能早于当前故障）：\n" + LogDigest.head(moduleDigest, 1000)
                + "\n\n点击“分享完整日志”导出当前保留的日志文件。"));
    }
    public void shareFullLog(Consumer<android.net.Uri> done, Consumer<String> failed) {
        logExporter.export(() -> diagnosticHeader()
                + "\n\n原会话编码配置：\n" + encoding.details()
                + "\n\n九号进程本地日志：\n" + Diagnostics.text(), done, failed);
    }
}
