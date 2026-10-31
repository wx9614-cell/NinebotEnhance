package dev.ichinomiya.ninebotenhance.hook;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.BatteryTelemetry;
import dev.ichinomiya.ninebotenhance.core.RegisterProbe;
import dev.ichinomiya.ninebotenhance.core.RideState;
import dev.ichinomiya.ninebotenhance.core.TireTelemetry;
import dev.ichinomiya.ninebotenhance.core.WidgetSettings;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Observe the verified 6.10.10 / 6.10.11 DynamicDevice response path for the selected vehicle and decode the pack voltage
 * and the tyre real-time frame. The optional refresh sends the same read-only commands through the Ninebot
 * sendCommand API (queued first, no retries) on the intervals chosen in the vehicle card settings, because the
 * Ninebot detail page stops its own polling while casting. A bounded bus census in the module log shows what
 * Ninebot itself dispatched, what replied and what its interceptor refused, so a cast can be diagnosed offline.
 */
public final class VehicleHooks {
    public static final String DEVICE=HookCatalog.DEVICE;
    private static final String CLIENT=HookCatalog.CLIENT;
    private static final String FUNCTION1=HookCatalog.FUNCTION1;
    /** The display register is the measured pack voltage; bay registers are cross-checked once a minute and used only when the display register is silent. */
    public static final long ATTEMPT_INTERVAL_MS=250,REPLY_WATCHDOG_MS=6000,CENSUS_INTERVAL_MS=60000,FRAME_LOG_INTERVAL_MS=30000,CROSS_CHECK_INTERVAL_MS=60000,DISPLAY_PRIORITY_MS=90000;
    /** A command that stays silent twice in a row is not sent again in this session; log volume per session is bounded. */
    public static final int SILENT_LIMIT=2,LOG_SENDS_PER_COMMAND=2,LOG_REPLIES_PER_COMMAND=3,LOG_SILENCE_PER_SESSION=20;
    private final XposedModule module;private final FrameClient frames;private final BatteryTelemetry battery;private final TireTelemetry tires;private final BooleanSupplier compatible;private final Runnable changed;
    private final Set<Method> installed=ConcurrentHashMap.newKeySet(),hits=ConcurrentHashMap.newKeySet();
    private final Set<String> failures=ConcurrentHashMap.newKeySet(),logged=ConcurrentHashMap.newKeySet();
    private final Map<String,Method> methods=new ConcurrentHashMap<>();
    private final Map<String,Long> frameLogged=new ConcurrentHashMap<>();
    private final Set<String> awaiting=ConcurrentHashMap.newKeySet(),muted=ConcurrentHashMap.newKeySet();
    private final Map<String,AtomicInteger> silentStreak=new ConcurrentHashMap<>(),sendLogged=new ConcurrentHashMap<>(),replyLogged=new ConcurrentHashMap<>();
    private final Map<String,Boolean> supportCache=new ConcurrentHashMap<>();
    private volatile Map<String,AtomicInteger> dispatched=new ConcurrentHashMap<>(),refused=new ConcurrentHashMap<>(),replies=new ConcurrentHashMap<>();
    private final Handler reader;
    private volatile Class<?> deviceClass;
    private final AtomicInteger sent=new AtomicInteger(),replied=new AtomicInteger(),silent=new AtomicInteger(),pending=new AtomicInteger(),silenceLogged=new AtomicInteger();
    private volatile long nextAttempt,lastReply,lastCensus,lastDisplayVoltage,lastTyreRead,lastVoltageRead,lastCrossCheck;
    private volatile boolean sessionLogged,probed;
    private volatile WidgetSettings settings=WidgetSettings.DEFAULT;
    /** Lithium bay mask observed passively from the rBool register Ninebot polls itself; -1 until seen. */
    private volatile int bays=-1;
    /**
     * Diagnostic probe: two-byte status registers the app itself never displays, read round-robin and logged whenever their
     * value changes, so a register that follows the gear, parking or reverse state can be identified on the real vehicle.
     */
    public static final String[] PROBE_REGISTERS=RegisterProbe.CANDIDATES;
    private static final Set<String> PROBE_SET=new HashSet<>(Arrays.asList(PROBE_REGISTERS));
    private static final long PROBE_INTERVAL_MS=250;
    /** Speed and power each follow their own read interval whenever a card or the hill-hold dodge needs them. */
    private volatile long lastSpeedRead,lastPowerRead;
    private int probeCursor;private volatile long lastProbe;private final Map<String,String> probeValues=new ConcurrentHashMap<>();
    /** Probe reads go one at a time so unanswered raw indexes cannot pile up in the Ninebot dispatcher. */
    private final AtomicInteger probePending=new AtomicInteger();private volatile List<String> probeList;private volatile int probeListKey;
    private java.lang.ref.WeakReference<Object> injectedDevice=new java.lang.ref.WeakReference<>(null);private Set<String> injectedModules=new HashSet<>();
    private static boolean isProbe(String name){return PROBE_SET.contains(name)||RegisterProbe.raw(name);}
    /** Named candidates the user selected plus, per requested board, every raw index; rebuilt only when the selection changes. */
    private List<String> probeNames(Object device){
        Set<String> selection=frames.probeSelection(),modules=frames.probeRawModules();
        if(!modules.isEmpty())injectRaw(device,modules);
        int key=selection.hashCode()*31+modules.hashCode();List<String> cached=probeList;
        if(cached!=null&&key==probeListKey)return cached;
        List<String> names=new ArrayList<>();
        for(String name:PROBE_REGISTERS)if(selection.contains(name))names.add(name);
        for(String module:RegisterProbe.RAW_MODULES)if(modules.contains(module))for(int i=0;i<RegisterProbe.RAW_INDEXES;i++)names.add(RegisterProbe.rawName(module,i));
        probeList=names;probeListKey=key;return names;
    }
    /**
     * Ninebot builds every command from its per-model configuration table by name. Adding read entries for each index of a
     * board lets the ordinary sendCommand path read registers the app never names; entries are read-only two-byte reads and
     * only exist in this process's copy of the table.
     */
    private void injectRaw(Object device,Set<String> modules){
        if(injectedDevice.get()==device&&injectedModules.containsAll(modules))return;
        try{
            Field factoryField=device.getClass().getDeclaredField("commandFactory");factoryField.setAccessible(true);Object factory=factoryField.get(device);
            if(factory==null){if(logged.add("raw factory"))frames.report("PROBE raw scan: command factory not ready");return;}
            Field mapField=factory.getClass().getDeclaredField("commandConfigMap");mapField.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,Object> map=(Map<String,Object>)mapField.get(factory);
            Class<?> configClass=Class.forName("cn.ninebot.library.bluetooth.dynamic.config.CommandConfig",false,device.getClass().getClassLoader());
            Constructor<?> ctor=configClass.getConstructor(String.class,String.class,String.class,int.class,String.class,Integer.class,int.class,int.class,String.class,Integer.class,String.class,String.class);
            int added=0;
            for(String module:modules)for(int i=0;i<RegisterProbe.RAW_INDEXES;i++){String name=RegisterProbe.rawName(module,i);if(map.containsKey(name))continue;
                map.put(name,ctor.newInstance(name,module,"read",i,"2",null,0,0,null,null,null,null));added++;}
            injectedDevice=new java.lang.ref.WeakReference<>(device);injectedModules=new HashSet<>(modules);
            frames.report("PROBE raw scan: injected "+added+" read commands for "+modules);
        }catch(Throwable e){if(logged.add("raw inject"))frames.report("PROBE raw scan injection failed "+e.getClass().getSimpleName()+": "+e.getMessage());}
    }
    /** Ignition state from bit 0 of the same register (the detail page gates its power-only features on it); null until seen. */
    private volatile Boolean powerOn;
    public Boolean powerOn(){return powerOn;}
    public VehicleHooks(XposedModule module,FrameClient frames,BooleanSupplier compatible,Runnable changed){
        this.module=module;this.frames=frames;battery=frames.batteryData();tires=frames.tireData();this.compatible=compatible;this.changed=changed;
        HandlerThread thread=new HandlerThread("Ninebot-VehicleRead");thread.start();reader=new Handler(thread.getLooper());
    }
    public int hookCount(){return installed.size();}
    /** The DynamicDevice class once seen; shared with the navigation test sender. */
    public Class<?> deviceClass(){return deviceClass;}
    public int hitCount(){return hits.size();}
    public static boolean interesting(String name){return DEVICE.equals(name);}
    public void inspect(Class<?> type){
        if(!DEVICE.equals(type.getName()))return;
        deviceClass=type;
        for(Method method:type.getDeclaredMethods()){
            int count=method.getParameterCount();String name=method.getName();
            boolean response=name.equals("onResponse")&&count==1&&method.getParameterTypes()[0].getName().endsWith(".NbFrame");
            boolean intercept=name.equals("intercept")&&count==1&&method.getParameterTypes()[0].getName().endsWith(".Command");
            if(!response&&!intercept||!installed.add(method))continue;
            try{module.hook(method).intercept(chain->{
                Object result=chain.proceed();
                if(compatible.getAsBoolean()){
                    try{if(response)observe(method,chain.getThisObject(),chain.getArg(0));else census(chain.getArg(0),Boolean.TRUE.equals(result));}
                    catch(Throwable e){failed(response?"BLE frame":"BLE census",e);}
                }
                return result;
            });frames.report("VEHICLE observer DynamicDevice."+name);}
            catch(Throwable e){installed.remove(method);failed("hook "+name,e);}
        }
    }
    /** Every command Ninebot hands to its dispatcher, and whether its own interceptor refused it. */
    private void census(Object command,boolean refusedNow)throws ReflectiveOperationException{
        String tag=string(call(command,"getTag"));if(tag.isEmpty())tag="?";
        dispatched.computeIfAbsent(tag,k->new AtomicInteger()).incrementAndGet();
        if(refusedNow){refused.computeIfAbsent(tag,k->new AtomicInteger()).incrementAndGet();
            if(awaiting.contains(tag)&&logged.add("refused "+tag))frames.report("VEHICLE "+tag+" refused by DynamicDevice.intercept (communicateEnable=false); the vehicle never saw it");}
    }
    private void observe(Method method,Object device,Object frame)throws ReflectiveOperationException{
        if(device==null||frame==null)return;
        String tag=string(call(call(frame,"getCommand"),"getTag"));
        replies.computeIfAbsent(tag.isEmpty()?"?":tag,k->new AtomicInteger()).incrementAndGet();
        boolean voltage=BatteryTelemetry.recognized(tag),tyres=TireTelemetry.REALTIME_COMMAND.equals(tag),flags=BatteryTelemetry.BAY_FLAGS_COMMAND.equals(tag);
        boolean probe=Arrays.asList(BatteryTelemetry.SOC_COMMANDS).contains(tag),level=BatteryTelemetry.DASH_LEVEL_COMMAND.equals(tag),register=isProbe(tag),ride=RideState.SPEED_COMMAND.equals(tag)||RideState.POWER_COMMAND.equals(tag);
        if(!voltage&&!tyres&&!flags&&!probe&&!level&&!register&&!ride)return;
        String vehicle=string(call(device,"getSn")),selected=battery.selectedKey();
        if(vehicle.isEmpty()||!vehicle.equals(selected)){if((voltage||tyres)&&logged.add("other "+tag))frames.report("VEHICLE frame "+tag+" from sn="+vehicle+" ignored; selected="+selected);return;}
        Object payload=call(frame,"getData");byte[] data=payload instanceof byte[]?(byte[])payload:null;long now=SystemClock.elapsedRealtime();
        if(ride&&data!=null&&data.length>=2){int value=BatteryTelemetry.u16(data,0);if(RideState.SPEED_COMMAND.equals(tag))frames.rideData().speed(value,now);else frames.rideData().power(RideState.signedPower(value),now);if(!register)return;}
        if(register){
            String value=hex(data);String previous=probeValues.put(tag,value);
            frames.registerProbe().reply(tag,value,data!=null&&data.length>=2?BatteryTelemetry.u16(data,0):-1,now);
            if(previous==null||!previous.equals(value))frames.report("PROBE "+tag+" bytes="+value+" "+BatteryTelemetry.describe(data)+(previous==null?" (first)":" (was "+previous+")"));
            return;
        }
        if(probe||level){
            Long last=frameLogged.get(tag);
            if(probe||last==null||now-last>=FRAME_LOG_INTERVAL_MS){frameLogged.put(tag,now);frames.report("VEHICLE "+(probe?"probe ":"dash ")+tag+" bytes="+hex(data)+" "+BatteryTelemetry.describe(data));}
            return;
        }
        if(flags){
            int seen=BatteryTelemetry.lithiumBays(data);
            if(seen>=0&&seen!=bays){bays=seen;frames.report("VEHICLE rBool bytes="+hex(data)+" lithium bays="+seen+" -> voltage candidates "+BatteryTelemetry.voltageCandidates(seen));}
            if(data!=null&&data.length>=2){boolean on=(BatteryTelemetry.u16(data,0)&1)!=0;Boolean previous=powerOn;
                if(previous==null||previous!=on){powerOn=on;frames.report("VEHICLE power "+(on?"on":"off")+" (rBool bit 0)");}}
            return;
        }
        long wall=System.currentTimeMillis();boolean updated;String decoded;
        if(voltage){
            Float volts=BatteryTelemetry.decode(tag,data);
            if(volts==null){if(failures.add("decode "+tag))frames.report("VEHICLE "+tag+" ignored: implausible payload "+hex(data));return;}
            boolean display=BatteryTelemetry.displayRegister(tag);
            if(display)lastDisplayVoltage=now;
            boolean shadowed=!display&&now-lastDisplayVoltage<DISPLAY_PRIORITY_MS;
            updated=!shadowed&&battery.update(vehicle,volts,BatteryTelemetry.Source.BLUETOOTH,wall,now);
            decoded=String.format(Locale.ROOT,"%.2fV (%s)%s",volts,BatteryTelemetry.describe(data),shadowed?" cross-check only; display register is the source":"");
        }else{
            TireTelemetry.RealTime values=TireTelemetry.decodeRealTime(data);
            if(values==null){if(failures.add("decode "+tag))frames.report("VEHICLE "+tag+" ignored: short payload "+hex(data));return;}
            updated=tires.update(vehicle,true,values.frontPressure(),values.frontTemperature(),TireTelemetry.Source.BLUETOOTH,wall,now);
            updated|=tires.update(vehicle,false,values.rearPressure(),values.rearTemperature(),TireTelemetry.Source.BLUETOOTH,wall,now);
            decoded="front="+values.frontPressure()+"bar/"+values.frontTemperature()+"C rear="+values.rearPressure()+"bar/"+values.rearTemperature()+"C";
        }
        // Frames are logged at most every 30 s per tag, so a long ride cannot evict the rest of the log.
        Long previous=frameLogged.get(tag);
        if(previous==null||now-previous>=FRAME_LOG_INTERVAL_MS){frameLogged.put(tag,now);frames.report("VEHICLE frame "+tag+" length="+(data==null?-1:data.length)+" bytes="+hex(data)+" -> "+decoded+(updated?"":" (unchanged)"));}
        if(updated){
            if(hits.add(method))frames.report("VEHICLE received DynamicDevice.onResponse "+tag);
            changed.run();
        }
    }
    /** Called from the frame worker while a session is active; the intervals come from the vehicle card settings. */
    public void pulse(String vehicle,WidgetSettings current){
        long now=SystemClock.elapsedRealtime();
        if(vehicle==null||vehicle.isEmpty()||deviceClass==null)return;
        if(current!=null)settings=current;
        if(now-lastCensus>=CENSUS_INTERVAL_MS){lastCensus=now;reader.post(this::reportCensus);}
        if(now<nextAttempt)return;
        nextAttempt=now+ATTEMPT_INTERVAL_MS;
        reader.post(()->refresh(vehicle));
    }
    /** Session end or disabled switch: flush the census, forget per-session state and let the next session read immediately. */
    public void stop(){probePending.set(0);frames.rideData().clear();
        if(sessionLogged){sessionLogged=false;reader.post(()->{reportCensus();frames.report("VEHICLE session summary "+summary());});}
        lastTyreRead=lastVoltageRead=lastCrossCheck=lastCensus=0;muted.clear();silentStreak.clear();sendLogged.clear();replyLogged.clear();supportCache.clear();silenceLogged.set(0);probed=false;
    }
    public String summary(){
        long now=SystemClock.elapsedRealtime();
        return "sent="+sent.get()+" replied="+replied.get()+" noReply="+silent.get()+" pending="+pending.get()+" lastReplyAgo="+(lastReply==0?-1:now-lastReply)+"ms bays="+bays+" muted="+muted;
    }
    private void reportCensus(){
        Map<String,AtomicInteger> sentNow=dispatched,refusedNow=refused,repliesNow=replies;
        dispatched=new ConcurrentHashMap<>();refused=new ConcurrentHashMap<>();replies=new ConcurrentHashMap<>();
        if(sentNow.isEmpty()&&repliesNow.isEmpty())return;
        frames.report("VEHICLE bus "+CENSUS_INTERVAL_MS/1000+"s dispatched="+counts(sentNow)+" replies="+counts(repliesNow)+(refusedNow.isEmpty()?"":" refused="+counts(refusedNow)));
    }
    private static String counts(Map<String,AtomicInteger> map){
        List<String> keys=new ArrayList<>(map.keySet());Collections.sort(keys);StringBuilder b=new StringBuilder("{");
        int total=0;for(String key:keys){int n=map.get(key).get();total+=n;if(b.length()>1)b.append(',');b.append(key).append('=').append(n);}
        return b.append("} total=").append(total).toString();
    }
    private void refresh(String vehicle){
        Class<?> type=deviceClass;if(type==null||!compatible.getAsBoolean())return;
        try{
            Object device=connectedDevice(type);
            if(device==null){if(logged.add("disconnected "+vehicle))frames.report("VEHICLE read skipped: no connected DynamicDevice for "+vehicle);return;}
            String sn=string(call(device,"getSn"));
            if(!vehicle.equals(sn)){if(logged.add("mismatch "+sn))frames.report("VEHICLE read skipped: connected sn="+sn+" selected="+vehicle);return;}
            WidgetSettings s=settings;long now=SystemClock.elapsedRealtime();
            if(!sessionLogged){sessionLogged=true;frames.report("VEHICLE session reads for sn="+sn+" via sendCommand(addToFirst, no retry): tyres every "+s.tyreIntervalSeconds()+"s, voltage every "+s.voltageIntervalMs()+" ms");}
            List<String> due=new ArrayList<>();
            if(s.readsTyres()&&now-lastTyreRead>=s.tyreIntervalSeconds()*1000L&&ready(TireTelemetry.REALTIME_COMMAND)){lastTyreRead=now;due.add(TireTelemetry.REALTIME_COMMAND);}
            if(s.readsVoltage()&&now-lastVoltageRead>=s.voltageIntervalMs()){String command=voltageCommand();if(command!=null&&ready(command)){lastVoltageRead=now;due.add(command);}}
            if(s.readsVoltage()&&now-lastCrossCheck>=CROSS_CHECK_INTERVAL_MS){lastCrossCheck=now;for(String bay:BatteryTelemetry.bayCommands(bays))if(!due.contains(bay)&&ready(bay))due.add(bay);}
            if(s.readsSpeed()&&now-lastSpeedRead>=s.speedIntervalMs()&&ready(RideState.SPEED_COMMAND)){lastSpeedRead=now;if(!due.contains(RideState.SPEED_COMMAND))due.add(RideState.SPEED_COMMAND);}
            if(s.readsPower()&&now-lastPowerRead>=s.powerIntervalMs()&&ready(RideState.POWER_COMMAND)){lastPowerRead=now;if(!due.contains(RideState.POWER_COMMAND))due.add(RideState.POWER_COMMAND);}
            // One diagnostic round per session: the bay SOC registers settle the BMS byte order against the dashboard level.
            if(!probed){probed=true;for(String name:BatteryTelemetry.probeCandidates(bays))if(!due.contains(name))due.add(name);}
            // Register probe: one candidate per tick in rotation; muted and still-pending ones are skipped.
            if(s.enabled(WidgetSettings.REGISTER_PROBE)&&now-lastProbe>=PROBE_INTERVAL_MS&&probePending.get()==0){lastProbe=now;List<String> names=probeNames(device);
                for(int i=0;i<names.size();i++){String name=names.get((probeCursor+i)%names.size());
                    if(!ready(name))continue;probeCursor=(probeCursor+i+1)%names.size();if(!due.contains(name))due.add(name);break;}}
            for(String name:due){
                if(!supports(device,name)){if(logged.add("unsupported "+name))frames.report("VEHICLE "+name+" absent from this vehicle configuration");continue;}
                send(type,device,name);
            }
        }catch(Throwable e){failed("read",e);}
    }
    private boolean ready(String command){return !awaiting.contains(command)&&!muted.contains(command);}
    private String voltageCommand(){
        if(!muted.contains(BatteryTelemetry.VRLA_VOLTAGE_COMMAND))return BatteryTelemetry.VRLA_VOLTAGE_COMMAND;
        for(String bay:BatteryTelemetry.bayCommands(bays))if(!muted.contains(bay))return bay;
        return null;
    }
    private void send(Class<?> type,Object device,String name)throws ReflectiveOperationException{
        ClassLoader loader=type.getClassLoader();
        Class<?> function=Class.forName(FUNCTION1,false,loader);
        long started=SystemClock.elapsedRealtime();
        pending.incrementAndGet();awaiting.add(name);
        if(isProbe(name)){probePending.incrementAndGet();frames.registerProbe().sent(name,started);}
        Object[] done={Boolean.FALSE};
        Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,method,args)->{
            switch(method.getName()){
                case "invoke":
                    synchronized(done){if(Boolean.TRUE.equals(done[0]))return null;done[0]=Boolean.TRUE;}
                    pending.decrementAndGet();awaiting.remove(name);
                    long took=SystemClock.elapsedRealtime()-started;
                    if(args!=null&&args.length==1&&args[0]!=null){
                        replied.incrementAndGet();lastReply=SystemClock.elapsedRealtime();silentStreak.remove(name);
                        if(isProbe(name)){probePending.decrementAndGet();frames.registerProbe().settled(name);}
                        if(replyLogged.computeIfAbsent(name,k->new AtomicInteger()).incrementAndGet()<=LOG_REPLIES_PER_COMMAND)frames.report("VEHICLE reply "+name+" after "+took+"ms");
                    }else{
                        silent.incrementAndGet();int streak=silentStreak.computeIfAbsent(name,k->new AtomicInteger()).incrementAndGet();
                        if(isProbe(name)){probePending.decrementAndGet();frames.registerProbe().silent(name,SystemClock.elapsedRealtime());}
                        if(silenceLogged.incrementAndGet()<=LOG_SILENCE_PER_SESSION)frames.report("VEHICLE no reply "+name+" after "+took+"ms"+(took<50?" (rejected before sending: command not built or intercepted)":" (Ninebot command timeout)"));
                        if(streak>=SILENT_LIMIT&&muted.add(name))frames.report("VEHICLE "+name+" muted for this session after "+streak+" silent reads");
                    }
                    return null;
                case "toString":return "NinebotEnhance vehicle reader";
                case "hashCode":return System.identityHashCode(proxy);
                case "equals":return args!=null&&args.length==1&&proxy==args[0];
                default:return null;
            }
        });
        type.getMethod("sendCommand",String.class,byte[].class,boolean.class,Integer.class,function).invoke(device,name,null,true,0,callback);
        sent.incrementAndGet();
        if(sendLogged.computeIfAbsent(name,k->new AtomicInteger()).incrementAndGet()<=LOG_SENDS_PER_COMMAND)frames.report("VEHICLE sent "+name+(bays<0?" (bays unknown)":" (bays="+bays+")"));
        reader.postDelayed(()->{synchronized(done){if(Boolean.TRUE.equals(done[0]))return;done[0]=Boolean.TRUE;}pending.decrementAndGet();awaiting.remove(name);silent.incrementAndGet();if(isProbe(name)){probePending.decrementAndGet();frames.registerProbe().silent(name,SystemClock.elapsedRealtime());}if(logged.add("watchdog "+name))frames.report("VEHICLE "+name+" callback never fired within "+REPLY_WATCHDOG_MS+"ms; released by watchdog");},REPLY_WATCHDOG_MS);
    }
    private boolean supports(Object device,String name)throws ReflectiveOperationException{
        Boolean known=supportCache.get(name);if(known!=null)return known;
        boolean value=Boolean.TRUE.equals(deviceClass.getMethod("hasCommand",String.class).invoke(device,name));
        supportCache.put(name,value);return value;
    }
    private Object connectedDevice(Class<?> type)throws ReflectiveOperationException{
        Class<?> client=Class.forName(CLIENT,false,type.getClassLoader());
        Object instance=client.getField("INSTANCE").get(null);
        Object device=client.getMethod("getConnectedDevice").invoke(instance);
        return type.isInstance(device)?device:null;
    }
    private Object call(Object object,String name)throws ReflectiveOperationException{
        if(object==null)return null;
        String key=object.getClass().getName()+"#"+name;
        Method method=methods.get(key);
        if(method==null){method=object.getClass().getMethod(name);methods.put(key,method);}
        return method.invoke(object);
    }
    private static String string(Object value){return value instanceof String?(String)value:"";}
    private static String hex(byte[] data){if(data==null)return "null";StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(data.length,16);i++)b.append(String.format(Locale.ROOT,"%02x",data[i]));return b.toString();}
    private void failed(String where,Throwable e){if(failures.add(where))frames.report("VEHICLE "+where+" unavailable "+e.getClass().getSimpleName());}
}
