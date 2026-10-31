package dev.ichinomiya.ninebotenhance.hook;

import android.os.SystemClock;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.TireTelemetry;
import dev.ichinomiya.ninebotenhance.diagnostics.WeakIdentityMap;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Observe only the verified 6.10.10 / 6.10.11 tyre parser and selected vehicle; never issue BLE/network requests. */
public final class TirePressureHooks {
    public static final String PARSER=HookCatalog.TYRE_PARSER;
    public static final String MANAGER=HookCatalog.DEVICE_MANAGER;
    private final XposedModule module;private final FrameClient frames;private final TireTelemetry tires;private final BooleanSupplier compatible;private final Runnable changed;
    private final Set<Method> installed=ConcurrentHashMap.newKeySet(),hits=ConcurrentHashMap.newKeySet();
    private final Set<String> failures=ConcurrentHashMap.newKeySet();
    private final WeakIdentityMap<String> owners=new WeakIdentityMap<>();
    private final WeakIdentityMap<Boolean> responses=new WeakIdentityMap<>();
    private final ThreadLocal<Packet> packet=new ThreadLocal<>();
    private static final class Packet {
        final Object parser;final String vehicle;final Float[] fields=new Float[4];
        Packet(Object parser,String vehicle){this.parser=parser;this.vehicle=vehicle;}
    }
    public TirePressureHooks(XposedModule module,FrameClient frames,BooleanSupplier compatible,Runnable changed){this.module=module;this.frames=frames;tires=frames.tireData();this.compatible=compatible;this.changed=changed;}
    public int hookCount(){return installed.size();}
    public int hitCount(){return hits.size();}
    public static boolean interesting(String name){return PARSER.equals(name)||MANAGER.equals(name);}
    public void inspect(Class<?> type){
        for(Method method:type.getDeclaredMethods()){
            String name=method.getName();int count=method.getParameterCount();
            boolean manager=type.getName().equals(MANAGER);
            boolean wanted=manager?(name.equals("getCurrentDeviceBean")&&count==0||name.equals("setCurrentDeviceBean")&&count==1)
                    :(name.equals("init")&&count==3||name.equals("reset")&&count==0||name.equals("onResponse")&&count==2
                    ||name.equals("parseExtraFloat")&&count==2||name.equals("onRequestFinish")&&count==4);
            if(!wanted||!installed.add(method))continue;
            try{module.hook(method).intercept(chain->{
                if(!compatible.getAsBoolean())return chain.proceed();
                Object self=chain.getThisObject();
                if(!manager&&name.equals("onResponse")){
                    Packet previous=packet.get(),current=null;
                    try{String owner=owners.get(self),sn=string(invoke(chain.getArg(0),"getSn"));if(owner!=null&&owner.equals(sn))current=new Packet(self,owner);}
                    catch(Throwable e){failed("BLE identity",e);}
                    packet.set(current);
                    try{Object result=chain.proceed();if(current!=null){try{publish(current);if(hasValues(current))hit(method);}catch(Throwable e){failed("BLE values",e);}}return result;}
                    finally{if(previous==null)packet.remove();else packet.set(previous);}
                }
                Object result=chain.proceed();
                try{
                    if(manager){frames.selectVehicle(deviceKey(name.equals("getCurrentDeviceBean")?result:chain.getArg(0)));hit(method);}
                    else switch(name){
                        case "init":owners.put(self,deviceKey(chain.getArg(0)));hit(method);break;
                        case "reset":owners.put(self,"");break;
                        case "parseExtraFloat":{
                            Packet current=packet.get();if(current!=null&&current.parser==self&&result instanceof Number){
                                int index=switch(String.valueOf(chain.getArg(0))){case "frontPressure"->0;case "frontTemperature"->1;case "rearPressure"->2;case "rearTemperature"->3;default->-1;};
                                if(index>=0)current.fields[index]=index%2==0?TireTelemetry.bluetoothPressure((Number)result):TireTelemetry.bluetoothTemperature((Number)result);
                            }break;
                        }
                        case "onRequestFinish":if(server(self,string(chain.getArg(0)),chain.getArg(2)))hit(method);break;
                    }
                }catch(Throwable e){failed(name,e);}
                return result;
            });frames.report("TIRE observer "+type.getSimpleName()+"."+name);}
            catch(Throwable e){installed.remove(method);failed("hook "+name,e);}
        }
    }
    private void publish(Packet p){long now=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
        boolean updated=tires.update(p.vehicle,true,p.fields[0],p.fields[1],TireTelemetry.Source.BLUETOOTH,wall,now);
        updated|=tires.update(p.vehicle,false,p.fields[2],p.fields[3],TireTelemetry.Source.BLUETOOTH,wall,now);
        if(updated)changed.run();
    }
    private static boolean hasValues(Packet p){for(Float value:p.fields)if(value!=null)return true;return false;}
    private boolean server(Object parser,String vehicle,Object config)throws ReflectiveOperationException{
        if(vehicle.isEmpty()||!vehicle.equals(owners.get(parser))||!Boolean.TRUE.equals(invoke(config,"getRequestSuccess")))return false;
        // The original parser skips cloud readings while the selected vehicle has a BLE connection.
        Class<?> manager=Class.forName(MANAGER,false,parser.getClass().getClassLoader());
        Object singleton=manager.getField("INSTANCE").get(null);
        if(vehicle.equals(deviceKey(invoke(singleton,"getCurrentDeviceBean")))&&Boolean.TRUE.equals(invoke(singleton,"isCurrentDeviceConnect")))return false;
        Object response=invoke(config,"getResponse");if(response==null||!Boolean.TRUE.equals(invoke(response,"getSuccess")))return false;
        synchronized(responses){if(responses.get(response)!=null)return false;responses.put(response,true);}
        // Response.value() in this APK is a synchronous lookup of its flattened cacheMap.
        // Read only the exact fields consumed by the original parser, without touching coroutine continuations.
        Method value=null;for(Method m:response.getClass().getDeclaredMethods())if(m.getName().equals("value")&&m.getParameterCount()==2&&m.getParameterTypes()[0]==String.class){value=m;break;}
        if(value==null)return false;value.setAccessible(true);
        Method lookup=value;
        return tires.readServer(vehicle,key->lookup.invoke(response,key,null),System.currentTimeMillis(),SystemClock.elapsedRealtime());
    }
    private static Object invoke(Object object,String name)throws ReflectiveOperationException{return object==null?null:object.getClass().getMethod(name).invoke(object);}
    private static String deviceKey(Object bean)throws ReflectiveOperationException{return string(invoke(bean,"getWnumber"));}
    private static String string(Object value){return value instanceof String?(String)value:"";}
    private void hit(Method method){if(hits.add(method)){frames.report("TIRE received "+method.getDeclaringClass().getSimpleName()+"."+method.getName());changed.run();}}
    private void failed(String where,Throwable e){if(failures.add(where))frames.report("TIRE "+where+" unavailable "+e.getClass().getSimpleName());}
}
