import dev.ichinomiya.ninebotenhance.core.*;
import dev.ichinomiya.ninebotenhance.ipc.*;
import dev.ichinomiya.ninebotenhance.hook.*;
import dev.ichinomiya.ninebotenhance.diagnostics.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class CoreTests {
    static int assertions;
    static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    static void close(float a, float b, String message) { check(Math.abs(a - b) < .02, message); }
    static void rejects(Runnable action, String message) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, message);
    }
    static void mapped(float[] m, float x, float y, float expectedX, float expectedY) {
        close(m[0] * x + m[1] * y + m[2], expectedX, "rotated touch x");
        close(m[3] * x + m[4] * y + m[5], expectedY, "rotated touch y");
    }
    static void componentContractTests() throws Exception {
        // Class literals are also used by the client binding: moving the service must update the manifest.
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new java.io.File("app/src/main/AndroidManifest.xml"));
        String[] components = {dev.ichinomiya.ninebotenhance.client.ServiceBridge.SERVICE_CLASS,
                dev.ichinomiya.ninebotenhance.service.RootBridgeProvider.class.getName()};
        for (int i = 0; i < components.length; i++) {
            var nodes = document.getElementsByTagName(i == 0 ? "service" : "provider");
            org.w3c.dom.Element found = null;
            for (int j = 0; j < nodes.getLength(); j++) {
                var element = (org.w3c.dom.Element) nodes.item(j);
                String name = element.getAttribute("android:name");
                if (name.startsWith(".")) name = Protocol.MODULE + name;
                else if (!name.contains(".")) name = Protocol.MODULE + "." + name;
                if (components[i].equals(name)) found = element;
            }
            check(found != null && "true".equals(found.getAttribute("android:exported")),
                    "compiled broker component is declared and exported for authenticated cross-process IPC");
            check(found != null && found.getAttribute("android:process").isEmpty(),
                    "broker components share the module process with ShizukuProvider initialization");
        }
        var providers = document.getElementsByTagName("provider");
        org.w3c.dom.Element shizuku = null;
        for (int i = 0; i < providers.getLength(); i++) {
            var element = (org.w3c.dom.Element) providers.item(i);
            if ("rikka.shizuku.ShizukuProvider".equals(element.getAttribute("android:name"))) shizuku = element;
        }
        check(shizuku != null && (Protocol.MODULE + ".shizuku").equals(shizuku.getAttribute("android:authorities"))
                        && "true".equals(shizuku.getAttribute("android:exported")) && "false".equals(shizuku.getAttribute("android:multiprocess"))
                        && shizuku.getAttribute("android:process").isEmpty()
                        && "android.permission.INTERACT_ACROSS_USERS_FULL".equals(shizuku.getAttribute("android:permission")),
                "official provider initializes Sui in the broker process with its protected authority");
    }
    static double luminance(int color){double r=((color>>>16)&255)/255.,g=((color>>>8)&255)/255.,b=(color&255)/255.;return .2126*r+.7152*g+.0722*b;}
    static void canvasLayoutTests() {
        DisplaySettings d=DisplaySettings.defaults();
        check(d.width==848&&d.height==480&&d.virtualWidth==640&&d.virtualHeight==440&&d.contentTop()==40,"calibrated layout reserves the 40px status bar and the right column");
        check(d.virtualWidth<=644-4,"virtual display leaves four pixels before the overlay at x644");
        DisplaySettings banded=new DisplaySettings(1024,608,760,496,160,0xff242424,true,0xffe6eaee,false,64);
        check(banded.contentTop()==48&&banded.contentBottom()==544&&banded.bottomInset==64&&banded.label().contains("底部预留 64")&&!d.label().contains("底部预留")&&d.bottomInset==0,"a bottom strip lifts the app off the frame's bottom edge");
        rejects(()->new DisplaySettings(1024,608,760,560,160,0xff242424,true,0xffe6eaee,false,64),"app plus bottom strip cannot exceed the frame");
        rejects(()->new DisplaySettings(1024,608,760,496,160,0xff242424,true,0xffe6eaee,false,-1),"a negative bottom strip is rejected");
        check(DisplaySettings.read((k,f)->k.equals("bottom_inset")?64:k.equals("width")?1024:k.equals("height")?608:k.equals("virtual_width")?760:k.equals("virtual_height")?496:k.equals("layout_version")?2:f).contentTop()==48,"the bottom strip round-trips through the settings keys");
        PreviewTransform lifted=new PreviewTransform(banded,1024,608,false);
        check(lifted.contains(100,100)&&!lifted.contains(100,560)&&!lifted.contains(100,20)&&!lifted.contains(800,300),"gestures start only inside the lifted app rectangle");
        float[] lm=lifted.input;check(Math.abs(lm[0]*100+lm[1]*100+lm[2]-100)<1e-3&&Math.abs(lm[3]*100+lm[4]*100+lm[5]-52)<1e-3,"app coordinates subtract the app's top row");
        ByteBuffer liftedOut=ByteBuffer.allocate(4*4*4),oneRow=ByteBuffer.wrap(new byte[]{1,2,3,4,5,6,7,8});
        PixelPacking.compose(oneRow,8,4,2,1,liftedOut,4,4,1,0xff102030,false);
        check(liftedOut.getInt(0)==0x102030ff&&liftedOut.getInt(16)==0x01020304&&liftedOut.getInt(20)==0x05060708&&liftedOut.getInt(24)==0x102030ff&&liftedOut.getInt(32)==0x102030ff&&liftedOut.getInt(60)==0x102030ff,"a lifted app row leaves background rows above and below it");
        rejects(()->PixelPacking.compose(oneRow,8,4,2,1,ByteBuffer.allocate(64),4,4,4,0xff102030,false),"an app row below the frame is rejected");
        rejects(()->new DisplaySettings(848,480,850,440,160,0xff242424),"oversize app width cannot be cropped");
        rejects(()->new DisplaySettings(848,480,640,482,160,0xff242424),"oversize app height cannot be cropped");
        rejects(()->new DisplaySettings(848,480,641,440,160,0xff242424),"odd virtual width rejected");
        rejects(()->new DisplaySettings(848,480,640,150,160,0xff242424),"tiny virtual height rejected");
        check(new DisplaySettings(240,320,240,320,160,0xff242424).virtualHeight==320,"a 240 x 320 half-screen frame is accepted");
        DisplaySettings keep=new DisplaySettings(848,480,640,440,160,0xff242424,true),off=new DisplaySettings(848,480,640,440,160,0xff242424,false);
        check(!d.keepPhoneDpi&&!DisplaySettings.DEFAULT_KEEP_PHONE_DPI,"keep-phone-DPI is off by default");
        DisplaySettings.RenderPlan plan=keep.renderPlan(520);
        check(plan!=null&&plan.width()==2080&&plan.height()==1430&&plan.dpi()==520,"keep-phone-DPI renders 640x440 dp at 2080x1430@520 for a 520 dpi phone");
        DisplaySettings.RenderPlan odd=keep.renderPlan(420);
        check(odd.width()==1680&&odd.height()==1156&&odd.dpi()==420,"render sizes round to even");
        DisplaySettings.RenderPlan capped=new DisplaySettings(848,480,640,440,100,0xff242424,true).renderPlan(700);
        check(capped.width()==4096&&capped.height()==2816&&capped.dpi()==700,"oversize plans shrink to the 4096 side limit keeping the phone density");
        check(keep.renderPlan(160)==null&&keep.renderPlan(0)==null&&off.renderPlan(520)==null,"no override when the density already matches, is unknown or the option is off");
        check(keep.withFrame(636,360).keepPhoneDpi&&!off.withFrame(636,360).keepPhoneDpi&&keep.label().contains("保持手机 DPI")&&!off.label().contains("保持"),"the option survives reframing and shows in the label");
        check(DisplaySettings.read((k,f)->k.equals("layout_version")?DisplaySettings.LAYOUT_VERSION:k.equals("keep_phone_dpi")?1:f).keepPhoneDpi&&!DisplaySettings.read((k,f)->k.equals("layout_version")?DisplaySettings.LAYOUT_VERSION:f).keepPhoneDpi,"keep-phone-DPI round-trips through the settings store and is off when nothing is stored");
        check(d.lightBackgroundColor==DisplaySettings.DEFAULT_LIGHT_BACKGROUND_COLOR&&d.background(true)==d.backgroundColor&&d.background(false)==d.lightBackgroundColor&&keep.withFrame(636,360).lightBackgroundColor==d.lightBackgroundColor,"each dashboard theme has its own frame background");
        rejects(()->new DisplaySettings(848,480,640,440,160,0xff242424,true,0x80ffffff),"transparent light background rejected");
        dev.ichinomiya.ninebotenhance.core.HudPalette dark=dev.ichinomiya.ninebotenhance.core.HudPalette.DARK,light=dev.ichinomiya.ninebotenhance.core.HudPalette.LIGHT;
        check(dark.dark()&&!light.dark()&&dev.ichinomiya.ninebotenhance.core.HudPalette.of(true)==dark&&dev.ichinomiya.ninebotenhance.core.HudPalette.of(false)==light,"palettes resolve by theme");
        for(dev.ichinomiya.ninebotenhance.core.HudPalette pal:new dev.ichinomiya.ninebotenhance.core.HudPalette[]{dark,light}){
            check((pal.surface()>>>24)>=0xf0&&(pal.text()>>>24)==255&&(pal.label()>>>24)==255&&(pal.icon()>>>24)==255&&(pal.percent()>>>24)==255&&(pal.body()>>>24)==255,"card surface and text colours are opaque");
            double surface=luminance(pal.surface()),text=luminance(pal.text()),label=luminance(pal.label());
            check(pal.dark()?text>surface+.5&&label>surface+.2:text<surface-.5&&label<surface-.2,"text stands out from the card surface in "+(pal.dark()?"dark":"light"));
        }
        check(Arrays.equals(dev.ichinomiya.ninebotenhance.core.DashboardTheme.payload(true),new byte[]{1,0,1,0})&&Arrays.equals(dev.ichinomiya.ninebotenhance.core.DashboardTheme.payload(false),new byte[]{1,0,0,0}),"day/night flag is mask 1 plus the value, little endian");
        for(boolean rotate:new boolean[]{false,true})for(int[] view:new int[][]{{360,740},{360,320},{1200,540}}){
            PreviewTransform m=new PreviewTransform(d,view[0],view[1],rotate);
            for(float[] point:new float[][]{{.5f,.5f},{639.5f,.5f},{.5f,439.5f},{639.5f,439.5f},{320,220}}){
                float frameY=point[1]+40;
                float x=m.fit.left+(rotate?480-frameY:point[0])/m.fit.scale;
                float y=m.fit.top+(rotate?point[0]:frameY)/m.fit.scale;
                check(m.contains(x,y),"every virtual-display corner stays touchable after phone rotation and fit");
                mapped(m.input,x,y,point[0],point[1]);mapped(m.frameInput,x,y,point[0],frameY);
            }
            for(float[] point:new float[][]{{320,20},{640.5f,200},{800,470}}){
                float x=m.fit.left+(rotate?480-point[1]:point[0])/m.fit.scale;
                float y=m.fit.top+(rotate?point[0]:point[1])/m.fit.scale;
                check(!m.contains(x,y),"top and right background never inject touches into the app");
            }
            check(!m.contains(m.fit.left-1,m.fit.top),"phone fit margins remain noninteractive");
        }
        DisplaySettings full=new DisplaySettings(860,480,160);PreviewTransform fit=new PreviewTransform(full,860,480,false);
        check(fit.contains(.5f,.5f)&&fit.contains(859.5f,479.5f),"full-canvas virtual display has no reserved touch margin");
        byte[] image={88,88,88,88,1,2,3,4,99,99,99,99,5,6,7,8,99,99,99,99,9,10,11,12,99,99,99,99,13,14,15,16};
        ByteBuffer source=ByteBuffer.wrap(image).asReadOnlyBuffer();source.position(4);
        for(java.nio.ByteOrder order:new java.nio.ByteOrder[]{java.nio.ByteOrder.BIG_ENDIAN,java.nio.ByteOrder.LITTLE_ENDIAN}){
            ByteBuffer out=ByteBuffer.allocateDirect(4*3*4).order(order);
            PixelPacking.compose(source,16,8,2,2,out,4,3,0xff12a0e3);
            for(int y=0;y<3;y++)for(int x=0;x<4;x++){
                int i=(y*4+x)*4;
                byte[] expected=y==0||x>=2?new byte[]{0x12,(byte)0xa0,(byte)0xe3,(byte)255}:Arrays.copyOfRange(image,4+(y-1)*16+x*8,8+(y-1)*16+x*8);
                for(int c=0;c<4;c++)check(out.get(i+c)==expected[c],"strided app is copied below top rows with full right background");
            }
            check(source.position()==4&&out.position()==0&&out.limit()==48&&out.order()==order,"composing preserves buffer state and both byte orders");
            ByteBuffer truncated=source.duplicate();truncated.limit(source.limit()-1);
            rejects(()->PixelPacking.compose(truncated,16,8,2,2,out,4,3,0xff242424),"missing bottom pixel fails before partial output");
            rejects(()->PixelPacking.compose(source,16,8,2,2,out,1,3,0xff242424),"app wider than canvas rejected");
            rejects(()->PixelPacking.compose(source,16,8,2,2,out,4,1,0xff242424),"app taller than canvas rejected");
            rejects(()->PixelPacking.compose(source,16,8,2,2,ByteBuffer.allocate(47),4,3,0xff242424),"short canvas allocation rejected");
        }
    }
    public static void main(String[] args) throws Exception {
        AuthorizationTests.run();
        VehicleStartTests.run();
        UpdateTests.run();
        BandColorTests.run();
        EncodingTests.run();
        OverlayTests.run();
        DashboardLayoutTests.run();
        HiddenFeatureTests.run();
        NaviTestTests.run();
        EventCensusTests.run();
        NaviLiveTests.run();
        NaviResumeTests.run();
        LampTests.run();
        BmsTests.run();
        TouchPanelTests.run();
        FramePacerTests.run();
        themeTests();
        componentContractTests();
        canvasLayoutTests();
        check(!PrivilegeMode.ROOT.useShizuku(true), "explicit Root keeps its chosen backend");
        check(PrivilegeMode.SHIZUKU.useShizuku(true), "explicit authorized Shizuku selected");
        boolean unavailable=false;try{PrivilegeMode.SHIZUKU.useShizuku(false);}catch(IllegalStateException e){unavailable=true;}
        check(unavailable,"explicit Shizuku never silently falls back to su");
        rejects(()->PrivilegeMode.parse("COMMAND"),"unknown privilege mode rejected");
        rejects(()->PrivilegeMode.parse("AUTO"),"the automatic backend is gone");
        drawTests();
        byte[] rtp={(byte)0x80,(byte)0xe0,0,1,0,0,0,100,0,0,0,1,42};
        ByteBuffer packet=ByteBuffer.wrap(rtp);RtpPacket parsed=RtpPacket.parse(packet);
        check(parsed!=null&&parsed.marker&&parsed.bytes==13&&parsed.frameKey.equals("1:100"),"RTP marker, length and frame timestamp parsed");
        check(packet.position()==0&&packet.limit()==13,"RTP observation leaves caller buffer unchanged");
        ByteBuffer offset=ByteBuffer.allocateDirect(32);offset.position(4);offset.put(rtp);offset.limit(17);offset.position(4);
        check(RtpPacket.parse(offset.asReadOnlyBuffer()).bytes==13&&offset.position()==4,"RTP supports direct/read-only sliced payload");
        check(RtpPacket.parse(ByteBuffer.wrap(new byte[11]))==null,"truncated RTP rejected");
        byte[] control=rtp.clone();control[1]=(byte)200;check(RtpPacket.parse(ByteBuffer.wrap(control))==null,"RTCP is not counted as video");
        byte[] version=rtp.clone();version[0]=0x40;check(RtpPacket.parse(ByteBuffer.wrap(version))==null,"wrong RTP version rejected");
        byte[] csrc=rtp.clone();csrc[0]=(byte)0x83;check(RtpPacket.parse(ByteBuffer.wrap(csrc))==null,"truncated CSRC header rejected");
        byte[] extension=rtp.clone();extension[0]=(byte)0x90;check(RtpPacket.parse(ByteBuffer.wrap(extension))==null,"truncated RTP extension rejected");
        byte[] padding=rtp.clone();padding[0]=(byte)0xa0;padding[12]=0;check(RtpPacket.parse(ByteBuffer.wrap(padding))==null,"invalid RTP padding rejected");
        StreamStats stats=new StreamStats(1000);stats.captured(1100);stats.captured(1500);stats.replaced();stats.encoded(1500,"encoder",500);
        stats.encoded(1500,"nestedEncoder",900);stats.packet(1500,"sender",13,"1:100",true);stats.packet(1500,"sender",13,"1:100",true);
        stats.packet(1500,"nestedSender",99,"1:101",true);
        StreamStats.Snapshot snapshot=stats.snapshot(2000);
        check(snapshot.captured()==2&&snapshot.replaced()==1,"capture and encoder supply count independently");
        check(snapshot.encoded()==1&&snapshot.encodedBytes()==500,"single encoder callback source prevents nested double count");
        check(snapshot.packets()==2&&snapshot.sentBytes()==26&&snapshot.sentFrames()==1,"repeated RTP submissions count bytes but deduplicate same frame end");
        close((float)snapshot.captureFps(),2,"capture rate uses elapsed session time");close((float)snapshot.encodeBps(),4000,"bitrate uses bits per second");
        close((float)stats.snapshot(4000).sendBps(),0,"stalled sender ages out of rolling rate");
        stats.stop(4500);stats.captured(4600);stats.packet(4600,"sender",100,"1:102",true);
        check(stats.snapshot(9000).durationMs()==3500&&stats.snapshot(9000).captured()==2&&stats.snapshot(9000).sentBytes()==26,"closed session rejects late samples and freezes duration");
        check(new StreamStats(5000).snapshot(5000).sentBytes()==0,"new session resets totals");
        // Phone-only rotation: center and all four sides must map back to the original RGBA
        // buffer, including resized preview space above the phone's keyboard.
        PreviewTransform turned = new PreviewTransform(860, 480, 480, 1000, true);
        close(turned.fit.top, 70, "turned preview vertical FIT margin");
        check(!turned.fit.contains(240, 69) && !turned.fit.contains(240, 930), "turned preview bars reject new touches");
        mapped(turned.input, 240, 500, 430, 240);
        mapped(turned.input, 0, 70, 0, 480);
        mapped(turned.input, 480, 70, 0, 0);
        mapped(turned.input, 0, 930, 860, 480);
        mapped(turned.input, 480, 930, 860, 0);
        for (boolean rotate : new boolean[]{false, true}) for (int[] view : new int[][]{{360,740}, {360,320}, {1200,540}}) {
            PreviewTransform mapping = new PreviewTransform(860, 480, view[0], view[1], rotate);
            for (float[] point : new float[][]{{0,0}, {860,480}, {127,371}, {430,240}}) {
                float screenX = mapping.fit.left + (rotate ? 480 - point[1] : point[0]) / mapping.fit.scale;
                float screenY = mapping.fit.top + (rotate ? point[0] : point[1]) / mapping.fit.scale;
                mapped(mapping.input, screenX, screenY, point[0], point[1]);
            }
        }
        KeyboardPolicy.text("高德地图😀 A\n"); KeyboardPolicy.text("a".repeat(2048));
        rejects(() -> KeyboardPolicy.text("a".repeat(2049)), "input Binder text bound");
        rejects(() -> KeyboardPolicy.text(null), "missing input text rejected");
        rejects(() -> KeyboardPolicy.text("\ud83d"), "unpaired high surrogate rejected");
        rejects(() -> KeyboardPolicy.text("\ude00"), "unpaired low surrogate rejected");
        rejects(() -> KeyboardPolicy.text("\ud83dA"), "broken emoji rejected");
        KeyboardPolicy.deletion(128, 0); KeyboardPolicy.deletion(64, 64);
        rejects(() -> KeyboardPolicy.deletion(-1, 0), "negative deletion rejected");
        rejects(() -> KeyboardPolicy.deletion(128, 1), "combined deletion bound");
        rejects(() -> KeyboardPolicy.deletion(Integer.MAX_VALUE, Integer.MAX_VALUE), "deletion overflow rejected");
        for (int key : new int[]{7,16,19,22,29,54,59,61,62,66,67,76,112,113,122,123}) check(KeyboardPolicy.key(key), "editing key allowed");
        for (int key : new int[]{0,3,4,5,6,24,25,26,64,65,84,117,118,187,219,279,284}) check(!KeyboardPolicy.key(key), "system/clipboard key denied on public typing endpoint");
        // Complete portrait phone content must fit within a landscape dash without cropping.
        float[] portrait = Geometry.fit(1080, 2400, 800, 480);
        close(portrait[0], 292, "portrait left pillarbox"); close(portrait[2], 508, "portrait right pillarbox");
        close(portrait[1], 0, "portrait not vertically cropped"); close(portrait[3], 480, "portrait full height");
        // Folded/unfolded and rotated source geometries stay within capture limits and remain even.
        for (int[] input : new int[][] {{1080,2400}, {2480,2200}, {2200,2480}, {2400,1080}, {1,1}}) {
            int[] size = Geometry.captureSize(input[0], input[1], 720);
            check(size[0] % 2 == 0 && size[1] % 2 == 0, "even capture dimensions");
            check(Math.max(size[0], size[1]) <= 720 && Math.min(size[0], size[1]) >= 2, "bounded capture dimensions");
        }
        rejects(() -> Geometry.captureSize(0, 20, 720), "invalid physical display rejected");
        rejects(() -> Geometry.fit(1, 1, 0, 1), "zero target rejected");
        // Two rows, each padded to 12 bytes, but the final row has no accessible trailing padding.
        byte[] padded = {1,2,3,4,5,6,7,8,99,99,99,99,9,10,11,12,13,14,15,16};
        ByteBuffer source = ByteBuffer.wrap(padded), output = ByteBuffer.allocate(16);
        PixelPacking.rgba(source, 12, 4, 2, 2, output);
        byte[] expected = new byte[16]; for (int i = 0; i < 16; i++) expected[i] = (byte)(i + 1);
        check(Arrays.equals(expected, output.array()), "row padding is excluded without reading beyond limit");
        check(source.position() == 0, "input position is not consumed");
        rejects(() -> PixelPacking.rgba(ByteBuffer.wrap(new byte[19]),12,4,2,2,output), "short last row rejected");
        rejects(() -> PixelPacking.rgba(source, 12, 4, 2, 2, ByteBuffer.allocate(15)), "undersized destination rejected");
        byte[] spaced = {1,2,3,4,99,99,99,99,5,6,7,8};
        ByteBuffer twoPixels = ByteBuffer.allocate(8);
        PixelPacking.rgba(ByteBuffer.wrap(spaced),16,8,2,1,twoPixels);
        check(Arrays.equals(twoPixels.array(), new byte[]{1,2,3,4,5,6,7,8}), "pixel stride handled");
        // Exporting a Binder service must not make the user's screen public to unrelated applications.
        check(InputDenial.matches("SecurityException: Injecting input events requires the caller (or the source of the instrumentation, if any) to have the INJECT_EVENTS permission."), "permission text recognized as an injection denial");
        check(InputDenial.matches("RemoteException: Remote stack trace: \tat com.android.server.input.InputManagerService.injectInputEventToTarget(InputManagerService.java:1172)"), "remote injection stack recognized as an injection denial");
        check(!InputDenial.matches("IllegalStateException: 虚拟屏已关闭") && !InputDenial.matches(null), "unrelated errors are not injection denials");
        check(!CallerPolicy.allowed(20001,20002,null), "unknown caller denied");
        check(!CallerPolicy.allowed(20001,20002,new String[]{"evil.cn.ninebot.ninebot"}), "lookalike package denied");
        check(!CallerPolicy.allowed(20001,20002,new String[]{"com.example.other"}), "unrelated app denied");
        check(CallerPolicy.allowed(20001,20002,new String[]{"cn.ninebot.ninebot"}), "intended target allowed");
        check(CallerPolicy.allowed(20002,20002,null), "own application allowed");
        check(!CallerPolicy.allowed(20001,20002,new String[]{"com.autonavi.minimap"})&&CallerPolicy.allowedFor(Protocol.NAVI_UPDATE,20001,20002,new String[]{"com.autonavi.minimap"})&&CallerPolicy.allowedFor(Protocol.REPORT,20001,20002,new String[]{"com.baidu.BaiduMap"})&&!CallerPolicy.allowedFor(Protocol.BEGIN,20001,20002,new String[]{"com.tencent.map"})&&!CallerPolicy.allowedFor(Protocol.NAVI_UPDATE,20001,20002,new String[]{"com.example.other"}), "navigation apps may only report and publish navigation state");
        // Hooks must remain out of authentication, transport and vehicle command implementations.
        check(HookPolicy.captureClass("cn.ninebot.capture.codec.BitmapToH264Encoder"), "image encoder eligible");
        check(!HookPolicy.captureClass("cn.ninebot.nbcrypto.NbEncryption"), "crypto excluded");
        check(!HookPolicy.captureClass("cn.ninebot.library.nbbluetooth.protocol.BleProtocol"), "BLE excluded");
        check(!HookPolicy.captureClass("cn.ninebot.capture.R$drawable"), "resources excluded");
        check(HookPolicy.coroutineDrawingMethod("cn.ninebot.capture.ViewToBitmapConvert$convert$1", "invokeSuspend"), "async drawing eligible");
        // A cancelled/stale Android activity result must never trigger another vehicle session.
        String first = "0123456789abcdef0123456789abcdef", second = "fedcba9876543210fedcba9876543210";
        DirectSession direct = new DirectSession();
        check(!direct.beginCast("invalid") && !direct.beginDisplay("invalid"), "malformed direct request rejected");
        check(!Protocol.validRequest(null) && !Protocol.validRequest(first + "0"), "missing/oversized request rejected");
        check(direct.beginCast(first), "user gesture starts vehicle checks");
        check(!direct.beginCast(second), "double tap cannot replace a pending cast");
        check(!direct.launch(first), "cannot launch before the vehicle is confirmed");
        check(!direct.vehicleConfirmed(second), "foreign check result ignored");
        check(!direct.vehicleConfirmed(first), "vehicle checks precede the binding");
        check(direct.powerChecked(first, true) && direct.cruiseReady(first) && direct.vehicleConfirmed(first), "powered vehicle and original cruise accepted before allocation");
        check(!direct.vehicleConfirmed(first), "duplicate confirmation ignored");
        check(!direct.launch(first), "no display yet, so nothing to bind");
        check(direct.beginDisplay(second) && !direct.launch(first), "a display that is still starting is not bound");
        check(!direct.displayReady(first) && direct.displayReady(second), "display readiness is request-bound");
        check(direct.launch(first), "first launch accepted once the display is ready");
        check(!direct.launch(first), "duplicate launch rejected");
        check(direct.running(first), "observed frames confirm running phase");
        check(!direct.endCast(second), "old/different stop ignored");
        check(direct.endCast(first) && direct.cast() == DirectSession.Cast.IDLE && direct.displayRunning(), "stopping the cast returns it to idle and keeps the display");
        check(direct.beginCast(second) && !direct.vehicleConfirmed(first), "late old result cannot authorize new request");
        check(direct.endCast(second) && !direct.launch(second), "cancel before launch never dispatches cruise");
        check(!direct.beginDisplay(first), "a second display cannot replace the running one");
        check(direct.endDisplay(second) && !direct.displayRunning() && !direct.casting(), "closing the display clears its lease");
        check(direct.beginDisplay(first) && !direct.casting() && direct.vehicleCheckRequest() == null, "the display starts on its own, no vehicle check involved");
        check(!direct.displayReady(second) && direct.displayReady(first) && direct.display() == DirectSession.Display.READY, "display callbacks are request-bound");
        check(direct.beginCast(second) && !direct.launch(second), "a cast on a running display still repeats the native checks");
        check(direct.powerChecked(second, true) && direct.cruiseReady(second) && direct.vehicleConfirmed(second) && direct.launch(second) && direct.running(second), "the running display is bound without being recreated");
        check(direct.endDisplay(first) && !direct.casting() && !direct.endCast(second), "closing the display drops the cast bound to it");
        check(direct.beginDisplay(first) && direct.endDisplay(first) && !direct.displayReady(first), "cancelled display startup rejects late callbacks");
        check(HookPolicy.interestingClass("cn.ninebot.device.motor.navi.CruiseModeActivity"), "known cruise lifecycle is observable");
        check(!HookPolicy.captureClass("cn.ninebot.device.motor.navi.CruiseModeActivity"), "cruise methods are trace-only");
        // Cancellation before/after Root attach and stale process callbacks are the key leak boundaries.
        SessionLease lease = new SessionLease();
        check(lease.begin(first, second), "root lease begins");
        check(!lease.ready(second) && !lease.isReady(), "cannot mark ready without handshake");
        check(!lease.attach(first), "wrong bootstrap nonce rejected");
        check(lease.attach(second) && !lease.attach(second), "nonce can be consumed only once");
        check(lease.ready(second) && lease.isReady(), "attached process may publish display");
        check(!lease.end(second) && lease.owns(first), "foreign stop leaves display alive");
        check(lease.end(first) && !lease.isReady() && !lease.authorize(second), "stop revokes root endpoint and display readiness");
        check(lease.begin(second, first) && !lease.attach(second), "stale process cannot attach to later session");
        check(!lease.end(first) && lease.owns(second), "old death callback cannot stop new display");
        check(lease.end(second) && !lease.attach(first), "cancel before su approval rejects late bootstrap");
        check(!lease.begin(null, first) && !lease.begin(first, "wrong"), "malformed nonce rejected");
        // Resolution allocations and density must be checked before opening a Surface.
        DisplaySettings defaults=DisplaySettings.defaults();check(defaults.width==848&&defaults.height==480&&defaults.virtualWidth==640&&defaults.virtualHeight==440&&defaults.dpi==160,"default frame 848x480 contains virtual display 640x440 at 160 DPI");
        new DisplaySettings(1920, 1080, 320); new DisplaySettings(480, 800, 160);
        rejects(() -> new DisplaySettings(Integer.MAX_VALUE, 480, 160), "overflow-sized width rejected");
        rejects(() -> new DisplaySettings(1920, 1920, 160), "excessive frame memory rejected");
        rejects(() -> new DisplaySettings(801, 480, 160), "odd size rejected");
        rejects(() -> new DisplaySettings(800, 480, 0), "invalid dpi rejected");
        rejects(() -> new DisplaySettings(320, 320, 480), "unusable dp dimensions rejected");
        check(DisplaySettings.shellQuote("a'b$()\n").equals("'a'\\''b$()\n'"), "APK path shell quoting is literal");
        TouchMapping touch = new TouchMapping(800, 480, 1000, 1000);
        check(!touch.contains(500, 100) && touch.contains(500, 500), "preview bars do not inject touches");
        close(touch.x(500), 400, "preview center x"); close(touch.y(500), 240, "preview center y");
        close(touch.x(0), 0, "preview left edge"); close(touch.y(200), 0, "preview top edge");
        TouchMapping portraitPreview = new TouchMapping(800, 480, 480, 800);
        close(portraitPreview.x(240), 400, "rotation keeps target center x"); close(portraitPreview.y(400), 240, "rotation keeps target center y");
        check(!portraitPreview.contains(480, 400), "right boundary is exclusive");
        check(HookPolicy.bitmapGetter("cn.ninebot.capture.encoder.ViewEncoder", "getBitmap", 0, true), "synchronous getter eligible for early frame");
        check(!HookPolicy.bitmapGetter("cn.ninebot.capture.encoder.ViewEncoder$1", "invokeSuspend", 1, false), "never short circuit coroutine state machine");
        check(!HookPolicy.bitmapGetter("cn.ninebot.capture.encoder.ViewEncoder", "getBitmap", 1, true), "parameterized conversion keeps side effects");
        check(!HookPolicy.bitmapGetter("cn.ninebot.capture.encoder.ViewEncoder", "run", 0, true), "timer callback is never skipped");
        check(HookPolicy.terminalCastMethod("cn.ninebot.mapcapture.DeviceScreenCastManager", "stopScreenCast"), "cast termination recognized");
        check(!HookPolicy.terminalCastMethod("cn.ninebot.capture.codec.CodecEncoder", "release"), "encoder recreation is not cruise termination");
        // Inline preview follows the map region, including inset/clipped layouts on a foldable.
        check(Arrays.equals(InlineBounds.clip(0, 24, 1080, 2200, 1080, 2400), new int[]{0,24,1080,2200}), "map region preserves top inset");
        check(Arrays.equals(InlineBounds.clip(-20, -40, 820, 520, 800, 480), new int[]{0,0,800,480}), "partly offscreen map is clipped");
        check(InlineBounds.clip(800, 0, 100, 100, 800, 480) == null, "offscreen map cannot create an input layer");
        check(InlineBounds.clip(0, 0, 0, 480, 800, 480) == null, "unlaid out map is retried");
        check(InlineBounds.clip(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 480, 800, 480) == null, "overflowing map extent cannot wrap onto host");
        TouchMapping embedded = new TouchMapping(800,480,480,752); // 800-high content minus 48-high toolbar.
        close(embedded.x(240), 400, "embedded touch uses picture width");
        close(embedded.y(376), 240, "embedded touch excludes toolbar from picture geometry");
        check(!embedded.contains(240, 10), "embedded letterbox cannot start a gesture");
        check(CallerPolicy.controls(20001, 20001, true), "current Ninebot owner can control ready display");
        check(!CallerPolicy.controls(20002, 20001, true), "another allowed package cannot control owner session");
        check(!CallerPolicy.controls(20001, 20001, false), "cancelled or stale display rejects input even from original owner");
        check(!CallerPolicy.controls(0, 0, true), "uninitialized owner UID cannot grant control");
        // A bind request is not a connection; absent callbacks must eventually retire that binding.
        BindingState binding = new BindingState();
        long initial = binding.begin(1000);
        check(!binding.expired(5999) && binding.expired(6000), "missing callback expires at five seconds");
        long replacement = binding.begin(6000);
        check(!binding.connected(initial) && !binding.current(initial), "late connection from retired binding rejected");
        check(!binding.disconnected(initial, 10000), "late disconnect cannot reset replacement's deadline");
        check(binding.expired(11000), "stale callbacks cannot leave binding stuck indefinitely");
        check(binding.connected(replacement) && !binding.expired(99999), "live binding has no retry deadline");
        check(binding.disconnected(replacement, 100000), "live binding death starts a fresh grace period");
        check(!binding.expired(104999), "short disconnect does not immediately tear down the binding");
        check(binding.disconnected(replacement, 104000) && binding.expired(105000), "duplicate death events cannot postpone retry forever");
        BindingState refused = new BindingState();
        check(refused.begin(0) == 1 && refused.failures() == 0, "the first bind attempt is not a failure");
        refused.begin(5000); refused.begin(10000);
        check(refused.failures() == 2, "binds replaced without ever connecting count as refused attempts");
        check(refused.connected(3) && refused.failures() == 0, "a live connection clears the refused count");
        var catalog = java.util.List.of(HookCatalog.method("java.lang.String", "length", new String[0], "t"), HookCatalog.method("java.lang.String", "charAt", new String[]{"int"}, "t"),
                HookCatalog.method("java.lang.String", "endsWith", new String[]{"*.String"}, "t"), HookCatalog.method("java.lang.String", "getBytes", new String[]{"byte[]"}, "t"),
                HookCatalog.method("java.lang.String", "nope", null, "t"), HookCatalog.type("java.util.Missing", "t"), HookCatalog.resource("id", "ivCruise", "t"), HookCatalog.resource("layout", "present", "t"));
        HookCatalog.Report report = HookCatalog.verify(catalog, name -> { try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; } }, t -> t.member().equals("ivCruise") ? 0 : 1);
        check(report.total() == 8 && report.missing().equals(java.util.List.of("String#getBytes(byte[])", "String#nope(…)", "Missing", "id:ivCruise")), "the catalog reports exactly the missing classes, methods and resources: " + report.missing());
        check(report.text().startsWith("Hook 目标 4/8 可用，缺失：") && HookCatalog.verify(catalog.subList(0, 3), name -> { try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; } }, t -> 1).text().equals("Hook 目标 3/3 可用"), "the report text counts available targets");
        check(HookCatalog.ALL.size() >= 20 && HookCatalog.ALL.stream().anyMatch(t -> t.owner().equals(HookCatalog.DEVICE) && t.member().equals("sendCommand")), "the catalog covers the vehicle read path");
        check(HookCatalog.compatible("6.10.10", 610104038L) && HookCatalog.compatible("6.10.11", 610114116L) && HookCatalog.compatible("6.10.12", 610124146L) && !HookCatalog.compatible("6.10.11", 610104038L)
                && !HookCatalog.compatible("6.10.12", 610114116L) && !HookCatalog.compatible(null, 610114116L) && HookCatalog.versions().equals("6.10.10 / 6.10.11 / 6.10.12"),
                "the version gate admits exactly the verified builds by name and code");
        //check(OpenSourceNotice.matches("我已知本项目免费开源在GitHub。") && OpenSourceNotice.matches("  我已知本项目免费开源在GitHub. ")
                //&& !OpenSourceNotice.matches("我已知本项目免费开源在GitHub") && !OpenSourceNotice.matches("") && !OpenSourceNotice.matches(null)
                //&& OpenSourceNotice.REPOSITORY.startsWith("https://github.com/Margele/NinebotEnhance"),
               // "the open-source sentence must be typed exactly, allowing surrounding spaces and an ASCII full stop");
        RegisterProbe probe = new RegisterProbe();
        java.util.function.Function<String, RegisterProbe.Value> value = name -> probe.snapshot(RegisterProbe.all()).stream().filter(r -> r.name().equals(name)).findFirst().get().value();
        probe.sent("rWarn", 1000); var rows = probe.snapshot(java.util.List.of("rSpeed", "rWarn"));
        check(rows.size() == 2 && rows.get(0).name().equals("rWarn") && rows.get(0).value().pending() && rows.get(1).name().equals("rSpeed") && rows.get(1).value() == null, "rows follow the catalogue order and unread registers have no value");
        check(probe.reply("rWarn", "6400", 100, 1200) && !probe.reply("rWarn", "6400", 100, 2200) && probe.reply("rWarn", "6401", 356, 3200), "a reply reports whether the bytes changed");
        RegisterProbe.Value warn = value.apply("rWarn");
        check(!warn.pending() && warn.repliedAt() == 3200 && warn.changedAt() == 3200 && warn.changedWithin(6200, 3000) && !warn.changedWithin(6201, 3000), "reply and change times feed the highlight window");
        probe.silent("rSpeed", 4000); probe.sent("rWarn", 5000); probe.silent("rWarn", 6000);
        check(value.apply("rSpeed").silent() && value.apply("rSpeed").hex().isEmpty() && value.apply("rWarn").silent() && !value.apply("rWarn").pending() && value.apply("rWarn").hex().equals("6401"), "unanswered reads are marked silent while keeping any earlier value");
        probe.sent("rWarn", 7000); probe.settled("rWarn");
        check(!value.apply("rWarn").pending() && value.apply("rWarn").silent(), "settling only clears the in-flight mark");
        probe.clear(); check(probe.snapshot(RegisterProbe.all()).stream().allMatch(r -> r.value() == null) && probe.snapshot(RegisterProbe.all()).size() == RegisterProbe.CANDIDATES.length, "clearing forgets every value and the catalogue stays complete");
        check(RegisterProbe.rawName("dis", 26).equals("xdis_1a") && RegisterProbe.raw("xdis_1a") && !RegisterProbe.raw("rBool") && !RegisterProbe.raw("x_1a"), "raw register names carry the board and hex index");
        RegisterProbe raw = new RegisterProbe(); raw.reply("xdis_1a", "0100", 1, 1000); raw.reply("rWarn", "6400", 100, 1500); raw.sent("xdis_1b", 1600);
        var rawRows = raw.snapshot(java.util.List.of("rWarn"), java.util.List.of("dis"));
        check(rawRows.size() == 3 && rawRows.get(0).name().equals("rWarn") && rawRows.get(1).name().equals("xdis_1a") && rawRows.get(2).name().equals("xdis_1b") && raw.snapshot(java.util.List.of("rWarn")).size() == 1, "raw rows follow the named ones in index order and only when their board is requested");
        raw.reply("xdis_1a", "0200", 2, 3000);
        var top = RegisterProbe.prioritized(raw.snapshot(java.util.List.of("rWarn"), java.util.List.of("dis")), 2);
        check(top.size() == 2 && top.get(0).name().equals("xdis_1a") && top.get(1).name().equals("rWarn") && RegisterProbe.replied(rawRows) == 2 && RegisterProbe.changed(raw.snapshot(java.util.List.of("rWarn"), java.util.List.of("dis")), 5000, 3000) == 1, "an overflowing table keeps the most recently changed rows first");
        RideState rideState = new RideState();
        check(!rideState.snapshot().hillHold(1000), "no readings means no hill hold");
        rideState.speed(0, 1000); rideState.power(168, 1200);
        check(rideState.snapshot().hillHold(1500) && rideState.snapshot().hillHold(4000) && !rideState.snapshot().hillHold(4300), "standing still with the motor holding counts as hill hold until the readings go stale");
        rideState.speed(12, 4400); rideState.power(400, 4400);
        check(!rideState.snapshot().hillHold(4500), "moving at 1.2 km/h is not hill hold");
        rideState.speed(3, 5000); rideState.power(101, 5000);
        check(!rideState.snapshot().hillHold(5100) && rideState.snapshot().hillHold(5100, 100, 10) && !rideState.snapshot().hillHold(5100, 101, 10) && new RideState.Snapshot(0, 101, 5000, 5000).hillHold(5100), "0.3 km/h only counts once the speed threshold allows it, and power must exceed the threshold");
        rideState.clear(); check(!rideState.snapshot().hillHold(5100) && !rideState.snapshot().hasPower(), "clearing forgets the readings");
        rideState.speed(0, 6000); rideState.power(RideState.signedPower(63000), 6000);
        check(RideState.signedPower(63000) == -2536 && RideState.signedPower(320) == 320 && RideState.signedPower(65535) == -1 && RideState.signedPower(32767) == 32767 && rideState.snapshot().power() == -2536 && rideState.snapshot().hasPower() && !rideState.snapshot().hillHold(6100), "rPower is a signed 16-bit value: regeneration reads negative, stays a known reading and never counts as hill hold");
        HillHoldDetector detector = new HillHoldDetector();
        check(!detector.update(new RideState.Snapshot(0, 168, 1000, 1000), 1000, 100, 300, 0, 3000) && !detector.update(new RideState.Snapshot(0, 168, 3000, 3000), 3500, 100, 300, 0, 3000) && detector.update(new RideState.Snapshot(0, 168, 4000, 4000), 4000, 100, 300, 0, 3000) && detector.active(), "hill hold triggers only after the condition has held for the minimum time");
        check(detector.update(new RideState.Snapshot(0, 350, 5000, 5000), 5000, 100, 300, 0, 3000) && detector.update(new RideState.Snapshot(0, 168, 5500, 5500), 5500, 100, 300, 0, 3000) && detector.update(new RideState.Snapshot(0, 350, 6000, 6000), 6000, 100, 300, 0, 3000) && detector.update(new RideState.Snapshot(0, 350, 8900, 8900), 8900, 100, 300, 0, 3000) && !detector.update(new RideState.Snapshot(0, 350, 9000, 9000), 9000, 100, 300, 0, 3000) && !detector.active(), "leaving the condition ends hill hold only after the minimum time; returning inside restarts the release timer");
        check(!detector.update(new RideState.Snapshot(0, 168, 9000, 9000), 9000, 100, 300, 0, 3000) && detector.update(new RideState.Snapshot(0, 168, 12000, 12000), 12000, 100, 300, 0, 3000) && detector.update(null, 15000, 100, 300, 0, 3000) && !detector.update(null, 18000, 100, 300, 0, 3000), "re-entry needs the minimum time again and stale or missing readings count as leaving the condition");
        check(!new RideState.Snapshot(0, 300, 1000, 1000).hillHold(1000, 100, 299, 0) && new RideState.Snapshot(0, 300, 1000, 1000).hillHold(1000, 100, 300, 0), "the maximum power is inclusive");
        detector.reset(); check(!detector.active(), "reset clears the active flag");
        check(binding.connected(replacement) && !binding.expired(110000), "framework reconnect during grace restores live binding");
        long newest = binding.begin(120000);
        check(binding.connected(newest), "new replacement can connect");
        check(!binding.disconnected(replacement, 121000) && !binding.expired(130000), "old disconnect cannot invalidate a new live connection");
        // A lost STOP must survive reconnection and a cancellation racing a late BEGIN acknowledgement.
        PendingStops stops = new PendingStops();
        stops.add(null); stops.add("invalid");
        check(stops.size() == 0 && stops.first() == null, "invalid cancellation cannot enter retry queue");
        stops.add(first); PendingStops.Entry inFlight = stops.first();
        check(inFlight.request.equals(first), "offline cancellation retained until acknowledged");
        stops.add(first);
        check(stops.size() == 1, "repeated cancellation coalesces by session");
        stops.acknowledged(inFlight);
        check(stops.size() == 1 && stops.first() != inFlight, "ack from STOP before late BEGIN cannot erase renewed cancellation");
        stops.add(second); PendingStops.Entry latestStop = stops.first();
        stops.acknowledged(latestStop);
        check(stops.size() == 1 && stops.first().request.equals(second), "ack affects only the matching cancellation");
        stops.acknowledged(latestStop);
        check(stops.size() == 1, "duplicate old ack cannot remove the next session's stop");
        stops.acknowledged(stops.first());
        check(stops.size() == 0 && stops.first() == null, "all acknowledged cancellations leave an empty queue");
        stops.add(first);
        check(stops.first().request.equals(first), "cancellation may be requeued even after an earlier ack");
        // A chat may truncate the pasted report; signature discovery must not hide the latest failure.
        String noisy = "12:00 MODULE loaded\n12:01 BRIDGE connected\n12:02 VD STATUS active=false\n";
        for (int i = 0; i < 300; i++) noisy += "12:03 SIGNATURE long.method." + i + "\n";
        noisy += "12:04 DIRECT ENTRY cardShown=true\n12:04 STAT replaced=0\n12:05 BRIDGE module process pid=99\n";
        String digest = LogDigest.recent(noisy, 120);
        check(digest.startsWith("12:05 BRIDGE module process pid=99"), "newest process transition leads the summary");
        check(digest.contains("VD STATUS active=false"), "failure survives hundreds of verbose signatures");
        check(!digest.contains("SIGNATURE") && !digest.contains("DIRECT ENTRY") && !digest.contains("STAT replaced"), "routine discovery and counters cannot crowd out failures");
        check(digest.length() <= 120, "pasted event digest obeys character budget");
        check(LogDigest.recent("x".repeat(2000), 100).length() == 100, "single long failure remains bounded");
        check(LogDigest.recent("old\r\nnew", 20).equals("new\nold"), "Windows line endings preserve newest-first order");
        check(LogDigest.recent(null, 20).isEmpty() && LogDigest.recent("message", 0).isEmpty(), "empty diagnostic inputs are valid");
        check(LogDigest.head("ok😀later", 4).equals("ok…"), "clipping cannot split a surrogate pair");
        check(LogDigest.head("unchanged", 20).equals("unchanged"), "short diagnostic text is preserved");
        // Fixed Surface pixels must reach the same visual targets in all four logical rotations.
        mapped(DisplayInputTransform.matrix(0, 800, 480, 800, 480), 100, 80, 100, 80);
        mapped(DisplayInputTransform.matrix(1, 800, 480, 480, 800), 100, 80, 80, 700);
        mapped(DisplayInputTransform.matrix(2, 800, 480, 800, 480), 100, 80, 700, 400);
        mapped(DisplayInputTransform.matrix(3, 800, 480, 480, 800), 100, 80, 400, 100);
        mapped(DisplayInputTransform.matrix(1, 800, 480, 480, 800), 700, 400, 400, 100);
        mapped(DisplayInputTransform.matrix(3, 800, 480, 480, 800), 700, 400, 80, 700);
        mapped(DisplayInputTransform.matrix(1, 480, 800, 800, 480), 80, 100, 100, 400);
        mapped(DisplayInputTransform.matrix(3, 480, 800, 800, 480), 80, 100, 700, 80);
        mapped(DisplayInputTransform.matrix(1, 800, 480, 960, 1600), 100, 80, 160, 1400);
        for (int r = 0; r < 4; r++) {
            boolean odd = (r & 1) != 0;
            mapped(DisplayInputTransform.matrix(r, 800, 480, odd ? 480 : 800, odd ? 800 : 480),
                    400, 240, odd ? 240 : 400, odd ? 400 : 240);
        }
        rejects(() -> DisplayInputTransform.matrix(-1, 800, 480, 480, 800), "negative rotation rejected");
        rejects(() -> DisplayInputTransform.matrix(4, 800, 480, 480, 800), "unknown rotation rejected");
        rejects(() -> DisplayInputTransform.matrix(0, 0, 480, 480, 800), "empty Surface rejected");
        rejects(() -> DisplayInputTransform.matrix(0, 800, 480, 480, 0), "removed logical display rejected");
        // A task moved to the phone leaves an empty display; transitions/errors must not become false recovery prompts.
        AppRecoveryState recovery = new AppRecoveryState();
        recovery.started(0);
        recovery.sample(0, false); recovery.sample(2999, false);
        check(recovery.state() == AppRecoveryState.HIDDEN, "first app launch gets a grace period");
        check(!recovery.restart(2999), "cannot relaunch before an empty display is confirmed");
        recovery.sample(4000, false); recovery.sample(5199, false);
        check(recovery.state() == AppRecoveryState.HIDDEN, "a short empty transition does not show recovery");
        recovery.sample(5200, false);
        check(recovery.state() == AppRecoveryState.MISSING, "persistent empty display offers restart");
        check(recovery.restart(5300) && recovery.state() == AppRecoveryState.RESTARTING, "explicit restart starts one recovery attempt");
        check(!recovery.restart(5301), "double tap cannot launch a second recovery attempt");
        recovery.sample(8300, false); recovery.sample(9499, false);
        check(recovery.state() == AppRecoveryState.RESTARTING, "keep pending UI until task absence is confirmed again");
        recovery.sample(9500, false);
        check(recovery.state() == AppRecoveryState.MISSING, "failed task return offers another attempt");
        check(recovery.restart(9600), "retry accepted after failed recovery");
        recovery.sample(9700, true);
        check(recovery.state() == AppRecoveryState.HIDDEN, "returning app immediately removes recovery UI");
        check(!recovery.restart(9701), "late click cannot relaunch an app already back on the display");
        recovery.sample(14000, false); recovery.sample(14500, true); recovery.sample(15000, false);
        recovery.sample(16199, false);
        check(recovery.state() == AppRecoveryState.HIDDEN, "brief occupancy resets the consecutive-empty interval");
        recovery.unknown(); recovery.sample(17000, false); recovery.sample(18199, false);
        check(recovery.state() == AppRecoveryState.HIDDEN, "failed task queries do not count as empty samples");
        recovery.sample(18200, false);
        check(recovery.state() == AppRecoveryState.MISSING, "fresh empty samples recover after a query failure");
        recovery.unknown();
        check(recovery.state() == AppRecoveryState.HIDDEN, "unknown task state hides a stale prompt");
        recovery.failed();
        check(recovery.state() == AppRecoveryState.MISSING, "launch exception keeps retry available");
        recovery.started(20000);
        check(recovery.state() == AppRecoveryState.HIDDEN && !recovery.restart(20000), "new display does not inherit previous recovery state");
        recovery.sample(30000, true); recovery.sample(60000, true);
        check(recovery.state() == AppRecoveryState.HIDDEN, "occupied task stays usable regardless of black or unchanged pixels");
        ProjectionTests.run();
        LogExportTests.run();
        CalibrationTests.run();
        NotificationTests.run();
        TireTelemetryTests.run();
        BatteryTelemetryTests.run();
        System.out.println("PASS: " + assertions + " assertions (RGBA/canvas, projection consent/size, input/rotation, lease/stop cancellation, settings, hook scope, ownership, service reconnection, statistics, app recovery and music)");
    }
    /** The drawn picture source: its settings clamp and keep at least one field, and its geometry stays inside the free area. */
    private static void drawTests() {
        DrawSettings d = new DrawSettings(83, DrawSettings.NONE, DrawSettings.NONE, DrawSettings.NONE);
        check(d.maxSpeed() == 80 && d.first() == DrawSettings.VOLTAGE && d.shown().length == 1, "the gauge scale snaps to 10 km/h and an empty picture falls back to the voltage");
        check(new DrawSettings(5, 9, -1, DrawSettings.POWER).maxSpeed() == DrawSettings.MIN_MAX_SPEED && new DrawSettings(999, 1, 2, 3).maxSpeed() == DrawSettings.MAX_MAX_SPEED, "the scale stays between 30 and 200");
        DrawSettings three = new DrawSettings(120, DrawSettings.SPEED, DrawSettings.TYRES, DrawSettings.BMS_SOC);
        check(Arrays.equals(three.shown(), new int[]{DrawSettings.SPEED, DrawSettings.TYRES, DrawSettings.BMS_SOC}) && three.uses(DrawSettings.TYRES) && !three.uses(DrawSettings.VOLTAGE), "up to three fields keep their order");
        check(new DrawSettings(80, DrawSettings.NONE, DrawSettings.POWER, DrawSettings.NONE).shown().length == 1 && DrawSettings.DEFAULT.label().equals("表程 80 km/h，电压，功率"), "empty slots drop out of the shown list");
        check(DrawSettings.read((k, f) -> k.equals("draw_max_speed") ? 100 : f).maxSpeed() == 100 && DrawSettings.read((k, f) -> f).equals(DrawSettings.DEFAULT), "settings read from preferences with defaults");
        DrawLayout l = DrawLayout.of(848, 480, 0, 0, 2);
        check(l.centerY() - l.radius() >= 0 && l.centerY() + l.radius() <= l.fieldTop() + l.radius() * 0.3f && l.fieldTop() < 480 && l.fieldWidth() == 424 && l.fieldCenter(1) == 636, "the gauge sits in the upper part and the fields split the strip");
        DrawLayout seven = DrawLayout.of(1024, 600, 48, 56, 0);
        check(seven.top() == 48 && seven.bottom() == 544 && seven.centerY() - seven.radius() >= 48 && seven.centerY() + seven.radius() <= 544, "the dashboard's own rows stay free of the gauge");
        check(DrawLayout.sweepFor(40, 80) == 135 && DrawLayout.sweepFor(200, 80) == 270 && DrawLayout.sweepFor(Float.NaN, 80) == 0 && DrawLayout.sweepFor(10, 0) == 0, "the arc fills in proportion and clamps");
    }
    private static void themeTests() {
        check(ThemeMode.dark(true, null, 0xff989da8), "night mode is not overridden by secondary grey text");
        check(ThemeMode.dark(true, 0xfffafbfc, 0xff20232a), "night configuration wins over stale pre-update light views");
        check(ThemeMode.dark(true, null, null), "empty night view is dark");
        check(ThemeMode.dark(false, 0xff17191f, 0xff989da8), "custom dark host container is recognized in a day configuration");
        check(!ThemeMode.dark(false, 0xfffafbfc, 0xffffffff), "white button text cannot override the light host surface");
        check(!ThemeMode.dark(false, null, 0xff989da8), "ambiguous grey alone does not force a dark day theme");
        check(ThemeMode.dark(false, null, 0xfff0f1f4), "bright neutral host text remains a dark-theme fallback");
        check(!ThemeMode.dark(false, null, 0xff20232a), "dark host text selects light mode");
        check(!ThemeMode.dark(false, null, null), "empty day view remains light");
        check(ThemeMode.surfaceDark(0x0017191f) == null, "transparent black cannot masquerade as a dark surface");
        check(ThemeMode.surfaceDark(0x8017191f) == null, "dim overlays are not theme evidence");
        check(ThemeMode.surfaceDark(0xff92caff) == null, "accent blue does not determine the host theme");
        check(ThemeMode.surfaceDark(0xff989da8) == null, "mid-grey surfaces are ambiguous");
        check(Boolean.TRUE.equals(ThemeMode.surfaceDark(0xff000000)), "black bar background selects light icons");
        check(Boolean.FALSE.equals(ThemeMode.surfaceDark(0xffffffff)), "white bar background selects dark icons");
    }
}
