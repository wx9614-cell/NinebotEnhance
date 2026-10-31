package dev.ichinomiya.ninebotenhance.hook;

import android.media.MediaCodec;
import android.os.SystemClock;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.diagnostics.*;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only observation. Original arguments, result, continuation and send behavior are untouched.
 * Frame senders receive whole encoded frames; RTP packets are only visible at the last hop (UDP datagram or BLE write).
 */
public final class StatisticsHooks {
    public static final String BLE_SENDER="cn.ninebot.library.screencast.BluetoothRtpSender",NB_BLE_SENDER="cn.ninebot.mapcapture.NBBluetoothRtpSender";
    public static final String WIFI_SENDER="cn.ninebot.library.screencast.RtpSender",FRAME_SENDER="cn.ninebot.library.screencast.FrameSender";
    public static final String SEND_QUEUE="cn.ninebot.library.screencast.NormalSendQueue";
    public static final String UDP_SESSION="jlibrtp.RTPSession",BLE_WRITER="cn.ninebot.mapcapture.NBBleSender";
    /** The EncodeListener objects every Ninebot encoder reports into; they live outside the capture package and are only reachable by name. */
    public static final String[] ENCODE_SINKS={"cn.ninebot.screencast.ScreenCastRequest$setListener$2","cn.ninebot.screencast.ScreenCastRequest$createNavigationScreenCast$7",
            "cn.ninebot.mapcapture.DeviceScreenCastRequest$createNavigationScreenCast$2"};
    private final XposedModule module;
    private final FrameClient frames;
    private final Set<Method> hooked=ConcurrentHashMap.newKeySet();
    private final Set<Method> receiverReportSeen=ConcurrentHashMap.newKeySet();
    public StatisticsHooks(XposedModule module,FrameClient frames){this.module=module;this.frames=frames;}
    public static boolean interesting(String name){
        if(name.equals(BLE_SENDER)||name.equals(NB_BLE_SENDER)||name.equals(WIFI_SENDER)||name.equals(FRAME_SENDER)||name.equals(SEND_QUEUE)||name.equals(UDP_SESSION)||name.equals(BLE_WRITER))return true;
        for(String sink:ENCODE_SINKS)if(name.equals(sink))return true;
        return false;
    }
    public void inspect(Class<?> type) {
        String name=type.getName();
        boolean frameSender=name.equals(BLE_SENDER) || name.equals(NB_BLE_SENDER) || name.equals(WIFI_SENDER);
        boolean packetWriter=name.equals(UDP_SESSION) || name.equals(BLE_WRITER);
        boolean encoder=implementsEncoder(type,0);
        if(name.equals(FRAME_SENDER)||frameSender)installControl(type);
        if(name.equals(SEND_QUEUE))installQueue(type);
        if(!frameSender&&!packetWriter&&!encoder)return;
        for(Method method:type.getDeclaredMethods()) {
            if(Modifier.isAbstract(method.getModifiers())||method.isSynthetic()||method.isBridge())continue;
            String methodName=method.getName();
            if(frameSender&&!methodName.equals("send") || packetWriter&&!(methodName.equals("sendTo")||methodName.equals("sendRtp")) || encoder&&!methodName.startsWith("on"))continue;
            int payload=-1,info=-1;
            Class<?>[] types=method.getParameterTypes();
            for(int i=0;i<types.length;i++) {if(types[i]==byte[].class||types[i]==ByteBuffer.class){if(payload!=-1){payload=-2;break;}payload=i;} if(types[i]==MediaCodec.BufferInfo.class)info=i;}
            if(payload<0||!hooked.add(method))continue;
            final int index=payload,infoIndex=info; final int kind=packetWriter?2:frameSender?1:0;
            String source=method.toGenericString();
            try { module.hook(method).intercept(chain->{
                StreamStats stats=frames.transportStats(); ByteBuffer data=null; int size=0; RtpPacket packet=null;
                if(stats!=null) try {
                    Object value=chain.getArg(index);
                    data=value instanceof byte[]?ByteBuffer.wrap((byte[])value):value instanceof ByteBuffer?((ByteBuffer)value).duplicate():null;
                    if(data!=null&&infoIndex>=0) {
                        MediaCodec.BufferInfo details=(MediaCodec.BufferInfo)chain.getArg(infoIndex);
                        if(details==null || details.size<=0 || (details.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0 || details.offset<0 || details.offset>data.capacity()-details.size) data=null;
                        else {data.clear();data.position(details.offset);data.limit(details.offset+details.size);}
                    }
                    if(data!=null) {size=data.remaining();if(kind==2)packet=RtpPacket.parse(data);}
                } catch(RuntimeException ignored) {}
                Object result=chain.proceed();
                if(stats!=null && !(result instanceof Boolean && !((Boolean)result))) {
                    long now=SystemClock.elapsedRealtime();
                    if(kind==2 && packet != null) {
                        stats.packet(now,source,packet.bytes,packet.frameKey,packet.marker);
                        frames.packetSubmissionObserved(packet.bytes);
                    } else if(kind==1&&size>0) {
                        stats.frameSubmitted(now,source,size);
                        frames.frameSubmissionObserved(size);
                    } else if(kind==0&&size>0) {
                        stats.encoded(now,source,size);
                        frames.encodedFrameObserved(size);
                    }
                }
                return result;
            }); frames.report("STATS observer "+source); }
            catch(Throwable e){hooked.remove(method);frames.report("STATS observer unavailable "+method.getName());}
        }
    }
    /** Dashboard feedback: RTCP APP subtype 1 loss reports, 2/3 rate requests, and Wi-Fi receiver reports with loss fraction and cumulative loss. */
    private void installControl(Class<?> type) {
        for(Method method:type.getDeclaredMethods()) {
            if(Modifier.isAbstract(method.getModifiers())||method.isSynthetic()||method.isBridge())continue;
            Class<?>[] types=method.getParameterTypes();
            boolean app=method.getName().equals("onAPPPktReceived")&&types.length==2&&types[0]==int.class&&types[1]==byte[].class;
            boolean receiver=method.getName().equals("RRPktReceived")&&types.length>=4&&types[2]==int[].class&&types[3]==int[].class;
            if(!app&&!receiver||!hooked.add(method))continue;
            try { module.hook(method).intercept(chain->{
                try {
                    StreamStats stats=frames.transportStats();
                    if(stats!=null&&app) {
                        int subtype=(Integer)chain.getArg(0);byte[] data=(byte[])chain.getArg(1);
                        if(subtype==1)stats.lossReport();
                        else if((subtype==2||subtype==3)&&data!=null&&data.length>=4)stats.dashboardRequest(subtype,ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()&0xffffffffL);
                        if(subtype!=5)frames.report("DASH rtcp app subtype="+subtype+" bytes="+hex(data));
                    }
                    if(stats!=null&&receiver) {
                        int[] fraction=(int[])chain.getArg(2),lost=(int[])chain.getArg(3);
                        if (fraction != null && fraction.length > 0) {
                            long cumulative = lost != null && lost.length > 0 ? lost[0] : -1;
                            stats.receiverReport(fraction[0],cumulative);
                            if(receiverReportSeen.add(method)) frames.report("DASH receiver-report observed lossFraction="+fraction[0]+" cumulativeLost="+cumulative);
                        }
                    }
                } catch(RuntimeException ignored) {}
                return chain.proceed();
            }); frames.report("STATS observer "+method.toGenericString()); }
            catch(Throwable e){hooked.remove(method);frames.report("STATS observer unavailable "+method.getName());}
        }
    }
    /** Frames the original send queue gave up on; read from its own counter after each enqueue. */
    private void installQueue(Class<?> type) {
        Field counter=null;
        try { counter=type.getDeclaredField("mGiveUpFrameCount");counter.setAccessible(true); } catch(Throwable ignored) {}
        if(counter==null){frames.report("STATS queue counter unavailable");return;}
        final Field field=counter;
        for(Method method:type.getDeclaredMethods()) {
            if(!method.getName().equals("putFrame")||method.getParameterCount()!=1||!hooked.add(method))continue;
            try { module.hook(method).intercept(chain->{
                Object result=chain.proceed();
                try {
                    StreamStats stats=frames.transportStats();
                    if(stats!=null) {
                        Object value=field.get(chain.getThisObject());
                        if(value instanceof java.util.concurrent.atomic.AtomicInteger)stats.queueDrops(((java.util.concurrent.atomic.AtomicInteger)value).get());
                        else if(value instanceof Number)stats.queueDrops(((Number)value).longValue());
                    }
                } catch(RuntimeException|IllegalAccessException ignored) {}
                return result;
            }); frames.report("STATS observer "+method.toGenericString()); }
            catch(Throwable e){hooked.remove(method);frames.report("STATS observer unavailable "+method.getName());}
        }
    }
    private static String hex(byte[] data) {
        if(data==null)return "null";StringBuilder text=new StringBuilder();
        for(int i=0;i<Math.min(data.length,16);i++)text.append(String.format(Locale.ROOT,"%02x",data[i]));
        return text.toString();
    }
    private static boolean implementsEncoder(Class<?> type,int depth) {
        if(type==null||depth>8)return false;
        if(type.getName().equals("cn.ninebot.capture.encoder.EncodeListener"))return true;
        for(Class<?> face:type.getInterfaces())if(implementsEncoder(face,depth+1))return true;
        return implementsEncoder(type.getSuperclass(),depth+1);
    }
}
