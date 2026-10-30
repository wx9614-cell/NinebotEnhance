package dev.ichinomiya.ninebotenhance.hook;

import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.navi.NaviApps;
import dev.ichinomiya.ninebotenhance.platform.ModuleResources;
import dev.ichinomiya.ninebotenhance.ui.DirectCastController;
import dev.ichinomiya.ninebotenhance.hook.OfficialCompatibility;

import android.app.Application;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.os.Binder;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedModule;

/** API 101 entry, compatible with subsequent API versions. No legacy Xposed API is mixed in. */
public final class MirrorModule extends XposedModule {
    private final AtomicBoolean installed = new AtomicBoolean();
    private StatisticsHooks statistics;
    private FeatureHooks feature;
    private EncodingHooks encoding;
    private TirePressureHooks tirePressure;
    private VehicleHooks vehicle;
    private NaviSender naviSender;
    private NaviAppHooks naviApps;
    private final Set<Class<?>> seen = ConcurrentHashMap.newKeySet();
    private final Set<Executable> hooked = ConcurrentHashMap.newKeySet();
    private final Set<Executable> called = ConcurrentHashMap.newKeySet();
    private final Set<ClassLoader> loaders = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Boolean> examining = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Integer> captureDepth = ThreadLocal.withInitial(() -> 0);
    private final Map<Object, Map<Method, int[]>> bitmapTemplates = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Boolean> startedManagers = Collections.synchronizedMap(new WeakHashMap<>());
    private FrameClient frames;
    private DirectCastController direct;
    private BroadcastReceiver externalCastReceiver;
    private String process;
    /** Set once Ninebot's Application is attached; the hooks and the vehicle page need nothing else. */
    private volatile boolean hostAttached;
    /** Whether the running build is one whose hook targets were verified by hand; others get a warning, not a disabled module. */
    private volatile boolean versionTested = true;
    private int scanCount;
    private final String[] seeds = {
        "cn.ninebot.capture.CaptureClient", "cn.ninebot.capture.VideoConfig", "cn.ninebot.capture.AbstractCaptureController",
        "cn.ninebot.capture.ViewToBitmapConvert", "cn.ninebot.capture.ViewToBitmapTimer",
        "cn.ninebot.capture.codec.BitmapToH264Encoder", "cn.ninebot.capture.codec.ViewToH264Encoder",
        "cn.ninebot.capture.codec.CodecCaptureController", "cn.ninebot.capture.codec.CodecEncoder",
        "cn.ninebot.capture.codec.CodecImageReaderEncoder", "cn.ninebot.capture.codec.VideoMediaCodec",
        "cn.ninebot.capture.encoder.LoopBitmapEncoder", "cn.ninebot.capture.encoder.ViewEncoder",
        "cn.ninebot.capture.encoder.FFmpegBitmapEncoder", "cn.ninebot.capture.encoder.FFmpegViewEncoder",
        "cn.ninebot.capture.encoder.FFmpegImageReaderEncoder", "cn.ninebot.capture.encoder.ImageReaderEncoder",
        "cn.ninebot.capture.mjpeg.MJpegEncoder", "cn.ninebot.capture.mjpeg.MJpegViewEncoder",
        "cn.ninebot.capture.mpeg2.Mpeg2Encoder", "cn.ninebot.capture.mpeg2.ViewToMpeg2Encoder", "cn.ninebot.capture.mpeg2.NbFFmpegFrameRecorder",
        "cn.ninebot.capture.AbstractCaptureController$drawViewRunnable$1",
        "cn.ninebot.capture.encoder.ViewEncoder$1$getBitmap$2$1",
        "cn.ninebot.capture.ViewToBitmapConvert$convert$1",
        "cn.ninebot.mapcapture.DeviceScreenCastManager", "cn.ninebot.mapcapture.DeviceScreenCastRequest",
        "cn.ninebot.mapcapture.NBBluetoothRtpSender", "cn.ninebot.library.screencast.BluetoothRtpSender",
        StatisticsHooks.WIFI_SENDER, StatisticsHooks.FRAME_SENDER, StatisticsHooks.SEND_QUEUE, StatisticsHooks.UDP_SESSION, StatisticsHooks.BLE_WRITER,
        StatisticsHooks.ENCODE_SINKS[0], StatisticsHooks.ENCODE_SINKS[1], StatisticsHooks.ENCODE_SINKS[2], FeatureHooks.VIEW_HOLDER, FeatureHooks.VIEW_MODELS, FeatureHooks.VISIBILITY_STORE, FeatureHooks.NAVIGATION_CARD,
        "cn.ninebot.device.motor.navi.DashNaviDataMessenger",
        "cn.ninebot.device.motor.navi.DashNaviDataMessenger$Companion",
        "cn.ninebot.device.motor.navi.CruiseModeActivity",
        "cn.ninebot.device.motor.navi.CruiseModeActivity$Companion",
        "cn.ninebot.device.motor.navi.ScreenCastHelper",
        TirePressureHooks.PARSER, TirePressureHooks.MANAGER, VehicleHooks.DEVICE
    };

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        process = param.getProcessName(); ModuleResources.initialize(getModuleApplicationInfo().sourceDir);
    }
    /** Ninebot runs helper processes (":pushcore" and others); only the main process shows the vehicle page and needs the module. */
    private boolean mainProcess() { return process == null || process.equals(Protocol.TARGET); }
    @Override public void onPackageLoaded(PackageLoadedParam param) {
        if (OfficialCompatibility.enabled()) return;
        if (Protocol.TARGET.equals(param.getPackageName())) { if (mainProcess()) install(param.getDefaultClassLoader()); }
        else if (NaviApps.supported(param.getPackageName())) naviApps(param.getPackageName()).install(param.getDefaultClassLoader());
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if (OfficialCompatibility.enabled()) return;
        if (NaviApps.supported(param.getPackageName())) { naviApps(param.getPackageName()).ready(param.getClassLoader()); return; }
        if (!Protocol.TARGET.equals(param.getPackageName()) || !mainProcess()) return;
        install(param.getClassLoader()); loaders.add(param.getClassLoader());
    }
    /** Navigation apps get their own observe-only probe; the Ninebot hooks are never installed there. */
    private synchronized NaviAppHooks naviApps(String pkg) {
        if (naviApps == null) naviApps = new NaviAppHooks(this, pkg, process);
        return naviApps;
    }
    private void install(ClassLoader loader) {
        loaders.add(loader);
        if (!installed.compareAndSet(false, true)) return;
        frames = new FrameClient(process == null ? Protocol.TARGET : process);
        statistics = new StatisticsHooks(this, frames);
        feature = new FeatureHooks(this, frames);
        encoding = new EncodingHooks(this, frames);
        tirePressure = new TirePressureHooks(this, frames, () -> hostAttached, this::updateSummary);
        vehicle = new VehicleHooks(this, frames, () -> hostAttached, this::updateSummary);
        frames.setVehicleReader(vehicle::pulse, vehicle::stop, vehicle::summary);
        naviSender = new NaviSender(frames, vehicle::deviceClass, () -> hostAttached);
        frames.setNaviTest(naviSender::pulse, naviSender::stop);
        frames.setNaviLive(naviSender::pulseLive);
        frames.setThemeSender(naviSender::pulseTheme);
        encoding.install();
        direct = new DirectCastController(frames, () -> hostAttached);
        frames.report("MODULE " + Protocol.VERSION + " loaded API=" + getApiVersion() + "; target=" + HookCatalog.versions() + "; direct cruise entry");
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Context context = (Context) chain.getArg(0);
                    if (Protocol.TARGET.equals(context.getPackageName())) {
                        frames.attach(context);
                        android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(Protocol.TARGET, 0);
                        versionTested = HookCatalog.compatible(info.versionName, info.getLongVersionCode()); hostAttached = true;
                        frames.targetVersion(info.versionName, info.getLongVersionCode(), versionTested);
                        if (chain.getThisObject() instanceof Application) {
                            Application app = (Application) chain.getThisObject();
                            direct.attach(app);
                            registerExternalCastReceiver(app);
                        }
                        frames.report("TARGET " + info.versionName + "/" + info.getLongVersionCode() + " tested=" + versionTested);
                        loaders.add(context.getClassLoader());
                        if (chain.getThisObject() != null) loaders.add(chain.getThisObject().getClass().getClassLoader());
                        frames.report("APPLICATION attached after wrapper");
                        scheduleScan(); verifyCatalog(context);
                    }
                } catch (Throwable e) { frames.report("ATTACH diagnostic failed: " + e.getClass().getSimpleName()); }
                return result;
            });
        } catch (Throwable e) { log(Log.ERROR, Protocol.TAG, "Application attach hook failed", e); }
        try {
            hook(LayoutInflater.class.getDeclaredMethod("inflate", int.class, ViewGroup.class)).intercept(chain -> {
                ViewGroup root = (ViewGroup)chain.getArg(1);
                int previousChildren = root == null ? 0 : root.getChildCount();
                Object result = chain.proceed();
                try {
                    View inflated = result instanceof View ? (View)result : null;
                    if (root != null && root.getChildCount() > previousChildren) inflated = root.getChildAt(previousChildren);
                    if (inflated != null) direct.inflated((int)chain.getArg(0), inflated);
                } catch (RuntimeException e) { frames.report("DIRECT inflate observer " + e.getClass().getSimpleName()); }
                return result;
            });
        } catch (Throwable e) { frames.report("DIRECT entry hook 2arg unavailable " + e.getClass().getSimpleName()); }
        try {
            hook(LayoutInflater.class.getDeclaredMethod("inflate", int.class, ViewGroup.class, boolean.class)).intercept(chain -> {
                ViewGroup root = (ViewGroup)chain.getArg(1);
                boolean attach = (boolean)chain.getArg(2);
                int previousChildren = root == null ? 0 : root.getChildCount();
                Object result = chain.proceed();
                try {
                    View inflated = result instanceof View ? (View)result : null;
                    if (attach && root != null && root.getChildCount() > previousChildren) inflated = root.getChildAt(previousChildren);
                    if (inflated != null) direct.inflated((int)chain.getArg(0), inflated);
                } catch (RuntimeException e) { frames.report("DIRECT inflate observer " + e.getClass().getSimpleName()); }
                return result;
            });
        } catch (Throwable e) { frames.report("DIRECT entry hook failed " + e.getClass().getSimpleName()); }
        try {
            hook(View.class.getDeclaredMethod("draw", Canvas.class)).intercept(chain -> {
                if (hostAttached && captureDepth.get() > 0) {
                    View view = (View) chain.getThisObject();
                    try {
                        Canvas canvas = (Canvas) chain.getArg(0);
                        int width = view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth();
                        int height = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
                        if (frames.draw(canvas, width > 0 ? width : canvas.getWidth(), height > 0 ? height : canvas.getHeight())) return null;
                    } catch (RuntimeException e) { frames.report("DRAW fallback: " + e.getClass().getSimpleName()); }
                }
                return chain.proceed();
            });
        } catch (Throwable e) { frames.report("View.draw unavailable: " + e.getClass().getSimpleName()); }
        // Observe lazy class loading without globally dumping classes or changing loader behavior.
        try {
            hook(ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class)).intercept(chain -> {
                Object result = chain.proceed();
                String name = (String) chain.getArg(0);
                if (!examining.get() && result instanceof Class<?> && HookPolicy.interestingClass(name)) inspect((Class<?>) result);
                return result;
            });
        } catch (Throwable e) { frames.report("ClassLoader observer unavailable; using bounded scans: " + e.getClass().getSimpleName()); }
    }
    private static int senderUid(BroadcastReceiver receiver) {
        try {
            Method method = BroadcastReceiver.class.getMethod("getSentFromUid");
            Object value = method.invoke(receiver);
            if (value instanceof Integer && (Integer) value >= 0) return (Integer) value;
        } catch (Throwable ignored) { }
        return Binder.getCallingUid();
    }
    private void registerExternalCastReceiver(Application app) {
        if (externalCastReceiver != null) return;
        externalCastReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !Protocol.EXTERNAL_CAST_CONTRACT.equals(intent.getStringExtra(Protocol.EXTERNAL_CAST_CONTRACT))) return;
                int senderUid = senderUid(this);
                String[] packages = context.getPackageManager().getPackagesForUid(senderUid);
                boolean allowed = false;
                if (packages != null) for (String name : packages) if (Protocol.EXTERNAL_CAST_PACKAGE.equals(name)) { allowed = true; break; }
                if (!allowed) { frames.report("EXTERNAL rejected sender uid=" + senderUid); return; }
                String request = intent.getStringExtra(Protocol.EXTERNAL_CAST_REQUEST);
                String selected = intent.getStringExtra(Protocol.EXTERNAL_CAST_APP);
                if (Protocol.EXTERNAL_CAST_BEGIN.equals(intent.getAction())) direct.externalCast(context, request, selected);
                else if (Protocol.EXTERNAL_CAST_STOP.equals(intent.getAction())) direct.externalStop(request);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Protocol.EXTERNAL_CAST_BEGIN); filter.addAction(Protocol.EXTERNAL_CAST_STOP);
        app.registerReceiver(externalCastReceiver, filter, Context.RECEIVER_EXPORTED);
        frames.report("EXTERNAL control receiver ready contract=v1");
    }
    private void scheduleScan() {
        new Handler(Looper.getMainLooper()).post(this::scan);
    }
    /** Resolve every catalogued class, method and resource once; the outcome goes to the log, the summary and the settings page. */
    private void verifyCatalog(Context context) {
        Thread worker = new Thread(() -> {
            try {
                HookCatalog.Report report = HookCatalog.verify(HookCatalog.ALL, name -> {
                    for (ClassLoader loader : new ArrayList<>(loaders)) try { return Class.forName(name, false, loader); } catch (Throwable ignored) {}
                    return null;
                }, target -> context.getResources().getIdentifier(target.member(), target.kind(), Protocol.TARGET));
                frames.compatibility(report.text()); frames.report("HOOK CHECK " + report.text()); updateSummary();
            } catch (Throwable e) { frames.report("HOOK CHECK failed " + e.getClass().getSimpleName()); }
        }, "Ninebot-HookCheck"); worker.setDaemon(true); worker.start();
    }
    private void scan() {
        if (examining.get()) return;
        examining.set(true);
        try {
            for (ClassLoader loader : new ArrayList<>(loaders)) for (String name : seeds) {
                try { inspectUnchecked(Class.forName(name, false, loader)); } catch (Throwable ignored) {}
            }
        } finally { examining.set(false); }
        updateSummary();
        if (++scanCount < 60) new Handler(Looper.getMainLooper()).postDelayed(this::scan, 2000);
    }
    private void inspect(Class<?> type) {
        examining.set(true);
        try { inspectUnchecked(type); }
        catch (Throwable e) { frames.report("INSPECT " + type.getName() + " " + e.getClass().getSimpleName()); }
        finally { examining.set(false); }
    }
    private void inspectUnchecked(Class<?> type) {
        if (!HookPolicy.interestingClass(type.getName())) return;
        boolean firstSeen = seen.add(type);
        if (seen.size() > 2000) return;
        // A class can be discovered before its dependent methods are ready in a lazily loaded Ninebot dex.
        // Revisit it on bounded rescans; every concrete hook installer remains idempotent via its own set.
        if (TirePressureHooks.interesting(type.getName())) { tirePressure.inspect(type); updateSummary(); return; }
        if (VehicleHooks.interesting(type.getName())) { vehicle.inspect(type); updateSummary(); return; }
        if (FeatureHooks.interesting(type.getName())) { feature.inspect(type); updateSummary(); return; }
        if (type.getName().equals(FeatureHooks.NAVI_MESSENGER_COMPANION)) feature.installTheme(type);
        statistics.inspect(type);
        encoding.inspect(type);
        boolean capture = HookPolicy.captureClass(type.getName());
        int described = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) || type.isInterface()) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (HookPolicy.vehiclePowerMethod(type.getName(), method.getName())) {
                installPowerObserver(method); continue;
            }
            boolean imageArgument = false, viewArgument = false;
            for (Class<?> p : parameters) { imageArgument |= p == Bitmap.class; viewArgument |= View.class.isAssignableFrom(p) || p == Canvas.class; }
            boolean bitmapReturn = method.getReturnType() == Bitmap.class;
            String name = method.getName();
            boolean scoped = capture && (imageArgument || bitmapReturn || viewArgument || HookPolicy.coroutineDrawingMethod(type.getName(), name)
                    || name.toLowerCase(Locale.ROOT).contains("drawview") || name.toLowerCase(Locale.ROOT).contains("getbitmap"));
            boolean trace = !capture && (name.startsWith("start") || name.startsWith("create") || name.startsWith("prepare")
                    || HookPolicy.terminalCastMethod(type.getName(), name)
                    || (type.getName().equals("cn.ninebot.device.motor.navi.CruiseModeActivity$Companion")
                        && (name.equals("open") || name.equals("openWithPermission"))));
            if (!scoped && !trace) continue;
            if (described++ < 8) frames.report("SIGNATURE " + method.toGenericString());
            installMethod(method, capture, scoped);
        }
        updateSummary();
    }
    private void updateSummary() {
        String compatibility = frames.compatibility();
        frames.summary((versionTested ? "" : "当前九号出行版本未测试\n") + (compatibility.isEmpty() || compatibility.contains("缺失") ? compatibility + (compatibility.isEmpty() ? "" : "\n") : "")
                + "发现 " + seen.size() + " 类 / 安装 " + (hooked.size()+tirePressure.hookCount()+vehicle.hookCount()+feature.hookCount()) + " Hook / 命中 " + (called.size()+tirePressure.hitCount()+vehicle.hitCount()));
    }
    private void installPowerObserver(Method method) {
        Class<?>[] params = method.getParameterTypes();
        int last = params.length - 1;
        boolean suspended = last >= 0 && params[last].isInterface() && params[last].getName().equals("kotlin.coroutines.Continuation")
                && method.getReturnType() == Object.class;
        if (!suspended && method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) return;
        if (!hooked.add(method)) return;
        try {
            hook(method).intercept(chain -> {
                String request = hostAttached ? direct.vehicleCheckRequest() : null;
                if (request == null) return chain.proceed();
                // Kotlin re-enters this method with its own state machine on resume. Wrapping that
                // argument would hide its type/label and restart the suspended function.
                if (suspended && BooleanResultObserver.ownStateMachine(method.getDeclaringClass().getName(),
                        method.getName(), chain.getArg(last))) return chain.proceed();
                if (called.add(method)) { frames.report("HIT POWER " + method.toGenericString()); updateSummary(); }
                BooleanResultObserver observer = new BooleanResultObserver(on -> direct.vehiclePowerObserved(request, on));
                Object[] args = chain.getArgs().toArray();
                if (suspended) {
                    try { args[last] = observer.continuation(params[last], args[last]); }
                    catch (RuntimeException e) { frames.report("POWER continuation unavailable " + e.getClass().getSimpleName()); }
                }
                Object result = chain.proceed(args);
                observer.returned(result);
                return result;
            });
            frames.report("SIGNATURE POWER " + method.toGenericString());
        } catch (Throwable e) { hooked.remove(method); frames.report("POWER hook unavailable " + e.getClass().getSimpleName()); }
    }
    private void installMethod(Method method, boolean capture, boolean scoped) {
        if (!hooked.add(method)) return;
        try {
            hook(method).intercept(chain -> {
                if (!hostAttached) return chain.proceed();
                if (called.add(method)) {
                    frames.report("HIT " + method.toGenericString());
                    updateSummary();
                }
                Object[] args = chain.getArgs().toArray();
                boolean shortcut = HookPolicy.bitmapGetter(method.getDeclaringClass().getName(), method.getName(),
                        method.getParameterCount(), method.getReturnType() == Bitmap.class);
                Object templateOwner = chain.getThisObject() == null ? method.getDeclaringClass() : chain.getThisObject();
                Map<Method, int[]> templates = null;
                if (shortcut) synchronized (bitmapTemplates) { templates = bitmapTemplates.computeIfAbsent(templateOwner, key -> new ConcurrentHashMap<>()); }
                int[] template = templates == null ? null : templates.get(method);
                if (template != null) {
                    Bitmap output = frames.replacement(template[0], template[1], template[2], true);
                    if (output != null) return output;
                }
                boolean replaced = false;
                if (capture) {
                    for (int i = 0; i < args.length; i++) if (args[i] instanceof Bitmap) {
                        try {
                            Bitmap replacement = frames.replace((Bitmap) args[i]);
                            if (replacement != null) { args[i] = replacement; replaced = true; }
                        } catch (RuntimeException e) { frames.report("BITMAP input fallback: " + e.getClass().getSimpleName()); }
                    }
                }
                int depth = captureDepth.get();
                if (scoped) captureDepth.set(depth + 1);
                Object result;
                try { result = chain.proceed(args); }
                finally { if (scoped) captureDepth.set(depth); }
                if (!capture && chain.getThisObject() != null) {
                    String type = method.getDeclaringClass().getName(), name = method.getName();
                    if (type.equals("cn.ninebot.mapcapture.DeviceScreenCastManager") && name.startsWith("start")
                            && !(result instanceof Boolean && !((Boolean)result))) startedManagers.put(chain.getThisObject(), true);
                    if (HookPolicy.terminalCastMethod(type, name) && startedManagers.remove(chain.getThisObject()) != null)
                        direct.transportEnded(name);
                }
                if (shortcut && result instanceof Bitmap) {
                    Bitmap bitmap = (Bitmap)result;
                    if (!bitmap.isRecycled()) templates.put(method, new int[]{bitmap.getWidth(), bitmap.getHeight(), bitmap.getDensity()});
                }
                if (capture && !replaced && result instanceof Bitmap) {
                    try {
                        Bitmap replacement = frames.replace((Bitmap) result);
                        if (replacement != null) return replacement;
                    } catch (RuntimeException e) { frames.report("BITMAP output fallback: " + e.getClass().getSimpleName()); }
                }
                return result;
            });
        } catch (Throwable e) { hooked.remove(method); frames.report("HOOK rejected " + method.getName() + ": " + e.getClass().getSimpleName()); }
    }
}
