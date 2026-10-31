package dev.ichinomiya.ninebotenhance.ui;

import dev.ichinomiya.ninebotenhance.core.PictureSource;

import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.client.ServiceBridge;
import dev.ichinomiya.ninebotenhance.core.DirectSession;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;
import dev.ichinomiya.ninebotenhance.core.StartPermission;
import dev.ichinomiya.ninebotenhance.core.UpdateCheck;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import dev.ichinomiya.ninebotenhance.platform.AppCatalog;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Retains the verified original ivCruise listener and the selected vehicle's checks. The virtual display is its own layer: it
 * starts from its button or for the first cast, keeps running when a cast ends or its preview is closed, and ends only when the
 * host's last activity is destroyed or the host process dies. A cast binds the running display to the vehicle.
 */
public final class DirectCastController implements Application.ActivityLifecycleCallbacks {
    public static final String CRUISE = "cn.ninebot.device.motor.navi.CruiseModeActivity";
    private static final String PENDING_DISPLAY = "dev.ichinomiya.ninebotenhance.pendingRequest";
    private final FrameClient frames;
    private final BooleanSupplier compatible;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DirectSession session = new DirectSession();
    private final VehicleCardInjector injector;
    private WeakReference<Activity> foreground = new WeakReference<>(null), origin = new WeakReference<>(null),
            cruiseActivity = new WeakReference<>(null), previewHost = new WeakReference<>(null);
    private final Set<Application> applications = Collections.newSetFromMap(new IdentityHashMap<>());
    private long launchDeadline, beforeFrames, captureMissingSince, inlineDeadline, previewRecreateUntil;
    private CruiseVirtualPanel panel;
    private RecordingPanel recordingPanel;
    private LocalVirtualDisplayDialog preview;
    /** The preview was open when its activity was rebuilt; it reopens on the rebuilt one. */
    private boolean previewWanted;
    /** The preview window's orientation choice for this display, and the host activity's own requested orientation while overridden. */
    private boolean previewLandscape; private int hostOrientation = NO_ORIENTATION;
    private static final int NO_ORIENTATION = Integer.MIN_VALUE;
    private int createdActivities, visibleActivities;
    private final StartPermission.Check permissionCheck = new StartPermission.Check();
    private WeakReference<Activity> permissionActivity = new WeakReference<>(null);
    /** The module's last release lookup, the version already offered this process, and how many lookups were asked for. */
    private Bundle update; private String updatePrompted; private int updateAttempts;
    /** The system refused touch injection once this process: shown once, or on the next usable page when none is in front. */
    private boolean inputDeniedPrompted; private String pendingInputDenial;
    public DirectCastController(FrameClient frames, BooleanSupplier compatible) {
        this.frames = frames; this.compatible = compatible;
        injector = new VehicleCardInjector(this, frames);
        frames.setDynamicPageListener(injector::refresh);
        frames.setInputDeniedListener(this::inputDenied);
    }
    public void attach(Application application) { if (applications.add(application)) application.registerActivityLifecycleCallbacks(this); }
    /** Entry used by the independent APK control plane. It deliberately reuses the normal UI/session path. */
    public void externalCast(Context context, String request, String selectedApp) {
        if (!Protocol.validRequest(request)) return;
        main.post(() -> {
            Activity activity = foreground.get();
            if (!compatible.getAsBoolean() || !usable(activity)) { frames.report("EXTERNAL cast rejected: Ninebot vehicle page is not visible"); return; }
            if (selectedApp != null && !selectedApp.isEmpty()) frames.report("EXTERNAL selected app=" + selectedApp);
            if (session.casting()) { frames.report("EXTERNAL cast already running"); return; }
            autostartPrompt(activity);
            checkPermission(activity, null, () -> startVehicle(activity));
        });
    }
    public void externalStop(String request) {
        if (!Protocol.validRequest(request)) return;
        main.post(() -> { if (session.casting() || session.cast() == DirectSession.Cast.CHECKING_VEHICLE || session.cast() == DirectSession.Cast.WAITING_DISPLAY || session.cast() == DirectSession.Cast.STARTING) endCast(session.castRequest(), true, "独立投屏已停止"); });
    }
    public void inflated(int id, View view) {
        if (!compatible.getAsBoolean() || view == null) return;
        try { injector.inflated(view.getResources().getResourceEntryName(id), view); }
        catch (RuntimeException ignored) {}
    }
    /** Button faces for one installed row; the injector also refreshes them once a second while the row is on screen. */
    public void decorate(VehicleCardInjector.Row row) {
        boolean virtual = frames.cachedSource().virtual(), running = session.displayRunning();
        ImageButton display = row.display(); String face = running ? "running" : "idle";
        if (!face.equals(display.getTag())) { display.setTag(face); display.setImageDrawable(new MirrorUi.Glyph("display", running ? row.theme().accent : row.theme().secondary)); }
        display.setContentDescription(running ? "虚拟显示器预览" : "启动虚拟显示器");
        // Recording mode has no virtual display: the control disappears and the cast button takes the start of the row.
        int visibility = virtual ? View.VISIBLE : View.GONE;
        if (display.getVisibility() != visibility) {
            display.setVisibility(visibility);
            LinearLayout.LayoutParams castParams = (LinearLayout.LayoutParams) row.cast().getLayoutParams();
            castParams.setMarginStart(virtual ? MirrorUi.dp(row.view().getContext(), 8) : 0); row.cast().setLayoutParams(castParams);
        }
        display.setEnabled(!permissionCheck.active()); display.setAlpha(permissionCheck.active() ? 0.4f : 1f);
        Button cast = row.cast();
        if (permissionCheck.active()) { cast.setText("取消授权检查"); cast.setEnabled(true); return; }
        switch (session.cast()) {
            // Always clickable: the vehicle checks run on the tap (missing entry, power off), never as a greyed button.
            case IDLE: cast.setText("全屏投屏"); break;
            case CHECKING_VEHICLE: cast.setText("取消车辆检查"); break;
            case WAITING_DISPLAY: case STARTING: cast.setText(virtual ? "取消投屏启动" : "取消录屏启动"); break;
            case RUNNING: cast.setText("停止投屏"); break;
        }
        cast.setEnabled(true);
    }
    // ---------------------------------------------------------------- display layer
    /** The left button: start the virtual display, or open the preview of the one already running. */
    public void clickDisplay(Activity activity, View anchor) {
        if (!compatible.getAsBoolean() || !usable(activity)) return;
        if (permissionCheck.active()) { cancelPermissionCheck(); return; }
        if (session.displayRunning()) { showPreview(activity); return; }
        if (!frames.cachedSource().virtual()) { toast(activity, "当前画面提供方式没有虚拟显示器"); return; }
        autostartPrompt(activity);
        checkPermission(activity, anchor, () -> { if (!session.displayRunning()) beginDisplay(activity, true); });
    }
    /** Creates the virtual display, or in recording mode the recording session hosted by the given activity. */
    private void beginDisplay(Activity activity, boolean withPreview) {
        String request = UUID.randomUUID().toString().replace("-", "");
        if (!session.beginDisplay(request)) return;
        captureMissingSince = 0; previewRecreateUntil = 0; previewLandscape = false;
        frames.report("CAST STAGE display-begin request=" + request + " elapsedMs=" + SystemClock.elapsedRealtime() + " source=" + frames.cachedSource().name() + " mode=" + (withPreview ? "preview" : "vehicle-cast"));
        frames.startDirect(request, activity, error -> {
            if (!session.ownsDisplay(request)) return;
            if (error != null) { endDisplay(request, error); return; }
            waitDisplay(request, SystemClock.elapsedRealtime() + (frames.screenCapture() ? 180000 : 65000));
        });
        injector.refresh();
        if (withPreview && usable(activity)) showPreview(activity);
    }
    private void waitDisplay(String request, long deadline) {
        if (!session.ownsDisplay(request) || session.display() != DirectSession.Display.STARTING) return;
        if (frames.failedFor(request)) { endDisplay(request, frames.status()); return; }
        if (frames.readyFor(request)) {
            if (!session.displayReady(request)) return;
            if (preview != null) preview.ready();
            frames.report("CAST STAGE display-ready request=" + request + " elapsedMs=" + SystemClock.elapsedRealtime() + " displayRequest=" + request + " capture=first-RGBA-frame");
            injector.refresh(); main.postDelayed(() -> monitorDisplay(request), 500);
            String cast = session.castRequest();
            if (cast != null && session.cast() == DirectSession.Cast.WAITING_DISPLAY) attach(cast);
            return;
        }
        if (SystemClock.elapsedRealtime() >= deadline) { endDisplay(request, frames.status()); return; }
        main.postDelayed(() -> waitDisplay(request, deadline), 200);
    }
    private void monitorDisplay(String request) {
        if (!session.ownsDisplay(request)) return;
        long now = SystemClock.elapsedRealtime();
        if (!frames.captureActiveFor(request)) {
            if (captureMissingSince == 0) captureMissingSince = now;
            if (now - captureMissingSince > 4000) { endDisplay(request, "画面采集已结束\n" + frames.status()); return; }
        } else captureMissingSince = 0;
        Activity host = previewHost.get();
        if (previewWanted && preview == null && usable(host) && foreground.get() == host && now < previewRecreateUntil) showPreview(host);
        main.postDelayed(() -> monitorDisplay(request), 500);
    }
    private void endDisplay(String request, String message) {
        if (!session.ownsDisplay(request)) return;
        String cast = session.castRequest();
        if (cast != null) endCast(cast, true, message, true);
        if (!session.endDisplay(request)) return;
        closePreview(); restoreOrientation(previewHost.get()); previewLandscape = false; previewWanted = false; previewRecreateUntil = 0; previewHost.clear();
        frames.stopDirect(request);
        frames.report("DISPLAY ended: " + message);
        injector.refresh();
        notify(message);
    }
    private void showPreview(Activity activity) {
        String request = session.displayRequest();
        if (request == null || !usable(activity)) return;
        if (preview != null) { if (preview.owns(activity)) return; closePreview(); restoreOrientation(previewHost.get()); }
        previewHost = new WeakReference<>(activity); previewWanted = true; previewRecreateUntil = 0;
        try {
            preview = new LocalVirtualDisplayDialog(activity, request, frames, () -> previewLandscape, () -> togglePreviewLandscape(activity),
                    this::previewDismissed, () -> entryDetails(activity, null));
            preview.show();
            if (session.display() == DirectSession.Display.READY) preview.ready();
            applyPreviewOrientation(activity);
        } catch (RuntimeException e) { preview = null; previewWanted = false; error(activity, "无法显示虚拟显示器", Ipc.error(e)); }
    }
    /** Back or the toolbar's close only hide the preview; the display keeps running and the host gets its orientation back. */
    private void previewDismissed() {
        preview = null; previewWanted = false; restoreOrientation(previewHost.get()); previewHost.clear(); frames.report("DISPLAY preview hidden");
    }
    private void closePreview() {
        if (preview == null) return;
        try { preview.close(); } catch (RuntimeException e) { frames.report("DISPLAY preview close " + Ipc.error(e)); }
        preview = null;
    }
    // ---------------------------------------------------------------- preview orientation
    /** The toolbar's "横屏": the whole preview window turns by asking the host activity for landscape; "还原" gives its own value back. */
    private void togglePreviewLandscape(Activity activity) {
        previewLandscape = !previewLandscape; frames.report("DISPLAY preview " + (previewLandscape ? "landscape" : "portrait"));
        applyPreviewOrientation(activity);
    }
    private void applyPreviewOrientation(Activity activity) {
        if (!usable(activity)) return;
        if (!previewLandscape) { restoreOrientation(activity); return; }
        try {
            // The host's own value is kept from the first turn: after a rebuild the record already reports landscape.
            if (hostOrientation == NO_ORIENTATION) hostOrientation = activity.getRequestedOrientation();
            if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE) activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
        } catch (RuntimeException e) { frames.report("DISPLAY preview orientation " + Ipc.error(e)); }
    }
    /** Gives the host activity the orientation it had before the preview turned it, once. */
    private void restoreOrientation(Activity activity) {
        if (hostOrientation == NO_ORIENTATION) return;
        int original = hostOrientation; hostOrientation = NO_ORIENTATION;
        if (!usable(activity)) return;
        try { activity.setRequestedOrientation(original); } catch (RuntimeException e) { frames.report("DISPLAY preview orientation " + Ipc.error(e)); }
    }
    // ---------------------------------------------------------------- cast layer
    private void castStage(String request, String detail) {
        frames.report("CAST STAGE request=" + request + " elapsedMs=" + SystemClock.elapsedRealtime()
                + " phase=" + session.phase() + " " + detail);
    }
    /** The middle button: start a cast on the running display (starting one if needed), or stop the current cast. */
    public void clickCast(Activity activity, View anchor) {
        if (!compatible.getAsBoolean()) return;
        if (permissionCheck.active()) { cancelPermissionCheck(); return; }
        if (session.casting()) { endCast(session.castRequest(), true, "投屏已停止"); return; }
        if (!usable(activity)) return;
        autostartPrompt(activity);
        checkPermission(activity, anchor, () -> startVehicle(activity));
    }
    private void startVehicle(Activity activity) {
        if (session.casting() || !usable(activity)) return;
        View entry = injector.cruiseEntry();
        frames.report("DIRECT CLICK " + VehicleCardInjector.entryInfo(injector.navigationCard()));
        String request = UUID.randomUUID().toString().replace("-", "");
        if (!session.beginCast(request)) return;
        frames.beginCastObservations(request);
        origin = new WeakReference<>(activity); inlineDeadline = 0;
        castStage(request, "vehicle-check begin" + (session.displayRunning() ? " virtual-display=already-running" : " virtual-display=not-started"));
        try {
            // Ninebot's own listener does every vehicle check (connection, capability, power). It stays bound while ivCruise
            // is hidden, and callOnClick does not care about visibility, so the only thing that cannot be clicked is no view at all.
            if (entry == null || !entry.callOnClick()) { endCast(request, false, "巡航入口不可用"); return; }
        } catch (RuntimeException e) { endCast(request, true, "巡航启动失败：" + Ipc.error(e)); return; }
        injector.refresh();
        waitVehicle(request, SystemClock.elapsedRealtime() + 90000);
    }
    /** Captured at the original power query's invocation, never from a cached previous result. */
    public String vehicleCheckRequest() { return session.vehicleCheckRequest(); }
    public void vehiclePowerObserved(String request, boolean on) {
        main.post(() -> {
            if (!session.powerChecked(request, on)) return;
            castStage(request, "vehicle-power observed on=" + on);
            if (!on) endCast(request, true, "车辆未开机，请先开机并连接蓝牙后再投屏");
        });
    }
    private void waitVehicle(String request, long deadline) {
        if (!session.ownsCast(request) || session.cast() != DirectSession.Cast.CHECKING_VEHICLE) return;
        Activity cruise = cruiseActivity.get();
        if (!usable(origin.get()) && !usable(cruise)) { endCast(request, false, "车辆页面已关闭"); return; }
        if (SystemClock.elapsedRealtime() >= deadline) { endCast(request, true, "未确认车辆投屏条件，请确认车辆已开机并连接蓝牙后重试"); return; }
        if (usable(cruise) && foreground.get() == cruise && cruise.hasWindowFocus()) {
            if (session.cruiseReady(request)) castStage(request, "cruise-activity foreground-and-focused");
            if (session.vehicleConfirmed(request)) { castStage(request, "vehicle-prerequisites confirmed"); prepareCast(request); return; }
        }
        main.postDelayed(() -> waitVehicle(request, deadline), 200);
    }
    /** Vehicle checks passed: bind the running display, wait for the one still starting, or start one now. */
    private void prepareCast(String request) {
        if (!session.ownsCast(request) || session.cast() != DirectSession.Cast.WAITING_DISPLAY) return;
        switch (session.display()) {
            case READY: attach(request); break;
            case STARTING: frames.report("DIRECT vehicle checks passed; waiting for the virtual display"); break;
            default:
                frames.report("DIRECT vehicle checks passed; starting the " + frames.cachedSource().name() + " picture for the cast");
                beginDisplay(cruiseActivity.get(), false);
        }
    }
    private void attach(String request) {
        String display = session.displayRequest();
        if (display == null || !session.launch(request)) return;
        beforeFrames = frames.replacementCount();
        castStage(request, "attach displayRequest=" + display + " replacementFramesBefore=" + beforeFrames);
        if (!frames.attachCast(display, request)) { endCast(request, true, "虚拟显示器未就绪，请重试"); return; }
        launchDeadline = SystemClock.elapsedRealtime() + 90000;
        frames.report("DIRECT cast attached to the display; waiting for replacement frames");
        injector.refresh();
        main.postDelayed(() -> monitorCast(request), 500);
    }
    private void monitorCast(String request) {
        if (!session.ownsCast(request)) return;
        long now = SystemClock.elapsedRealtime();
        if (session.cast() == DirectSession.Cast.STARTING) {
            if ((frames.screenCapture() || cruiseActivity.get() != null) && frames.replacementCount() > beforeFrames) {
                if (session.running(request)) castStage(request, "capture-hook frame observed replacementFrames=" + frames.replacementCount()); injector.refresh();
            } else if (now > launchDeadline) { endCast(request, true, "巡航投屏未启动，请长按按钮查看日志"); return; }
        }
        Activity cruise = cruiseActivity.get();
        if (session.cast() == DirectSession.Cast.RUNNING && usable(cruise) && foreground.get() == cruise && cruise.hasWindowFocus()) {
            String display = session.displayRequest();
            try {
                if (frames.screenCapture()) {
                    if (recordingPanel != null && !recordingPanel.owns(cruise)) closePanel();
                    if (recordingPanel == null) recordingPanel = new RecordingPanel(cruise, frames, () -> endCast(request, true, "投屏已停止"));
                    main.postDelayed(() -> monitorCast(request), 500); return;
                }
                if (panel != null && (!panel.owns(cruise) || !panel.attached())) closePanel();
                if (panel == null && display != null) {
                    if (inlineDeadline == 0) inlineDeadline = now + 10000;
                    panel = CruiseVirtualPanel.mount(cruise, display, frames, () -> endCast(request, true, "投屏已停止"), () -> entryDetails(cruise, null));
                    if (panel != null) inlineDeadline = 0;
                    else if (now >= inlineDeadline) { endCast(request, true, "未找到巡航地图区域，请长按投屏按钮查看日志"); return; }
                }
            } catch (RuntimeException e) { endCast(request, true, "无法显示巡航虚拟屏：" + Ipc.error(e)); return; }
        }
        main.postDelayed(() -> monitorCast(request), 500);
    }
    private void endCast(String request, boolean finishCruise, String message) { endCast(request, finishCruise, message, false); }
    private void endCast(String request, boolean finishCruise, String message, boolean withDisplay) {
        if (!session.ownsCast(request)) return;
        castStage(request, "stopping reason=" + message);
        if (!session.endCast(request)) return;
        closePanel();
        Activity owned = cruiseActivity.get(); cruiseActivity.clear(); origin.clear();
        frames.detachCast(request);
        if (finishCruise && usable(owned)) owned.finish();
        frames.report("DIRECT ended: " + message);
        if (withDisplay) return;
        injector.refresh();
        // A recording or drawing session is nothing but the cast, so it ends with it; the virtual display stays.
        if (!frames.cachedSource().virtual() && session.displayRunning()) { endDisplay(session.displayRequest(), message); return; }
        notify(message);
    }
    private void closePanel() {
        if (panel != null) { panel.close(); panel = null; }
        if (recordingPanel != null) { recordingPanel.close(); recordingPanel = null; }
        inlineDeadline = 0;
    }
    /** Called only after the observed cast manager's actual terminal operation, never its encoder rotation/reconfigure. */
    public void transportEnded(String reason) {
        if (session.cast() != DirectSession.Cast.RUNNING) return;
        // Ninebot tears down its foreground lock-screen transport when the phone sleeps.
        // Keep the module-owned display and session alive so the next encoder callback can
        // continue with the module lock frame instead of returning to the native lock screen.
        if (frames.displayLockActive()) {
            frames.report("DIRECT native transport paused while phone is locked: " + reason);
            return;
        }
        String request = session.castRequest();
        main.post(() -> { if (session.ownsCast(request) && session.cast() == DirectSession.Cast.RUNNING) endCast(request, true, "巡航投屏已结束：" + reason); });
    }
    // ---------------------------------------------------------------- activity lifecycle
    @Override public void onActivityCreated(Activity activity, Bundle state) {
        createdActivities++;
        if (state != null && session.ownsDisplay(state.getString(PENDING_DISPLAY))) {
            previewHost = new WeakReference<>(activity); previewRecreateUntil = SystemClock.elapsedRealtime() + 15000;
        }
        if (session.casting() && CRUISE.equals(activity.getClass().getName())) {
            if (panel != null && !panel.owns(activity)) closePanel();
            cruiseActivity = new WeakReference<>(activity); frames.report("DIRECT cruise activity created");
        }
    }
    /** The page walk from before 1.1.5: every 1.2 s while a host activity is in front, so cards that come and go with Bluetooth are caught. */
    private final Runnable scan = new Runnable() {
        @Override public void run() {
            Activity activity = foreground.get(); if (!usable(activity)) return;
            if (compatible.getAsBoolean() && !CRUISE.equals(activity.getClass().getName())) injector.scanPage(activity);
            main.postDelayed(this, 1200);
        }
    };
    @Override public void onActivityResumed(Activity activity) {
        foreground = new WeakReference<>(activity); main.removeCallbacks(scan); main.post(scan);
        if (pendingInputDenial != null) promptInputDenial(activity);
        if (compatible.getAsBoolean() && !CRUISE.equals(activity.getClass().getName())) scheduleUpdateCheck();
        if (panel != null && panel.owns(activity)) panel.resume();
        if (recordingPanel != null && recordingPanel.owns(activity)) recordingPanel.resume();
        if (preview != null && preview.owns(activity)) { preview.resume(); applyPreviewOrientation(activity); }
        else if (previewWanted && preview == null && previewHost.get() == activity && session.displayRunning()) showPreview(activity);
    }
    @Override public void onActivityPaused(Activity activity) {
        if (permissionCheck.active() && permissionActivity.get() == activity) cancelPermissionCheck();
        if (panel != null && panel.owns(activity)) panel.pause();
        if (preview != null && preview.owns(activity)) preview.pause();
        if (foreground.get() == activity) { foreground.clear(); main.removeCallbacks(scan); }
    }
    @Override public void onActivityDestroyed(Activity activity) {
        createdActivities = Math.max(0, createdActivities - 1);
        boolean rebuilding = activity.isChangingConfigurations();
        if (preview != null && preview.owns(activity)) {
            closePreview();
            if (rebuilding) previewRecreateUntil = SystemClock.elapsedRealtime() + 15000;
            else { previewWanted = false; previewHost.clear(); hostOrientation = NO_ORIENTATION; }
        }
        if (panel != null && panel.owns(activity)) closePanel();
        if (recordingPanel != null && recordingPanel.owns(activity)) closePanel();
        if (rebuilding) { if (cruiseActivity.get() == activity) cruiseActivity.clear(); return; }
        String cast = session.castRequest();
        if (cast != null) {
            if (activity == cruiseActivity.get()) endCast(cast, false, "巡航页面已退出");
            else if (activity == origin.get() && !usable(cruiseActivity.get())
                    && (session.cast() == DirectSession.Cast.CHECKING_VEHICLE || session.cast() == DirectSession.Cast.WAITING_DISPLAY))
                endCast(cast, false, "车辆页面已退出");
        }
        // The host's last activity is gone: the user left the app, and the display goes with it.
        if (createdActivities == 0 && session.displayRunning()) endDisplay(session.displayRequest(), "九号出行已退出");
    }
    /** Visible activities, reported to the module with a short grace so a rotation or a page switch does not read as leaving. */
    private final Runnable visibility = this::reportVisibility;
    private void reportVisibility() { frames.setHostVisible(visibleActivities > 0); }
    @Override public void onActivityStarted(Activity activity) { visibleActivities++; main.removeCallbacks(visibility); visibility.run(); }
    @Override public void onActivityStopped(Activity activity) {
        visibleActivities = Math.max(0, visibleActivities - 1); main.removeCallbacks(visibility); main.postDelayed(visibility, 600);
    }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        if (activity == previewHost.get() && session.displayRequest() != null) state.putString(PENDING_DISPLAY, session.displayRequest());
    }
    // ---------------------------------------------------------------- update prompt
    /** A few asks after the host comes up: the first answer may predate the module's lookup, so ask again a little later. */
    private void scheduleUpdateCheck() {
        if (updateAttempts >= 3) return;
        main.removeCallbacks(updateCheck); main.postDelayed(updateCheck, updateAttempts == 0 ? 3000 : updateAttempts == 1 ? 20000 : 60000);
    }
    private final Runnable updateCheck = this::runUpdateCheck;
    private void runUpdateCheck() {
        if (updateAttempts >= 3) return;
        updateAttempts++;
        frames.checkUpdate(false, result -> {
            update = result;
            if (System.currentTimeMillis() - result.getLong("checked_at") >= UpdateCheck.INTERVAL_MS) scheduleUpdateCheck();
            Activity activity = foreground.get(); if (usable(activity)) promptUpdate(activity);
        }, error -> scheduleUpdateCheck());
    }
    /** Once per process and version, on the vehicle page only: the newer release and the page to get it from. No download. */
    private void promptUpdate(Activity activity) {
        Bundle known = update; if (known == null || !known.getBoolean("newer")) return;
        String version = known.getString("latest", ""), url = known.getString("url", UpdateCheck.RELEASES_URL);
        if (version.isEmpty() || version.equals(updatePrompted) || !injector.rowIn(activity)) return;
        updatePrompted = version; frames.report("UPDATE prompt " + version);
        UpdateDialog.show(activity, new MirrorUi(activity, reference(activity, null)), version, url);
    }
    private void openUrl(Activity activity, String url) { UpdateDialog.open(activity, url); }
    // ---------------------------------------------------------------- input injection refused
    private void inputDenied(String error) {
        if (inputDeniedPrompted) return;
        Activity activity = foreground.get();
        if (usable(activity)) promptInputDenial(activity); else pendingInputDenial = error;
    }
    /** Xiaomi / HyperOS gate shell injection behind the developer option "USB debugging (security settings)"; that is the way out. */
    private void promptInputDenial(Activity activity) {
        if (inputDeniedPrompted || !usable(activity)) return;
        inputDeniedPrompted = true; pendingInputDenial = null; frames.report("INPUT denied prompt");
        MirrorUi theme = new MirrorUi(activity, reference(activity, null));
        int pad = MirrorUi.dp(activity, 20);
        TextView title = new TextView(activity); title.setText("系统拒绝触摸注入"); title.setTextSize(20); title.setTextColor(theme.text);
        title.setPadding(pad, pad, pad, pad / 2);
        TextView body = new TextView(activity); body.setText("请在开发者选项中打开「USB 调试（安全设置）」，然后重新开始投屏。"); body.setTextSize(14); body.setTextColor(theme.secondary);
        body.setPadding(pad, pad / 2, pad, pad); body.setLineSpacing(MirrorUi.dp(activity, 3), 1);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setCustomTitle(title).setView(body)
                .setNegativeButton("关闭", null).setPositiveButton("打开开发者选项", (d, which) -> openDeveloperOptions(activity));
        // Root and a root-run Shizuku / Sui can keep uid 0 instead; the ADB Shizuku cannot, which the authorization dialog explains.
        if (frames.cachedSource().virtual()) builder.setNeutralButton("尝试不降权", (d, which) -> tryKeepRoot(activity));
        AlertDialog dialog = builder.create();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 24, false));
        for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which); if (button != null) button.setTextColor(theme.accent);
        }
    }
    /** Keep-root is saved once the session has ended; the module refuses the change while one runs, so the save is retried briefly. */
    private void tryKeepRoot(Activity activity) {
        String display = session.displayRequest();
        if (display != null) endDisplay(display, "投屏已停止");
        keepRootAttempt(activity, 0);
    }
    private void keepRootAttempt(Activity activity, int attempt) {
        Bundle args = new Bundle(); args.putBoolean("save", true); args.putBoolean("keep_root", true);
        frames.privilege(args, result -> { frames.report("PRIVILEGE keep root switched on from the injection prompt"); if (usable(activity)) toast(activity, "已开启不降权，请重新开始投屏"); },
                message -> {
                    if (attempt < 6 && message.contains("请先结束投屏")) main.postDelayed(() -> keepRootAttempt(activity, attempt + 1), 500);
                    else error(activity, "无法开启不降权", message);
                });
    }
    private void openDeveloperOptions(Activity activity) {
        try { activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { error(activity, "无法打开开发者选项", "请手动进入开发者选项。\n" + Ipc.error(e)); }
    }
    // ---------------------------------------------------------------- permission check before a start
    private void checkPermission(Activity activity, View anchor, Runnable start) {
        if (permissionCheck.active()) return;
        long generation = permissionCheck.begin(SystemClock.elapsedRealtime());
        permissionActivity = new WeakReference<>(activity);
        injector.refresh();
        permissionState(activity, anchor, start, generation, true);
    }
    private void permissionState(Activity activity, View anchor, Runnable start, long generation, boolean prepare) {
        if (!permissionCheck.owns(generation)) return;
        Bundle args = new Bundle(); if (prepare) args.putBoolean("prepare_start", true);
        frames.privilege(args, result -> {
            StartPermission.Check.Result decision = permissionCheck.accept(generation, result.getBoolean("start_allowed"),
                    result.getBoolean("start_pending"), SystemClock.elapsedRealtime());
            if (decision == StartPermission.Check.Result.STALE) return;
            if (decision == StartPermission.Check.Result.WAIT) {
                main.postDelayed(() -> permissionState(activity, anchor, start, generation, false), 300);
                return;
            }
            cancelPermissionCheck();
            if (!usable(activity) || foreground.get() != activity) return;
            if (decision == StartPermission.Check.Result.START) start.run();
            else if (decision == StartPermission.Check.Result.TIMEOUT)
                permissionRequired(activity, anchor, "授权检查超时", "请重新点击，或到设置检查授权服务状态。");
            else permissionRequired(activity, anchor, "请先完成授权", result.getString("start_permission_message",
                    "请先到设置 → 授权方式申请权限，然后重新开始。"));
        }, error -> {
            if (!permissionCheck.owns(generation)) return;
            cancelPermissionCheck();
            if (usable(activity) && foreground.get() == activity)
                permissionRequired(activity, anchor, "暂时无法检查授权", error + "\n请先到设置检查模块连接与授权状态。");
        });
    }
    private void cancelPermissionCheck() {
        permissionCheck.cancel(); permissionActivity.clear();
        injector.refresh();
    }
    private void permissionRequired(Activity activity, View anchor, String heading, String message) {
        MirrorUi theme = new MirrorUi(activity, reference(activity, anchor));
        int pad = MirrorUi.dp(activity, 20);
        TextView title = new TextView(activity); title.setText(heading); title.setTextSize(20); title.setTextColor(theme.text);
        title.setPadding(pad, pad, pad, pad / 2);
        TextView body = new TextView(activity); body.setText(message); body.setTextSize(14); body.setTextColor(theme.secondary);
        body.setPadding(pad, pad / 2, pad, pad); body.setLineSpacing(MirrorUi.dp(activity, 3), 1);
        AlertDialog dialog = new AlertDialog.Builder(activity).setCustomTitle(title).setView(body)
                .setNegativeButton("取消", null).setPositiveButton("去设置", (d, which) -> {
                    if (usable(activity)) PrivilegeDialog.show(activity, frames, reference(activity, anchor));
                }).create();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 24, false));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(theme.accent);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(theme.accent);
    }
    // ---------------------------------------------------------------- settings
    private void promptStop(Activity activity, View anchor) {
        boolean virtual = frames.cachedSource().virtual();
        MirrorUi theme = new MirrorUi(activity, reference(activity, anchor));
        TextView body = DialogContent.text(activity, theme, (virtual ? "虚拟显示器" : "投屏") + "正在运行，停止后才能更改设置。要强制停止吗？", 14);
        DialogContent.show(activity, theme, DialogContent.create(activity, theme, "设置", body, "强制停止", () -> {
            String display = session.displayRequest();
            if (display != null) endDisplay(display, virtual && !session.casting() ? "虚拟显示器已停止" : "投屏已停止");
            if (usable(activity) && !session.displayRunning()) settings(activity, anchor);
        }));
    }
    public void settings(Activity activity, View anchor) {
        if (!usable(activity)) return;
        View card = reference(activity, anchor);
        if (!frames.noticeAccepted()) { OpenSourceNoticeDialog.show(activity, card, frames, () -> settings(activity, anchor)); return; }
        if (permissionCheck.active()) cancelPermissionCheck();
        // A running picture locks the settings: the user stops it here or leaves them closed.
        if (session.displayRunning()) { promptStop(activity, anchor); return; }
        autostartPrompt(activity);
        DisplaySettings cached = frames.cachedSettings();
        MirrorUi theme = new MirrorUi(activity, card);
        LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density); layout.setPadding(pad, pad / 2, pad, 0);
        if (!frames.versionTested()) {
            TextView warning = new TextView(activity); warning.setTextColor(0xffe06c5c); warning.setTextSize(14);
            warning.setText("当前九号出行版本未测试，当前版本为: " + frames.targetVersion());
            LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(-1, -2); warningParams.bottomMargin = MirrorUi.dp(activity, 12);
            layout.addView(warning, warningParams);
        }
        TextView connection = new TextView(activity); connection.setTextColor(theme.secondary); connection.setTextSize(13);
        connection.setLineSpacing(MirrorUi.dp(activity, 3), 1);
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(-1, -2); connectionParams.bottomMargin = MirrorUi.dp(activity, 16);
        layout.addView(connection, connectionParams);
        // The utilities share one row, the four tools another; labels shrink before they wrap.
        Button authorization = new Button(activity); authorization.setText("授权方式"); theme.button(authorization, null);
        Button statistics = new Button(activity); statistics.setText("统计信息"); theme.button(statistics, null);
        // The module's own entry page: the permission check with the autostart and notification pages behind its rows.
        Button permissions = new Button(activity); permissions.setText("权限设置"); theme.button(permissions, null);
        permissions.setOnClickListener(v -> openModule(activity));
        if (!frames.serviceConnected()) connection.setText(frames.serviceStatus());
        buttonRow(activity, layout, 0, new Button[]{authorization, statistics, permissions});
        Button upgrade = new Button(activity); theme.button(upgrade, null); upgrade.setVisibility(View.GONE);
        LinearLayout.LayoutParams upgradeParams = new LinearLayout.LayoutParams(-1, -2); upgradeParams.topMargin = MirrorUi.dp(activity, 12);
        layout.addView(upgrade, upgradeParams);
        java.util.function.Consumer<Bundle> showUpdate = known -> {
            if (known == null || !known.getBoolean("newer")) { upgrade.setVisibility(View.GONE); return; }
            String version = known.getString("latest", ""), url = known.getString("url", UpdateCheck.RELEASES_URL);
            upgrade.setText("更新到 " + version); upgrade.setVisibility(View.VISIBLE); upgrade.setOnClickListener(v -> openUrl(activity, url));
        };
        showUpdate.accept(update);
        statistics.setOnClickListener(v->StatisticsDialog.show(activity,frames,card));
        Button widgets=new Button(activity);widgets.setText("控件管理");theme.button(widgets,null);
        Button encoder=new Button(activity);encoder.setText("设置覆盖");theme.button(encoder,null);
        Button hidden=new Button(activity);hidden.setText("隐藏功能");theme.button(hidden,null);
        Button touch=new Button(activity);touch.setText("触摸屏管理");theme.button(touch,null);
        Button drawing=new Button(activity);drawing.setText("绘制管理");theme.button(drawing,null);
        LinearLayout tools = buttonRow(activity, layout, 12, new Button[]{widgets, drawing, encoder, hidden, touch});
        widgets.setOnClickListener(v->WidgetSettingsDialog.show(activity,frames,card));
        drawing.setOnClickListener(v->DrawSettingsDialog.show(activity,frames,card));
        Button lamp=new Button(activity);lamp.setText("大灯管理");theme.button(lamp,null);
        Button board=new Button(activity);board.setText("BMS 管理");theme.button(board,null);
        Button reads=new Button(activity);reads.setText("数据读取设置");theme.button(reads,null);
        LinearLayout devices = buttonRow(activity, layout, 12, new Button[]{lamp, board, reads});
        lamp.setOnClickListener(v->frames.lampSettings(activity,theme.dark));
        board.setOnClickListener(v->frames.bmsSettings(activity,theme.dark));
        reads.setOnClickListener(v->ReadSettingsDialog.show(activity,frames,card));
        encoder.setOnClickListener(v->EncoderOverrideDialog.show(activity,frames,card,!session.displayRunning()));
        hidden.setOnClickListener(v->HiddenFeatureDialog.show(activity,frames,card));
        touch.setOnClickListener(v->frames.touchSettings(activity,theme.dark));
        TextView appLabel = new TextView(activity); appLabel.setText("启动应用"); appLabel.setTextColor(theme.secondary); appLabel.setPadding(0, pad / 2, 0, pad / 3); layout.addView(appLabel);
        ChoiceSpinner appPicker = new ChoiceSpinner(activity, theme, "选择启动应用");
        ArrayList<Bundle> apps = new ArrayList<>();
        AppPickerAdapter appNames = new AppPickerAdapter(activity, frames, theme, apps);
        appPicker.setAdapter(appNames); appPicker.setEnabled(false);
        appPicker.setBackground(theme.background(activity, theme.input, 14, false)); appPicker.setClipToOutline(true);
        layout.addView(appPicker, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout dimensions=fieldRow(activity,layout),virtualDimensions=fieldRow(activity,layout),options=fieldRow(activity,layout),dpiColumn=fieldColumn(activity,options);
        EditText width=field(activity,fieldColumn(activity,dimensions),"整帧宽度",cached.width,theme),
                height=field(activity,fieldColumn(activity,dimensions),"整帧高度",cached.height,theme),
                virtualWidth=field(activity,fieldColumn(activity,virtualDimensions),"虚拟屏宽度",cached.virtualWidth,theme),
                virtualHeight=field(activity,fieldColumn(activity,virtualDimensions),"虚拟屏高度",cached.virtualHeight,theme),
                dpi=field(activity,dpiColumn,"DPI",cached.dpi,theme);
        // The frame follows the vehicle's cast configuration; the fields stay for the read-back text but are never shown.
        // Virtual size, density, colours and the DPI switches moved to the override dialog; the fields stay for the read-back only.
        dimensions.setVisibility(View.GONE);virtualDimensions.setVisibility(View.GONE);options.setVisibility(View.GONE);
        LinearLayout colorColumn = fieldColumn(activity, options);
        fieldLabel(activity, colorColumn, "深色背景", theme);
        BandColorButton topColor = new BandColorButton(activity, theme, cached.backgroundColor);
        colorColumn.addView(topColor, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout lightColumn = fieldColumn(activity, options);
        fieldLabel(activity, lightColumn, "浅色背景", theme);
        BandColorButton lightColor = new BandColorButton(activity, theme, cached.lightBackgroundColor);
        lightColumn.addView(lightColor, new LinearLayout.LayoutParams(-1, -2));
        android.widget.CheckBox keepDpi=new android.widget.CheckBox(activity);keepDpi.setText("保持 DPI");keepDpi.setTextColor(theme.text);keepDpi.setTextSize(15);
        keepDpi.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));keepDpi.setChecked(cached.keepPhoneDpi);
        keepDpi.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);keepDpi.setIncludeFontPadding(false);keepDpi.setPadding(0,MirrorUi.dp(activity,8),0,MirrorUi.dp(activity,8));
        LinearLayout dpiRow=new LinearLayout(activity);dpiRow.setGravity(Gravity.CENTER_VERTICAL);
        dpiRow.addView(keepDpi,new LinearLayout.LayoutParams(0,-2,1));
        layout.addView(dpiRow,new LinearLayout.LayoutParams(-1,-2));dpiRow.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(activity); scroll.addView(layout);
        TextView title = new TextView(activity); title.setText("设置"); title.setTextSize(20); title.setTextColor(theme.text);
        title.setPadding(pad, pad, pad, pad / 2);
        SettingsFooter footer = new SettingsFooter(activity, theme);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); body.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity).setCustomTitle(title).setView(body).create();
        // Show cached fields and diagnostics first. No service, Binder call or capture worker gates this window.
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 24, false));
        footer.about.setOnClickListener(v -> AboutDialog.show(activity, card, frames));
        footer.close.setOnClickListener(v -> dialog.dismiss());
        Button save = footer.save; save.setEnabled(false);
        boolean[] loaded = {false};
        Runnable showMode = () -> {
            // Cards and the touch panel belong to the virtual display; the drawn picture has its own page; the capture has neither.
            PictureSource source = frames.cachedSource();
            for (View field : new View[]{appLabel,appPicker,touch,widgets})field.setVisibility(source.virtual()?View.VISIBLE:View.GONE);
            drawing.setVisibility(source.draws()?View.VISIBLE:View.GONE);
            reads.setVisibility(source.captures()?View.GONE:View.VISIBLE);
            alignRow(activity, tools); alignRow(activity, devices);
        };
        showMode.run();
        Runnable read = () -> {
            loaded[0] = false; save.setEnabled(false); appPicker.setEnabled(false);
            String w=width.getText().toString(),h=height.getText().toString(),d=dpi.getText().toString(),vw=virtualWidth.getText().toString(),vh=virtualHeight.getText().toString();
            int color = topColor.color(), light = lightColor.color(); boolean keep = keepDpi.isChecked();
            connection.setText("正在读取已保存参数；当前显示缓存或未保存的输入。");
            frames.getSettings(config -> {
                if (!usable(activity) || !dialog.isShowing()) return;
                DisplaySettings value = Ipc.settings(config);
                boolean edited = !w.equals(width.getText().toString()) || !h.equals(height.getText().toString()) || !d.equals(dpi.getText().toString())
                        ||!vw.equals(virtualWidth.getText().toString())||!vh.equals(virtualHeight.getText().toString())||color!=topColor.color()||light!=lightColor.color()||keep!=keepDpi.isChecked();
                if (!edited) { width.setText(String.valueOf(value.width)); height.setText(String.valueOf(value.height)); dpi.setText(String.valueOf(value.dpi));
                    virtualWidth.setText(String.valueOf(value.virtualWidth));virtualHeight.setText(String.valueOf(value.virtualHeight));topColor.setBandColor(value.backgroundColor);lightColor.setBandColor(value.lightBackgroundColor);keepDpi.setChecked(value.keepPhoneDpi); }
                ArrayList<Bundle> catalog = Ipc.parcelableList(config, AppCatalog.APPS, Bundle.class);
                String selected = config.getString(AppCatalog.SELECTED, "");
                apps.clear(); apps.add(null);
                int selectedIndex = 0;
                if (catalog != null) for (Bundle app : catalog) {
                    apps.add(app);
                    if (selected.equals(app.getString("component"))) selectedIndex = apps.size() - 1;
                }
                appNames.loaded(); appPicker.setSelection(selectedIndex);
                boolean idle = !session.displayRunning();
                loaded[0] = true; save.setEnabled(idle);
                appPicker.setEnabled(idle);
                width.setEnabled(idle);height.setEnabled(idle);dpi.setEnabled(idle);virtualWidth.setEnabled(idle);virtualHeight.setEnabled(idle);topColor.setEnabled(idle);lightColor.setEnabled(idle);keepDpi.setEnabled(idle);
                showMode.run();
                connection.setText(!frames.cachedSource().virtual() ? "当前方式：" + frames.cachedSource().label() + "。" + (frames.cachedSource().captures() ? "\n开始时通过系统窗口选择单个应用或整个屏幕。" : "")
                        : "已读取: 整帧 "+value.width+" × "+value.height+"，虚拟屏 "+value.virtualWidth+" × "+value.virtualHeight+"，"+value.dpi+" DPI"+(value.keepPhoneDpi?"，保持手机 DPI":"")+"。"+(edited?"\n保留你刚输入的内容。":"")
                        + (apps.size() == 1 ? "\n请选择启动应用并允许读取应用列表。" : selectedIndex == 0
                            ? (selected.isEmpty() ? "\n请先选择启动应用。" : "\n原应用入口已不可用，请重新选择。") : "")
                        + (idle ? "" : "\n请先关闭虚拟显示器再修改。")
                        + (frames.compatibility().isEmpty() ? "" : "\n" + frames.compatibility()));
            }, error -> {
                if (!usable(activity) || !dialog.isShowing()) return;
                connection.setText(error + "\n当前输入尚未保存，可重新打开设置或查看日志。");
            });
        };
        appPicker.setOpenAction(() -> {
            if (!loaded[0] || session.displayRunning()) return;
            int index = appPicker.getSelectedItemPosition();
            String selected = index > 0 && index < apps.size() ? apps.get(index).getString("component", "") : "";
            frames.pickLaunchApp(activity, theme.dark, selected, app -> {
                if (!usable(activity) || !dialog.isShowing() || session.displayRunning()) return;
                String component = app.getString("component", ""); if (component.isEmpty()) return;
                int match = -1;
                for (int i = 1; i < apps.size(); i++) if (component.equals(apps.get(i).getString("component"))) { match = i; break; }
                if (match < 0) { apps.add(app); match = apps.size() - 1; } else apps.set(match, app);
                appNames.loaded(); appPicker.setSelection(match);
                // Only the app choice changes. Width, height, DPI and band edits remain unsaved in this dialog.
            });
        });
        authorization.setOnClickListener(v -> PrivilegeDialog.show(activity, frames, card, selected -> {
            if (!dialog.isShowing()) return;
            showMode.run(); read.run();
        }));
        footer.logs.setOnClickListener(v -> entryDetails(activity, anchor));
        save.setOnClickListener(v -> {
            if (!loaded[0]) return;
            if (session.displayRunning()) { toast(activity, "请先关闭虚拟显示器再修改设置"); return; }
            if (!frames.cachedSource().virtual()) { dialog.dismiss(); return; }
            int index = appPicker.getSelectedItemPosition();
            if (index <= 0 || index >= apps.size()) { toast(activity, "请先选择启动应用"); return; }
            String selected = apps.get(index).getString("component");
            try {
                // Display parameters are edited in the override dialog; this save keeps the stored ones inside the current frame. The
                // cache, not what this dialog read when it opened: the override dialog may have saved since.
                DisplaySettings next = frames.cachedSettings().withFrame(frames.frameWidth(), frames.frameHeight());
                save.setEnabled(false); appPicker.setEnabled(false);
                width.setEnabled(false);height.setEnabled(false);dpi.setEnabled(false);virtualWidth.setEnabled(false);virtualHeight.setEnabled(false);topColor.setEnabled(false);lightColor.setEnabled(false);keepDpi.setEnabled(false);connection.setText("正在保存…");
                frames.saveSettings(next, selected, error -> {
                    if (!usable(activity) || !dialog.isShowing()) return;
                    if (error == null) { dialog.dismiss(); toast(activity, "启动应用和显示参数已保存"); }
                    else {
                        loaded[0] = false;
                        width.setEnabled(true);height.setEnabled(true);dpi.setEnabled(true);virtualWidth.setEnabled(true);virtualHeight.setEnabled(true);topColor.setEnabled(true);lightColor.setEnabled(true);keepDpi.setEnabled(true);
                        connection.setText(error + "\n请重新读取后再保存。"); error(activity, "保存失败", error);
                    }
                });
            } catch (IllegalArgumentException e) { error(activity, "无法保存", e instanceof NumberFormatException ? "请输入整数" : String.valueOf(e.getMessage())); }
        });
        read.run();
        // The settings read owns the metadata slot first; a lookup not yet answered this process is asked for a moment later.
        if (update == null) main.postDelayed(() -> { if (dialog.isShowing()) frames.checkUpdate(false, known -> { update = known; if (dialog.isShowing()) showUpdate.accept(known); }, error -> {}); }, 1500);
    }
    /** One row of equal-width buttons; labels shrink before they wrap so each stays on one line. */
    private static LinearLayout buttonRow(Activity activity, LinearLayout layout, int topDp, Button[] buttons) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setBaselineAligned(false);
        for (Button button : buttons) {
            button.setMaxLines(1); button.setPadding(MirrorUi.dp(activity, 3), button.getPaddingTop(), MirrorUi.dp(activity, 3), button.getPaddingBottom());
            button.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); if (row.getChildCount() > 0) params.setMarginStart(MirrorUi.dp(activity, 8));
            row.addView(button, params);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = MirrorUi.dp(activity, topDp);
        layout.addView(row, params);
        return row;
    }
    /** The gap sits between visible buttons only: hiding a row's first button must not indent the one that takes its place. */
    private static void alignRow(Activity activity, LinearLayout row) {
        boolean first = true;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i); if (child.getVisibility() == View.GONE) continue;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            int margin = first ? 0 : MirrorUi.dp(activity, 8);
            if (params.getMarginStart() != margin) { params.setMarginStart(margin); child.setLayoutParams(params); }
            first = false;
        }
    }
    private static LinearLayout fieldRow(Activity activity, LinearLayout layout) {
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }
    private static LinearLayout fieldColumn(Activity activity, LinearLayout row) {
        LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) params.setMarginStart(MirrorUi.dp(activity, 12));
        row.addView(column, params); return column;
    }
    private static EditText field(Activity activity, LinearLayout layout, String label, int value, MirrorUi theme) {
        int pad = MirrorUi.dp(activity, 12);
        fieldLabel(activity, layout, label, theme);
        EditText edit = new EditText(activity); edit.setSingleLine(true); edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setMinimumHeight(MirrorUi.dp(activity, 52));
        edit.setTextColor(theme.text); edit.setTextSize(18); edit.setBackgroundTintList(null);
        edit.setBackground(theme.background(activity, theme.input, 12, false)); edit.setPadding(pad, pad, pad, pad);
        edit.setText(String.valueOf(value)); layout.addView(edit, new LinearLayout.LayoutParams(-1, -2)); return edit;
    }
    private static void fieldLabel(Activity activity, LinearLayout layout, String label, MirrorUi theme) {
        int pad = MirrorUi.dp(activity, 12);
        TextView text = new TextView(activity); text.setText(label); text.setTextColor(theme.secondary); text.setTextSize(13);
        text.setSingleLine(true); text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setPadding(0, pad, 0, pad / 2); layout.addView(text);
    }
    /** MIUI per-app permission editor first, then the autostart manager, then the plain app details page. */
    private static boolean autostartPrompted;
    /** Once per process: the system refused to bind the module service; say what usually causes that on this phone. */
    private void autostartPrompt(Activity activity) {
        if (autostartPrompted || frames.serviceConnected() || !frames.serviceBindRefused() || !usable(activity)) return;
        autostartPrompted = true;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("模块服务未连接").setMessage(frames.serviceAdvice()).setNegativeButton("关闭", null);
        if (ServiceBridge.hyperOs()) builder.setPositiveButton("打开设置", (d, w) -> openAutostart(activity));
        builder.show();
    }
    private void openAutostart(Activity activity) { AutostartPages.open(activity, frames::report); }
    /** The module APK's exported launcher activity, started from the host by component; forceQueryable keeps it visible. */
    private void openModule(Activity activity) {
        try {
            activity.startActivity(new Intent().setClassName(Protocol.MODULE, ModuleActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            frames.report("SETTINGS module entry opened");
        } catch (RuntimeException e) { error(activity, "无法打开 Ninebot Enhance", Ipc.error(e)); frames.report("SETTINGS module entry failed " + e.getClass().getSimpleName()); }
    }
    public void entryDetails(Activity activity, View anchor) {
        if (!usable(activity)) return;
        frames.report("DIRECT ENTRY " + VehicleCardInjector.entryInfo(injector.navigationCard()));
        LogDialog.show(activity, frames, reference(activity, anchor));
    }
    /** The theme reference for dialogs: the anchor the row was placed against, else the activity content. */
    private static View reference(Activity activity, View anchor) {
        return anchor != null ? anchor : activity.findViewById(android.R.id.content);
    }
    /** Session ends that are not failures stay a toast; everything else is an error the user can read and copy. */
    private static final Set<String> PLAIN_ENDS = Set.of("投屏已停止", "虚拟显示器已停止", "巡航页面已退出", "车辆页面已退出", "九号出行已退出");
    private void notify(String message) {
        if (message == null || PLAIN_ENDS.contains(message) || message.startsWith("巡航投屏已结束")) toast(message); else error(message);
    }
    private void error(String message) { Activity visible = foreground.get(); if (usable(visible)) error(visible, "出错了", message); }
    private void error(Activity activity, String title, String message) { ErrorDialog.show(activity, reference(activity, null), title, message); }
    private void toast(String message) { Activity visible = foreground.get(); if (usable(visible)) toast(visible, message); }
    private static boolean usable(Activity activity) { return activity != null && !activity.isFinishing() && !activity.isDestroyed(); }
    private static void toast(Context context, String value) { Toast.makeText(context, value, Toast.LENGTH_LONG).show(); }
}
