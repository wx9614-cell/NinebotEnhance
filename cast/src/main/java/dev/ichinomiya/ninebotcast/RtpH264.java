package dev.ichinomiya.ninebotcast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 通用 H.264 over RTP/UDP packetizer. It accepts Annex-B and 4-byte AVCC access units. */
final class RtpH264 {
    private final DatagramSocket socket;
    private final InetAddress address;
    private final int port;
    private final int mtu;
    private int sequence;
    private final int ssrc = new Random().nextInt();
    private byte[] codecConfig;
    long frames, packets, bytes, fragments;

    RtpH264(String host, int port, int mtu) throws Exception {
        address = InetAddress.getByName(host);
        this.port = port;
        this.mtu = Math.max(256, mtu);
        socket = new DatagramSocket();
        LogStore.add("RTP 初始化 host=" + host + " port=" + port + " mtu=" + this.mtu + " ssrc=" + ssrc);
    }

    void setCodecConfig(byte[] data) {
        if (data == null || data.length == 0) return;
        codecConfig = data.clone();
        LogStore.add("RTP 已保存 H264 codec config bytes=" + data.length);
    }

    void sendAccessUnit(byte[] data, long ptsUs) throws IOException {
        List<Nal> nals = split(data);
        if (nals.isEmpty()) return;
        boolean keyFrame = false;
        for (Nal nal : nals) if ((nal.data[nal.offset] & 0x1f) == 5) keyFrame = true;
        if (keyFrame && codecConfig != null) sendCodecConfig(ptsUs);
        sendNals(nals, ptsUs);
        frames++;
        if ((frames % 30) == 0) LogStore.add("RTP 发送进度 frames=" + frames + " packets=" + packets + " bytes=" + bytes);
    }

    private void sendCodecConfig(long ptsUs) throws IOException {
        List<Nal> nals = split(codecConfig);
        sendNals(nals, ptsUs);
    }

    private void sendNals(List<Nal> nals, long ptsUs) throws IOException {
        long timestamp = (ptsUs * 90L) / 1000L;
        for (int i = 0; i < nals.size(); i++) {
            Nal nal = nals.get(i);
            sendNal(nal.data, nal.offset, nal.length, i == nals.size() - 1, timestamp);
        }
    }

    private void sendNal(byte[] data, int start, int length, boolean marker, long timestamp) throws IOException {
        if (length <= 0) return;
        if (length <= mtu - 12) {
            byte[] packet = new byte[12 + length];
            header(packet, marker, timestamp);
            System.arraycopy(data, start, packet, 12, length);
            socket.send(new DatagramPacket(packet, packet.length, address, port));
            packets++;
            bytes += packet.length;
            return;
        }

        int type = data[start] & 0x1f;
        int maxPayload = mtu - 14;
        int offset = start + 1;
        while (offset < start + length) {
            int chunk = Math.min(maxPayload, start + length - offset);
            boolean first = offset == start + 1;
            boolean last = offset + chunk >= start + length;
            byte[] packet = new byte[14 + chunk];
            header(packet, marker && last, timestamp);
            packet[12] = (byte) ((data[start] & 0xe0) | 28);
            packet[13] = (byte) ((type & 0x1f) | (first ? 0x80 : 0) | (last ? 0x40 : 0));
            System.arraycopy(data, offset, packet, 14, chunk);
            socket.send(new DatagramPacket(packet, packet.length, address, port));
            packets++;
            bytes += packet.length;
            fragments++;
            offset += chunk;
        }
    }

    private void header(byte[] packet, boolean marker, long timestamp) {
        packet[0] = (byte) 0x80;
        packet[1] = (byte) ((marker ? 0x80 : 0) | 96);
        packet[2] = (byte) (sequence >>> 8);
        packet[3] = (byte) sequence++;
        put(packet, 4, timestamp);
        put(packet, 8, ssrc & 0xffffffffL);
    }

    private static void put(byte[] packet, int offset, long value) {
        for (int i = 3; i >= 0; i--) packet[offset + 3 - i] = (byte) (value >>> (i * 8));
    }

    private static List<Nal> split(byte[] data) {
        List<Nal> result = new ArrayList<>();
        if (data == null || data.length == 0) return result;
        int firstStart = findStartCode(data, 0);
        if (firstStart >= 0) {
            int cursor = firstStart;
            while (cursor < data.length) {
                int payload = skipStartCode(data, cursor);
                int next = findStartCode(data, payload);
                int end = next >= 0 ? next : data.length;
                if (end > payload) result.add(new Nal(data, payload, end - payload));
                if (next < 0) break;
                cursor = next;
            }
            return result;
        }

        // MediaCodec AVC output is commonly 4-byte length-prefixed (AVCC).
        int cursor = 0;
        boolean lengthPrefixed = data.length >= 4;
        while (lengthPrefixed && cursor + 4 <= data.length) {
            int length = ((data[cursor] & 0xff) << 24) | ((data[cursor + 1] & 0xff) << 16)
                    | ((data[cursor + 2] & 0xff) << 8) | (data[cursor + 3] & 0xff);
            cursor += 4;
            if (length <= 0 || cursor + length > data.length) { lengthPrefixed = false; break; }
            result.add(new Nal(data, cursor, length));
            cursor += length;
        }
        if (lengthPrefixed && cursor == data.length && !result.isEmpty()) return result;

        result.clear();
        result.add(new Nal(data, 0, data.length));
        return result;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) return i;
            if (i + 4 < data.length && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) return i;
        }
        return -1;
    }

    private static int skipStartCode(byte[] data, int offset) {
        return offset + (offset + 3 < data.length && data[offset + 2] == 1 ? 3 : 4);
    }

    void close() {
        socket.close();
        LogStore.add("RTP 关闭 frames=" + frames + " packets=" + packets + " fragments=" + fragments + " bytes=" + bytes);
    }

    private static final class Nal {
        final byte[] data;
        final int offset;
        final int length;
        Nal(byte[] data, int offset, int length) { this.data = data; this.offset = offset; this.length = length; }
    }
}
