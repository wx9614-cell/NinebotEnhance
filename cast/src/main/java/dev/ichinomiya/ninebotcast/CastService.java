package dev.ichinomiya.ninebotcast;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.view.*;
import java.nio.*;
import dev.ichinomiya.ninebotcast.core.DisplaySettings;

public final class CastService extends Service {
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaCodec codec;
    private RtpH264 rtp;
    private Thread worker;
    private volatile boolean running;

    @Override public int onStartCommand(Intent in, int flags, int id) {
        if (running) { LogStore.add("CAST 已在运行，忽略重复启动"); return START_NOT_STICKY; }
        try {
            int[] size=fitSize(num(in,"width",1280), num(in,"height",720));
            int w=size[0], h=size[1], fps=num(in,"fps",20), rate=num(in,"bitrate",2500000);
            boolean virtualOverride=in.getBooleanExtra("virtual_override", false);
            boolean keepPhoneDpi=in.getBooleanExtra("keep_phone_dpi", false);
            int virtualWidth=num(in,"virtual_width",DisplaySettings.DEFAULT_VIRTUAL_WIDTH), virtualHeight=num(in,"virtual_height",DisplaySettings.DEFAULT_VIRTUAL_HEIGHT);
            int dpi=num(in,"dpi",DisplaySettings.DEFAULT_DPI), bottomInset=num(in,"bottom_inset",0);
            DisplaySettings settings;
            try { settings = new DisplaySettings(w,h,virtualWidth,virtualHeight,dpi,DisplaySettings.DEFAULT_BACKGROUND_COLOR,keepPhoneDpi,DisplaySettings.DEFAULT_LIGHT_BACKGROUND_COLOR,virtualOverride,bottomInset); }
            catch (IllegalArgumentException e) { settings = DisplaySettings.defaults().withFrame(w,h); LogStore.add("CAST 虚拟屏参数无效，回退默认布局：" + e.getMessage()); }
            boolean drawOverlay=in.getBooleanExtra("draw_overlay", true), touchInput=in.getBooleanExtra("touch_input", true);
            String drawName=in.getStringExtra("draw_name");
            String host=in.getStringExtra("host"); int port=num(in,"port",5004);
            if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("未填写 UDP 目标地址");
            LogStore.add("CAST 初始化 frame="+w+"x"+h+" virtual="+settings.virtualWidth+"x"+settings.virtualHeight+" dpi="+settings.dpi+" profile="+settings.label()+" fps="+fps+" bitrate="+rate+" target="+host+":"+port);
            LogStore.add("CAST options draw="+drawOverlay+" touch="+touchInput+" name="+drawName);
            createNotification();
            MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
            projection=manager.getMediaProjection(in.getIntExtra("result",0),(Intent)in.getParcelableExtra("data"));
            if (projection == null) throw new IllegalStateException("录屏授权无效");
            rtp=new RtpH264(host,port,1200);
            codec=MediaCodec.createEncoderByType("video/avc");
            MediaFormat f=MediaFormat.createVideoFormat("video/avc",w,h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE,rate); f.setInteger(MediaFormat.KEY_FRAME_RATE,fps); f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            LogStore.add("CODEC configure video/avc color=surface");
            Surface surface=codec.createInputSurface(); codec.start();
            display=projection.createVirtualDisplay("Ninebot Independent Cast",w,h,settings.dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null);
            running=true; LogStore.add("CAST running：等待编码输出");
            worker=new Thread(this::drain,"cast-encoder"); worker.start();
        } catch(Exception e) { LogStore.add("CAST 启动失败："+e); stopSelf(); }
        return START_NOT_STICKY;
    }

    private void drain() {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        try {
            while(running) {
                int ix=codec.dequeueOutputBuffer(info,1000);
                if(ix>=0) {
                    ByteBuffer b=codec.getOutputBuffer(ix);
                    if(b != null && info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                        byte[] x=new byte[info.size]; b.position(info.offset); b.limit(info.offset+info.size); b.get(x); rtp.sendAccessUnit(x,info.presentationTimeUs);
                    }
                    codec.releaseOutputBuffer(ix,false);
                }
            }
        } catch(Exception e) { if(running) LogStore.add("CAST 编码/发送异常："+e); }
        finally { if(running){running=false;LogStore.add("CAST 工作线程结束");} stopSelf(); }
    }

    private void createNotification() {
        String ch="cast"; getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(ch,"九号独立投屏",NotificationManager.IMPORTANCE_LOW));
        startForeground(7,new Notification.Builder(this,ch).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("九号独立投屏").setContentText("正在采集并发送 H.264/RTP UDP").setOngoing(true).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }
    private static int num(Intent i,String k,int d){try{return Integer.parseInt(i.getStringExtra(k));}catch(Exception e){return d;}}
    private static int even(int value){return Math.max(2,value & ~1);}
    private static int[] fitSize(int requestedWidth,int requestedHeight){
        int w=Math.max(160,requestedWidth), h=Math.max(160,requestedHeight);
        float scale=Math.min(1280f/w,720f/h);
        if(scale<1f){w=Math.round(w*scale);h=Math.round(h*scale);}
        return new int[]{even(w),even(h)};
    }
    @Override public void onDestroy(){running=false;if(display != null)display.release();if(codec != null){try{codec.stop();}catch(Exception ignored){}codec.release();}if(projection != null)projection.stop();if(rtp != null)rtp.close();LogStore.add("CAST 已停止");super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
