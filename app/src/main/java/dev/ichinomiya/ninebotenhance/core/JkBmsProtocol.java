package dev.ichinomiya.ninebotenhance.core;

import java.util.Arrays;
import java.util.List;

/** The JK boards: fixed 300-byte answers that push themselves once asked, in either the 24-slot or the 32-slot layout. */
public final class JkBmsProtocol extends BufferedBmsProtocol {
    private static final int FRAME=300;
    private static final byte[] INFO=request(0x97),STREAM=request(0x96);
    private static final int[] TEMPERATURES={130,132,222,224,226};
    private static final BmsProtocol.Endpoint ENDPOINT=new Endpoint(FFE0,FFE1,FFE1,false,false);
    private static byte[] request(int cmd){
        byte[] frame=new byte[20];
        frame[0]=0x55;frame[1]=(byte)0xaa;frame[2]=(byte)0x90;frame[3]=(byte)0xeb;frame[4]=(byte)cmd;
        int sum=0;for(int i=0;i<19;i++)sum+=frame[i]&0xff;
        frame[19]=(byte)sum;
        return frame;
    }
    static boolean name(String label){return label!=null&&label.trim().toLowerCase(java.util.Locale.ROOT).startsWith("jk");}
    private int layout;
    @Override public int id(){return BmsSettings.PROTOCOL_JK;}
    @Override public List<Endpoint> endpoints(){return List.of(ENDPOINT);}
    @Override public byte[][] begin(long now){return new byte[][]{INFO};}
    @Override public byte[] followUp(long now,long lastDataAt){return lastDataAt==0?STREAM:null;}
    @Override public long followUpDelayMs(){return 500;}
    @Override public byte[][] poll(long now,long lastDataAt){
        return lastDataAt==0||now-lastDataAt>=4000?new byte[][]{STREAM}:new byte[][]{};
    }
    @Override public long silenceMs(int pollMs){return Math.max(pollMs*3L+2000,15000);}
    @Override protected int headerLength(){return 4;}
    @Override protected boolean atHeader(int offset){
        return (buffer[offset]&0xff)==0x55&&(buffer[offset+1]&0xff)==0xaa&&(buffer[offset+2]&0xff)==0x90&&(buffer[offset+3]&0xff)==0xeb;
    }
    @Override protected int frameLength(){
        if(length<FRAME)return NEED_MORE;
        int sum=0;for(int i=0;i<FRAME-1;i++)sum+=buffer[i]&0xff;
        return (sum&0xff)==(buffer[FRAME-1]&0xff)?FRAME:BAD;
    }
    @Override protected BmsData take(int size,long now){
        if((buffer[4]&0xff)!=0x02)return null;
        int narrow=score(24,0),wide=score(32,32);
        int best=narrow>=wide?0:1;
        if(Math.max(narrow,wide)>=8&&best!=layout)layout=best;
        return status(now);
    }
    /** One layout scored the way the board's own firmware does it, so a frame that only fits the other one loses. */
    private int score(int slots,int late){
        int valid=0,suspicious=0;long sum=0;
        for(int i=0;i<slots;i++){
            int mv=u16(buffer,6+2*i);
            if(mv==0)continue;
            if(mv<100||mv>6500){suspicious++;continue;}
            valid++;sum+=mv;
        }
        int score=valid>=2&&valid<=slots?6:-12;score-=2*suspicious;
        float volts=u32(buffer,118+late)/1000f;
        score+=volts>=1&&volts<=1000?5:-10;
        score+=Math.abs(s32(buffer,126+late))/1000f<=5000?2:-6;
        score+=(buffer[141+late]&0xff)<=100?3:-7;
        score+=Math.abs(sum-Math.round(volts*1000))<=Math.max(2000,Math.round(volts*80))?8:-8;
        return score;
    }
    private BmsData status(long now){
        int late=layout==0?0:32,slots=layout==0?24:32;
        int[] cell=new int[slots];int used=0;
        for(int i=0;i<slots;i++){
            int mv=u16(buffer,6+2*i);
            if(mv==0||mv<100||mv>6500)continue;
            cell[used++]=mv;
        }
        int[] cells=Arrays.copyOf(cell,used);
        CellStats stats=statistics(cells,cells.length);
        float volts=u32(buffer,118+late)/1000f,amps=-s32(buffer,126+late)/1000f;
        if(volts<1||volts>1000||Math.abs(amps)>5000)return null;
        int[] temps=new int[TEMPERATURES.length];int count=0;
        for(int offset:TEMPERATURES){
            int t=Math.round(s16(buffer,offset+late)*0.1f);
            if(t<-80||t>200)continue;
            temps[count++]=t;
        }
        if(count==0)temps[count++]=Math.round(s16(buffer,layout==0?134:144)*0.1f);
        int[] out=new int[count];System.arraycopy(temps,0,out,0,count);
        int mos=(buffer[166+late]&0xff)!=0?1:0;
        mos|=(buffer[167+late]&0xff)!=0?2:0;
        return new BmsData("",mos,cells.length,u32(buffer,146+late)*0.001f,u32(buffer,142+late)*0.001f,volts,amps,
                Math.round(volts*amps),Math.min(100,buffer[141+late]&0xff),out,stats.maxMv(),stats.minMv(),stats.avgMv(),
                stats.diffMv(),u32(buffer,154+late)*0.001f,(int)Math.min(Integer.MAX_VALUE,u32(buffer,150+late)),cells,now);
    }
}
