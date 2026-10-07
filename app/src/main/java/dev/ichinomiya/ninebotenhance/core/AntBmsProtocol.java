package dev.ichinomiya.ninebotenhance.core;

import java.util.List;

/** The ANT boards: a ten-byte request frame and a length-prefixed answer, both bound by 7E A1 at the front and AA 55 at the back. */
public final class AntBmsProtocol extends BufferedBmsProtocol {
    private static final int REPLY_STATUS=0x11;
    private static final byte[] STATUS=request(0x01,0x0000,0xbe);
    private static final BmsProtocol.Endpoint ENDPOINT=new Endpoint(FFE0,FFE1,FFE1,false,false);
    private static byte[] request(int func,int address,int value){
        byte[] frame=new byte[10];
        frame[0]=0x7e;frame[1]=(byte)0xa1;frame[2]=(byte)func;
        frame[3]=(byte)(address&0xff);frame[4]=(byte)((address>>>8)&0xff);frame[5]=(byte)value;
        int crc=crc16(frame,1,5);frame[6]=(byte)(crc&0xff);frame[7]=(byte)((crc>>>8)&0xff);
        frame[8]=(byte)0xaa;frame[9]=0x55;
        return frame;
    }
    static boolean name(String label){
        if(label==null)return false;String lower=label.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ant@")||lower.startsWith("ant");
    }
    @Override public int id(){return BmsSettings.PROTOCOL_ANT;}
    @Override public List<Endpoint> endpoints(){return List.of(ENDPOINT);}
    @Override public byte[][] begin(long now){return new byte[][]{STATUS};}
    @Override public byte[][] poll(long now,long lastDataAt){return new byte[][]{STATUS};}
    @Override public long silenceMs(int pollMs){return pollMs*3L+2000;}
    @Override protected int headerLength(){return 2;}
    @Override protected boolean atHeader(int offset){return (buffer[offset]&0xff)==0x7e&&(buffer[offset+1]&0xff)==0xa1;}
    @Override protected int frameLength(){
        if(length<6)return NEED_MORE;
        int declared=10+(buffer[5]&0xff);
        if((buffer[2]&0xff)!=REPLY_STATUS){
            if(length<declared)return NEED_MORE;
            if((buffer[declared-2]&0xff)!=0xaa||(buffer[declared-1]&0xff)!=0x55)return BAD;
            return crc16(buffer,1,5+(buffer[5]&0xff))==u16(buffer,declared-4)?declared:BAD;
        }
        if(length<declared)return NEED_MORE;
        boolean valid=crc16(buffer,1,5+(buffer[5]&0xff))==u16(buffer,declared-4);
        if((buffer[declared-2]&0xff)==0xaa&&(buffer[declared-1]&0xff)==0x55)return valid?declared:BAD;
        int limit=Math.min(length-2,declared+16);
        for(int i=declared;i<=limit;i++)if((buffer[i]&0xff)==0xaa&&(buffer[i+1]&0xff)==0x55)
            return crc16(buffer,1,5+(buffer[5]&0xff))==u16(buffer,i-2)?i+2:BAD;
        return length<=declared+16+2?NEED_MORE:BAD;
    }
    @Override protected BmsData take(int size,long now){
        if((buffer[2]&0xff)!=REPLY_STATUS)return null;
        int sensors=buffer[8]&0xff,cells=buffer[9]&0xff;
        if(sensors>6||cells<1||cells>32)return null;
        int dyn=2*cells+2*sensors;
        if(112+dyn>size-4)return null;
        int[] cell=new int[cells];for(int i=0;i<cells;i++)cell[i]=u16(buffer,6+2*i);
        int[] temps=new int[sensors==0?1:sensors];
        if(sensors==0)temps[0]=s16(buffer,6+dyn);
        else for(int i=0;i<sensors;i++)temps[i]=s16(buffer,6+2*cells+2*i);
        float volts=u16(buffer,10+dyn)*0.01f;
        float amps=s16(buffer,12+dyn)*0.1f;
        int mos=(buffer[？18+dyn]&0xff)!=0?1:0;
mos|=(buffer[19+dyn]&0xff)!=0?2:0;
        int maxMv=u16(buffer,84+dyn), minMv=u16(buffer,82+dyn);
return new BmsData("",mos,cells,u32(buffer,50+dyn)/1000000f,u32(buffer,54+dyn)/1000000f,volts,amps,
        Math.round(volts*amps),u16(buffer,42+dyn),temps,
        maxMv,minMv,(maxMv+minMv)/2,maxMv-minMv,u32(buffer,58+dyn)*0.001f,0,cell,now);
    }
}
