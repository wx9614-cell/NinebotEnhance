import dev.ichinomiya.ninebotenhance.core.*;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

final class BmsTests {
    private static byte[] bytes(int... values){byte[] out=new byte[values.length];for(int i=0;i<values.length;i++)out[i]=(byte)values[i];return out;}
    static void run(){
        // Curve arithmetic against the published generator multiples.
        Secp256k1.Point two=Secp256k1.multiply(BigInteger.valueOf(2),Secp256k1.G);
        CoreTests.check(two.x().equals(new BigInteger("C6047F9441ED7D6D3045406E95C07CD85C778E4B8CEF3CA7ABAC09B95C709EE5",16))
                &&two.y().equals(new BigInteger("1AE168FEA63DC339A3C58419466CEAEEF7F632653266D0E1236431A950CFE52A",16)),"2G matches the secp256k1 reference");
        Secp256k1.Point three=Secp256k1.multiply(BigInteger.valueOf(3),Secp256k1.G);
        CoreTests.check(three.x().equals(new BigInteger("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",16)),"3G matches the secp256k1 reference");
        CoreTests.check(Secp256k1.multiply(Secp256k1.N,Secp256k1.G)==null&&Secp256k1.onCurve(Secp256k1.G),"nG is the point at infinity and G lies on the curve");
        SecureRandom random=new SecureRandom();
        byte[] a=Secp256k1.privateKey(random),b=Secp256k1.privateKey(random);
        byte[] pa=Secp256k1.publicKey(a),pb=Secp256k1.publicKey(b);
        CoreTests.check(pa.length==64&&pb.length==64&&Arrays.equals(Secp256k1.shared(a,pb),Secp256k1.shared(b,pa)),"both sides of the handshake derive the same 32-byte secret");
        CoreTests.rejects(()->Secp256k1.decode(new byte[64]),"a point off the curve is refused");
        // Request frames: reversed header, little endian counters, content of at most four bytes reversed, checksum over everything before it.
        byte[] data=DlBmsProtocol.frame(DlBmsProtocol.FC_DATA,new byte[0],1);
        CoreTests.check(Arrays.equals(data,bytes(0xA5,0x5A,0x00,0x40,0x17,0x01,0x00,0x01,0x00,0x01,0x00,0x00,0x00,0x59,0x01)),"the FC17 request is framed with its running sum: "+hex(data));
        byte[] param=DlBmsProtocol.frame(0x05,bytes(0x12,0x34),7);
        CoreTests.check(param[13]==0x34&&param[14]==0x12&&(param[11]&0xff)==2,"short content is byte reversed");
        byte[] key=new byte[64];for(int i=0;i<64;i++)key[i]=(byte)i;byte[] exchange=DlBmsProtocol.frame(DlBmsProtocol.FC_KEY,key,2);
        CoreTests.check(exchange.length==13+64+2&&exchange[13]==0&&exchange[76]==63,"a 64-byte public key is sent as is");
        // Answers: natural header; parse, length and checksum checks.
        byte[] content=bytes(0x42,0x4C,0x00);byte[] answer=answer(DlBmsProtocol.FC_NAME,3,content);
        DlBmsProtocol.Response r=DlBmsProtocol.parse(answer,answer.length);
        CoreTests.check(r!=null&&r.fc()==DlBmsProtocol.FC_NAME&&r.serial()==3&&Arrays.equals(r.content(),content),"an answer frame parses back to its fields");
        CoreTests.check(DlBmsProtocol.frameLength(answer,answer.length)==answer.length&&DlBmsProtocol.frameLength(answer,5)==-1&&DlBmsProtocol.parse(answer,answer.length-1)==null,"the frame length comes from the header and a short buffer does not parse");
        byte[] broken=answer.clone();broken[broken.length-1]^=1;
        CoreTests.check(DlBmsProtocol.parse(broken,broken.length)==null,"a wrong checksum is dropped");
        // AES-128-CBC with zero padding round-trips and pads to whole blocks only when needed.
        byte[] shared=new byte[32];for(int i=0;i<32;i++)shared[i]=(byte)(i*7);
        byte[] plain=bytes(1,2,3,4,5);byte[] cipher=DlBmsProtocol.encrypt(DlBmsProtocol.key(shared),DlBmsProtocol.iv(shared),plain);
        byte[] back=DlBmsProtocol.decrypt(DlBmsProtocol.key(shared),DlBmsProtocol.iv(shared),cipher);
        CoreTests.check(cipher.length==16&&Arrays.equals(Arrays.copyOf(back,5),plain)&&back[5]==0,"zero padded CBC round trip");
        CoreTests.check(DlBmsProtocol.encrypt(DlBmsProtocol.key(shared),DlBmsProtocol.iv(shared),new byte[32]).length==32,"aligned input is not padded further");
        // FC17: the documented sample values in the full 142-byte shape.
        byte[] full=new byte[142];System.arraycopy("BAT3".getBytes(),0,full,0,4);full[32]=0x0f;full[33]=20;
        put32(full,34,300);put32(full,38,264);put16(full,42,804);put16(full,44,3);put32(full,46,24);put16(full,50,88);full[52]=1;full[53]=21;
        put16(full,62,4032);put16(full,64,4010);put16(full,66,4024);put16(full,68,22);put32(full,70,125);put32(full,74,5);
        for(int i=0;i<20;i++)put16(full,78+i*2,4010+i);
        BmsData d=DlBmsProtocol.parseData(full,1000);
        CoreTests.check(d!=null&&d.name().equals("BAT3")&&d.cells()==20&&d.volts()==80.4f&&d.amps()==0.3f&&d.watts()==24&&d.soc()==88&&d.cycles()==5&&d.diffMv()==22&&d.temps()[0]==21&&d.cellMv().length==20&&d.cellMv()[19]==4029&&d.known(),"the full FC17 shape parses: "+d.describe());
        put16(full,44,0xfffe);put32(full,46,0xffffffe8L);put16(full,78,4020*134/10);
        BmsData charging=DlBmsProtocol.parseData(full,2000);
        CoreTests.check(charging.amps()==-0.2f&&charging.watts()==-24&&charging.charging()&&charging.cellMv()[0]==4020,"signed current and power, and old-firmware cell scaling");
        byte[] shortShape=new byte[110];shortShape[0]=0x0f;shortShape[1]=20;put32(shortShape,2,300);put32(shortShape,6,264);put16(shortShape,10,804);put16(shortShape,12,3);put32(shortShape,14,24);put16(shortShape,18,88);shortShape[20]=1;shortShape[21]=25;
        for(int i=0;i<20;i++)put16(shortShape,46+i*2,4000+i);
        BmsData s=DlBmsProtocol.parseData(shortShape,3000);
        CoreTests.check(s!=null&&s.name().isEmpty()&&s.volts()==80.4f&&s.soc()==88&&s.maxCellMv()==4019&&s.minCellMv()==4000&&s.diffMv()==19&&s.temps()[0]==25,"the truncated shape derives the cell statistics");
        CoreTests.check(DlBmsProtocol.parseData(new byte[10],1)==null&&DlBmsProtocol.advertisement(0x6C64,"kjmk".getBytes())&&!DlBmsProtocol.advertisement(0x6C64,"abcd".getBytes())&&DlBmsProtocol.deviceName("DL-BMS")&&!DlBmsProtocol.deviceName("MOTORE"),"short buffers are rejected and the advertisement is recognised");
        // Settings, state and the card layout.
        BmsSettings settings=new BmsSettings("b4:f5:f6:77:77:0b",2750);
        CoreTests.check(settings.bound()&&settings.mac().equals("B4:F5:F6:77:77:0B")&&settings.pollMs()==2500&&new BmsSettings("",99999).pollMs()==BmsSettings.MAX_POLL_MS&&!BmsSettings.NONE.bound(),"BMS settings normalise the address and step the poll interval");
        BmsState state=BmsState.NONE.withData(d);
        CoreTests.check(state.ready()&&state.connected(1500,settings.limitMs())&&!state.connected(20000,settings.limitMs())&&!BmsState.of(BmsState.CONNECTING,"").connected(1500,settings.limitMs())&&state.describe().startsWith("已连接 SOC 88%")&&BmsState.of(BmsState.IDLE,"").describe().equals("未连接")&&!BmsState.of(BmsState.IDLE,"").connected(1500,settings.limitMs()),"the state reports a fresh reading and expires an old one");
        BmsCard.Layout layout=BmsCard.parse("1,2|4,4,9|7");
        CoreTests.check(layout.rows().get(0).equals(List.of(1,2))&&layout.rows().get(1).equals(List.of(4))&&layout.rows().get(2).equals(List.of(7))&&layout.encode().equals("1,2|4|7")&&layout.height()==3*BmsCard.ROW_HEIGHT,"the layout drops repeats and unknown fields and keeps three rows");
        BmsCard.Layout sparse=BmsCard.parse("|3||");
        CoreTests.check(sparse.rowCount()==1&&sparse.height()==BmsCard.ROW_HEIGHT&&sparse.rowOf(3)==1&&sparse.rowOf(1)==-1&&BmsCard.parse("").equals(BmsCard.DEFAULT)&&BmsCard.parse("|||").height()==BmsCard.ROW_HEIGHT,"empty rows are hidden and an empty layout still has one row");
        CoreTests.check(BmsCard.value(BmsCard.VOLTAGE,d).equals("80.4")&&BmsCard.value(BmsCard.SOC,BmsData.EMPTY).equals("--")&&BmsCard.unit(BmsCard.DIFF).equals("mV")&&BmsCard.label(BmsCard.CYCLES).equals("循环次数"),"field texts");
        CoreTests.check(WidgetSettings.CARDS.contains(WidgetSettings.BMS)&&(WidgetSettings.OFF_BY_DEFAULT&WidgetSettings.BMS)!=0&&!WidgetSettings.DEFAULT.enabled(WidgetSettings.BMS)&&WidgetSettings.index(WidgetSettings.BMS)>=0,"the BMS card exists, is off by default and takes a condition");
        CoreTests.check(!WidgetSettings.migrate(6,WidgetSettings.ALL,30,1000,5,30).enabled(WidgetSettings.BMS)&&WidgetSettings.migrate(7,WidgetSettings.ALL,30,1000,5,30).enabled(WidgetSettings.BMS),"saves from before the BMS existed do not inherit it");
        WidgetCondition bmsOnly=new WidgetCondition(WidgetCondition.WHILE,0,5,WidgetCondition.BMS_CONNECTED,0,160,0,30000,20,90,0,100);
        CoreTests.check(bmsOnly.matches(new WidgetCondition.Measurements(1,1,50,50,false,Float.NaN,Float.NaN,Float.NaN,Float.NaN,true))&&!bmsOnly.matches(new WidgetCondition.Measurements(1,1,50,50,false,Float.NaN,Float.NaN,Float.NaN,Float.NaN,false))&&bmsOnly.uses(WidgetCondition.BMS_CONNECTED),"the BMS-connected condition");
        WidgetSettings shown=WidgetSettings.DEFAULT.with(WidgetSettings.BMS,true);
        SidebarLayout.Stack stack=SidebarLayout.arrange(shown,WidgetSettings.BMS,0,new SidebarLayout.Sizes(100,190,100,190,190,190,120,190,2*BmsCard.ROW_HEIGHT),false);
        CoreTests.check(stack.bms()!=null&&stack.bms().bottom()==SidebarLayout.BOTTOM&&stack.bms().height()==2*BmsCard.ROW_HEIGHT&&stack.bms().width()==190&&stack.of(WidgetSettings.BMS)==stack.bms(),"the BMS card takes the height its rows need");
        boards();
    }
    /** The four plain protocols: the read command each board asks for, and one whole status frame taken apart. */
    private static void boards(){
        BmsProtocol ant=BmsProtocols.create(BmsSettings.PROTOCOL_ANT),jbd=BmsProtocols.create(BmsSettings.PROTOCOL_JBD),
                jk=BmsProtocols.create(BmsSettings.PROTOCOL_JK),yy=BmsProtocols.create(BmsSettings.PROTOCOL_YY);
        CoreTests.check(Arrays.equals(ant.begin(0)[0],bytes(0x7E,0xA1,0x01,0x00,0x00,0xBE,0x18,0x55,0xAA,0x55))
                &&Arrays.equals(jbd.begin(0)[0],bytes(0xDD,0xA5,0x03,0x00,0xFF,0xFD,0x77))
                &&Arrays.equals(jbd.followUp(0,0),bytes(0xDD,0xA5,0x04,0x00,0xFF,0xFC,0x77))
                &&Arrays.equals(yy.begin(0)[0],bytes(0x01,0x03,0x00,0x4B,0x00,0x5C,0x35,0xE5)),
                "every board is asked for its status with its own published command bytes");
        byte[] jkInfo=jk.begin(0)[0],jkStream=jk.poll(0,0)[0];
        CoreTests.check(jkInfo.length==20&&jkInfo[4]==(byte)0x97&&jkStream[4]==(byte)0x96&&jkInfo[19]==0x11&&jkStream[19]==0x10
                &&jk.poll(9000,9000).length==0&&Arrays.equals(jk.followUp(0,0),jkStream),"JK opens with the device info frame, then the stream, and stops once it is pushing");
        BmsData a=ant.accept(antStatus(1000),1000);
        CoreTests.check(a!=null&&a.cells()==16&&near(a.volts(),52.84f)&&near(a.amps(),0.3f)&&!a.charging()&&a.soc()==91
                &&near(a.capacityAh(),280.0f)&&Math.abs(a.remainingAh()-252.602325f)<1e-3&&a.temps()[0]==1&&a.temps()[1]==2
                &&a.cellMv()[0]==3300&&a.watts()==16&&a.mos()==3,"the ANT status frame reads as one whole pack: "+a.describe());
        CoreTests.check(ant.accept(garbage(37,0x7E),1100)==null&&ant.accept(antStatus(1200),1200)!=null,"an ANT frame behind junk still resynchronises");
        byte[] broken=antStatus(1300);broken[40]^=1;
        CoreTests.check(ant.accept(broken,1300)==null,"an ANT frame with a wrong checksum is dropped");
        byte[] basic=jbdBasic(1400);
        CoreTests.check(jbd.accept(Arrays.copyOf(basic,12),1400)==null,"a JBD frame in pieces waits for the rest");
        BmsData j=jbd.accept(Arrays.copyOfRange(basic,12,basic.length),1410);
        CoreTests.check(j!=null&&near(j.volts(),52.84f)&&near(j.amps(),0.3f)&&j.soc()==55&&j.cycles()==42&&near(j.capacityAh(),200.0f)
                &&near(j.remainingAh(),100.0f)&&j.cells()==8&&j.temps()[0]==25&&j.temps()[1]==30&&j.mos()==3,"the JBD basic frame parses: "+j.describe());
        BmsData cells=jbd.accept(jbdCells(1420),1420);
        CoreTests.check(cells!=null&&cells.cellMv().length==8&&cells.maxCellMv()==4100&&cells.minCellMv()==4100&&cells.diffMv()==0
                &&near(cells.volts(),52.84f)&&cells.soc()==55,"the JBD cell frame fills the voltages into the reading already held");
        byte[] badJbd=basic.clone();badJbd[badJbd.length-1]^=1;
        CoreTests.check(jbd.accept(badJbd,1430)==null,"a JBD frame with a wrong checksum is dropped");
        BmsData k=jk.accept(jkStatus(1500),1500);
        CoreTests.check(k!=null&&k.cells()==24&&near(k.volts(),79.2f)&&near(k.amps(),1.5f)&&!k.charging()&&k.soc()==77&&k.cycles()==12
                //&&near(k.capacityAh(),100.0f)&&near(k.remainingAh(),50.0f)&&k.mos()==3&&k.temps()[0]==25&&k.cellMv().length==24,"the JK status frame reads in the 24-slot layout: "+k.describe());
        byte[] badJk=jkStatus(1600);badJk[299]^=1;
        CoreTests.check(jk.accept(badJk,1600)==null&&jk.accept(jkStatus(1700),1700)!=null,"a JK frame with a wrong sum is dropped and the next one still parses");
        BmsData y=yy.accept(yyStatus(1800),1800);
        CoreTests.check(y!=null&&y.cells()==16&&near(y.volts(),52.84f)&&near(y.amps(),-3.0f)&&y.charging()&&y.soc()==88&&near(y.capacityAh(),280.0f)
                &&near(y.remainingAh(),200.0f)&&y.temps()[0]==25&&y.temps()[1]==30&&y.cellMv()[0]==3300&&y.mos()==3&&y.watts()==-159,"the 彦阳 frame reads through the Modbus response: "+y.describe());
        byte[] badYy=yyStatus(1900);badYy[100]^=1;
        CoreTests.check(yy.accept(badYy,1900)==null&&yy.accept(yyStatus(2000),2000)!=null,"a 彦阳 frame with a wrong CRC is dropped and the stream recovers");
        CoreTests.check(BmsProtocols.name("DL-BMS")==BmsSettings.PROTOCOL_DL&&BmsProtocols.name("ANT@24S") ==BmsSettings.PROTOCOL_ANT
                &&BmsProtocols.name("JBD-24S")==BmsSettings.PROTOCOL_JBD&&BmsProtocols.name("jk02_32S")==BmsSettings.PROTOCOL_JK
                &&BmsProtocols.name("HLK-B40")==BmsSettings.PROTOCOL_YY&&BmsProtocols.name("随机设备")==BmsSettings.PROTOCOL_UNKNOWN,
                "the advertised name picks the protocol");
        CoreTests.check(BmsProtocols.services(List.of("0000ff00-0000-1000-8000-00805f9b34fb"))==BmsSettings.PROTOCOL_JBD
                &&BmsProtocols.services(List.of("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))==BmsSettings.PROTOCOL_YY
                &&BmsProtocols.services(List.of("0000ffe0-0000-1000-8000-00805f9b34fb","0000ffe2-0000-1000-8000-00805f9b34fb"))==BmsSettings.PROTOCOL_DL
                &&BmsProtocols.services(List.of())==BmsSettings.PROTOCOL_UNKNOWN&&BmsProtocols.create(BmsSettings.PROTOCOL_DL)==null,
                "the exposed services settle the protocol when the name says nothing");
        BmsSettings forced=new BmsSettings("b4:f5:f6:77:77:0b",2750,BmsSettings.PROTOCOL_JK);
        CoreTests.check(forced.protocol()==BmsSettings.PROTOCOL_JK&&new BmsSettings("b4:f5:f6:77:77:0b",2750,99).protocol()==BmsSettings.PROTOCOL_DL
                &&forced.withProtocol(BmsSettings.PROTOCOL_ANT).protocol()==BmsSettings.PROTOCOL_ANT
                &&BmsSettings.protocolName(BmsSettings.PROTOCOL_YY).equals("彦阳")&&BmsSettings.NONE.protocol()==BmsSettings.PROTOCOL_DL&&!BmsSettings.NONE.preferBms()&&forced.withPreferBms(true).preferBms()&&BmsSettings.protocolName(BmsSettings.PROTOCOL_DL).equals("DL")&&BmsSettings.protocolName(0).equals("DL"),
                "the chosen protocol is stored, clamped and named");
    }
    /** A 152-byte ANT status answer for a sixteen cell, two sensor pack. */
    private static byte[] antStatus(long at){
        int cells=16,sensors=2,dyn=2*cells+2*sensors,total=116+dyn;
        byte[] f=new byte[total];
        f[0]=0x7E;f[1]=(byte)0xA1;f[2]=0x11;f[5]=(byte)(106+dyn);f[7]=3;f[8]=(byte)sensors;f[9]=(byte)cells;
        for(int i=0;i<cells;i++)put16(f,34+2*i,3300);
        put16(f,34+2*cells,1);put16(f,34+2*cells+2,2);
        put16(f,34+dyn,2);put16(f,36+dyn,7);
        put16(f,38+dyn,5284);put16(f,40+dyn,3);put16(f,42+dyn,91);put16(f,44+dyn,100);
        f[46+dyn]=1;f[47+dyn]=1;
        put32(f,50+dyn,280000000L);put32(f,54+dyn,252602325L);put32(f,58+dyn,4862138L);
        put16(f,74+dyn,3300);put16(f,78+dyn,3300);put16(f,82+dyn,0);put16(f,84+dyn,3300);
        put16(f,94+dyn,0xFAF2);
        int crc=crc16(f,1,5+(106+dyn));put16(f,112+dyn,crc);f[114+dyn]=(byte)0xAA;f[115+dyn]=0x55;
        return f;
    }
    /** A JBD basic answer: the twenty-three byte payload, its big-endian running sum and the 0x77 tail. */
    private static byte[] jbdBasic(long at){
        byte[] f=new byte[34];f[0]=(byte)0xDD;f[1]=0x03;f[3]=27;
        put16be(f,4,5284);put16be(f,6,-30);put16be(f,8,10000);put16be(f,10,20000);put16be(f,12,42);
        put16be(f,16,0);put16be(f,18,0);put16be(f,20,0x10);f[23]=55;f[24]=3;f[25]=8;f[26]=2;
        put16be(f,27,2981);put16be(f,29,3031);
        int sum=0;for(int i=2;i<=f.length-4;i++)sum=(sum+(f[i]&0xff))&0xffff;
        put16be(f,31,-sum);f[33]=0x77;
        return f;
    }
    private static byte[] jbdCells(long at){
        byte[] f=new byte[7+16];f[0]=(byte)0xDD;f[1]=0x04;f[3]=16;
        for(int i=0;i<8;i++)put16be(f,4+2*i,4100);
        int sum=0;for(int i=2;i<=f.length-4;i++)sum=(sum+(f[i]&0xff))&0xffff;
        put16be(f,f.length-3,-sum);f[f.length-1]=0x77;
        return f;
    }
    /** A JK status answer in the twenty-four slot layout. */
    private static byte[] jkStatus(long at){
        byte[] f=new byte[300];f[0]=0x55;f[1]=(byte)0xAA;f[2]=(byte)0xEB;f[3]=(byte)0x90;f[4]=0x02;
        for(int i=0;i<24;i++)put16(f,6+2*i,3300);
        put32(f,118,79200L);put32(f,126,-1500L);put16(f,130,250);put16(f,132,251);
        put16(f,140,0);f[141]=77;put32(f,142,50000L);put32(f,146,100000L);put32(f,150,12L);put32(f,154,250000L);
        f[158]=99;f[166]=1;f[167]=1;
        put16(f,222,(short)-1000);put16(f,224,(short)-1000);put16(f,226,(short)-1000);
        int sum=0;for(int i=0;i<299;i++)sum+=f[i]&0xff;f[299]=(byte)sum;
        return f;
    }
    /** A 彦阳 Modbus answer carrying the ninety-two holding registers. */
    private static byte[] yyStatus(long at){
        byte[] f=new byte[189];f[0]=1;f[1]=3;f[2]=(byte)184;
        f[3]=16;f[4]=1;
        put32(f,5,52840L);put32(f,9,-300L);
        for(int i=0;i<16;i++)put16(f,15+2*i,3300);
        f[3+74]=65;f[3+75]=65;f[3+76]=70;f[3+77]=65;
        put16(f,3+86,2800);put16(f,3+88,2000);f[3+90]=88;f[3+91]=100;
        put32(f,3+128,0);put32(f,3+154,0);
        int crc=crc16(f,0,187);put16(f,187,crc);
        return f;
    }
    private static byte[] garbage(int length,int first){
        byte[] junk=new byte[length];junk[0]=(byte)first;for(int i=1;i<length;i++)junk[i]=(byte)(0x11*i);
        return junk;
    }
    private static boolean near(float actual,float expected){return Math.abs(actual-expected)<1e-4f;}
    private static int crc16(byte[] data,int offset,int length){
        int crc=0xffff;
        for(int i=offset;i<offset+length;i++){crc^=data[i]&0xff;for(int bit=0;bit<8;bit++)crc=(crc&1)!=0?(crc>>>1)^0xa001:crc>>>1;}
        return crc&0xffff;
    }
    private static void put16be(byte[] b,int i,int v){b[i]=(byte)(v>>8);b[i+1]=(byte)v;}
    private static byte[] answer(int fc,int serial,byte[] content){
        byte[] f=new byte[13+content.length+2];f[0]=0x5a;f[1]=(byte)0xa5;f[2]=0;f[3]=0x40;f[4]=(byte)fc;f[5]=(byte)serial;f[6]=(byte)(serial>>8);f[7]=1;f[9]=1;f[11]=(byte)content.length;f[12]=(byte)(content.length>>8);
        System.arraycopy(content,0,f,13,content.length);int sum=DlBmsProtocol.checksum(f,f.length-2);f[f.length-2]=(byte)sum;f[f.length-1]=(byte)(sum>>8);return f;
    }
    private static void put16(byte[] b,int i,int v){b[i]=(byte)v;b[i+1]=(byte)(v>>8);}
    private static void put16(byte[] b,int i,short v){b[i]=(byte)v;b[i+1]=(byte)(v>>8);}
    private static void put32(byte[] b,int i,long v){b[i]=(byte)v;b[i+1]=(byte)(v>>8);b[i+2]=(byte)(v>>16);b[i+3]=(byte)(v>>24);}
    private static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format(java.util.Locale.ROOT,"%02X ",v));return b.toString().trim();}
}
