package net.pictakshay.watchlink;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * The MoYoung / Da Fit command protocol used by the Noise Icon 2 (manufacturer "MOYOUNG-V2").
 * Command numbers and payload layouts follow the reverse engineering in Gadgetbridge's Da Fit
 * support (DaFitConstants.java by krzys-h).
 *
 * Commands are written to fee2 and answers arrive as notifications on fee3, both framed as
 *   FE EA, 0x20 + (length >> 8), length & 0xFF, command, payload...
 * where length counts the whole packet. Long packets are split into MTU-sized writes.
 *
 * No Android APIs here, so it can be unit-tested on a plain JVM.
 */
final class WatchProtocol {
    private WatchProtocol() { }

    static final String SERVICE = "0000feea-0000-1000-8000-00805f9b34fb";
    static final String STEPS_CHAR = "0000fee1-0000-1000-8000-00805f9b34fb";   // {steps, distance, calories} as uint24 LE
    static final String DATA_OUT = "0000fee2-0000-1000-8000-00805f9b34fb";
    static final String DATA_IN = "0000fee3-0000-1000-8000-00805f9b34fb";
    static final String BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb";
    static final String BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb";

    static final int CMD_SET_WEATHER_FUTURE = 66;
    static final int CMD_SET_WEATHER_TODAY = 67;
    static final int CMD_SEND_MESSAGE = 65;
    static final int CMD_SYNC_TIME = 49;
    static final int CMD_SYNC_SLEEP = 50;
    static final int CMD_SYNC_PAST_SLEEP_AND_STEP = 51;
    static final int CMD_FIND_MY_WATCH = 97;
    static final int CMD_NOTIFY_WEATHER_CHANGE = 100;       // watch asks for the weather again
    static final int CMD_NOTIFY_PHONE_OPERATION = 103;      // watch music / call buttons
    static final int CMD_TRIGGER_MEASURE_HEARTRATE = 109;

    // CMD_SYNC_PAST_SLEEP_AND_STEP arguments. Gadgetbridge found the official names swapped:
    // "yesterday" (1, 3) really returns the day before yesterday, and vice versa.
    static final int PAST_STEPS_2_DAYS_AGO = 1;
    static final int PAST_STEPS_1_DAY_AGO = 2;
    static final int PAST_SLEEP_2_DAYS_AGO = 3;
    static final int PAST_SLEEP_1_DAY_AGO = 4;

    static final int OP_PLAY_PAUSE = 0, OP_PREVIOUS = 1, OP_NEXT = 2, OP_REJECT_CALL = 3;

    static final int WEATHER_CLOUDY = 0, WEATHER_FOGGY = 1, WEATHER_OVERCAST = 2, WEATHER_RAINY = 3,
        WEATHER_SNOWY = 4, WEATHER_SUNNY = 5, WEATHER_WINDY = 6, WEATHER_HAZE = 7;

    static final int MESSAGE_OTHER = 11;

    static byte[] packet(int command, byte[] payload) {
        int length = payload.length + 5;
        byte[] p = new byte[length];
        p[0] = (byte) 0xFE;
        p[1] = (byte) 0xEA;
        p[2] = (byte) (32 + ((length >> 8) & 0xFF));
        p[3] = (byte) (length & 0xFF);
        p[4] = (byte) command;
        System.arraycopy(payload, 0, p, 5, payload.length);
        return p;
    }

    /** Splits a packet into writes of at most {@code size} bytes. */
    static List<byte[]> fragments(byte[] packet, int size) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < packet.length; i += size) {
            byte[] f = new byte[Math.min(size, packet.length - i)];
            System.arraycopy(packet, i, f, 0, f.length);
            out.add(f);
        }
        return out;
    }

    /** A decoded fee3 packet. */
    static final class Packet {
        final int command;
        final byte[] payload;
        Packet(int command, byte[] payload) { this.command = command; this.payload = payload; }
    }

    /** Rebuilds packets that the watch splits across several notifications. */
    static final class Reassembler {
        private byte[] buf;
        private int pos;

        /** Returns the finished packet, or null if more fragments are needed (or the data was junk). */
        Packet add(byte[] fragment) {
            if (buf == null) {
                int len = length(fragment);
                if (len < 5) return null;
                buf = new byte[len];
                pos = 0;
            }
            int n = Math.min(fragment.length, buf.length - pos);
            System.arraycopy(fragment, 0, buf, pos, n);
            pos += n;
            if (pos < buf.length) return null;
            byte[] done = buf;
            buf = null;
            byte[] payload = new byte[done.length - 5];
            System.arraycopy(done, 5, payload, 0, payload.length);
            return new Packet(done[4] & 0xFF, payload);
        }

        private static int length(byte[] f) {
            if (f.length < 5 || f[0] != (byte) 0xFE || f[1] != (byte) 0xEA) return -1;
            int hi = 0;
            if (f[2] != 16) {
                if ((f[2] & 0xFF) < 32) return -1;
                hi = (f[2] & 0xFF) - 32;
            }
            return (hi << 8) | (f[3] & 0xFF);
        }
    }

    static int uint24le(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8) | ((b[offset + 2] & 0xFF) << 16);
    }

    /** fee1 / past-steps payload: {steps, distance (m), calories (kcal)}, each uint24 little-endian. */
    static int[] steps(byte[] b) {
        if (b == null || b.length < 9) return null;
        return new int[] { uint24le(b, 0), uint24le(b, 3), uint24le(b, 6) };
    }

    /**
     * The watch keeps time as if it were in GMT+8: it takes the local wall-clock time and stores
     * it as a GMT+8 timestamp. So shift "now" by the difference between local time and GMT+8.
     */
    static byte[] syncTime(long nowMillis, TimeZone local) {
        long watch = (nowMillis + local.getOffset(nowMillis) - 8L * 3600_000L) / 1000L;
        int t = (int) watch;
        return packet(CMD_SYNC_TIME, new byte[] { (byte) (t >> 24), (byte) (t >> 16), (byte) (t >> 8), (byte) t, 8 });
    }

    static byte[] heartRate(boolean start) {
        return packet(CMD_TRIGGER_MEASURE_HEARTRATE, new byte[] { start ? (byte) 0 : (byte) -1 });
    }

    /** Shows "sender: text" on the watch like a phone notification. */
    static byte[] message(int type, String sender, String text) {
        byte[] s = (sender + ":" + text).getBytes(StandardCharsets.UTF_8);
        byte[] cut = new byte[Math.min(s.length, 180)];
        System.arraycopy(s, 0, cut, 0, cut.length);
        byte[] payload = new byte[cut.length + 1];
        payload[0] = (byte) type;
        System.arraycopy(cut, 0, payload, 1, cut.length);
        return packet(CMD_SEND_MESSAGE, payload);
    }

    /** Today's weather: {has_pm25=0, condition, temp °C, festival[4 UTF-16BE chars], city[4 UTF-16BE chars]}. */
    static byte[] weatherToday(int condition, int tempC, String city) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0);
        b.write(condition);
        b.write((byte) clampTemp(tempC));
        b.write(utf16Fixed("", 4), 0, 8);
        b.write(utf16Fixed(city == null ? "" : city, 4), 0, 8);
        return packet(CMD_SET_WEATHER_TODAY, b.toByteArray());
    }

    /** Seven days of {condition, min °C, max °C}; missing days are sent as haze / -100 like Da Fit does. */
    static byte[] weatherForecast(int[][] days) {
        byte[] p = new byte[21];
        for (int i = 0; i < 7; i++) {
            if (days != null && i < days.length && days[i] != null) {
                p[i * 3] = (byte) days[i][0];
                p[i * 3 + 1] = (byte) clampTemp(days[i][1]);
                p[i * 3 + 2] = (byte) clampTemp(days[i][2]);
            } else {
                p[i * 3] = WEATHER_HAZE;
                p[i * 3 + 1] = -100;
                p[i * 3 + 2] = -100;
            }
        }
        return packet(CMD_SET_WEATHER_FUTURE, p);
    }

    /** Maps a WMO weather code (as used by Open-Meteo) to the watch's 8 weather icons. */
    static int conditionFromWmo(int code) {
        if (code <= 1) return WEATHER_SUNNY;
        if (code == 2) return WEATHER_OVERCAST;     // partly cloudy
        if (code == 3) return WEATHER_CLOUDY;
        if (code == 45 || code == 48) return WEATHER_FOGGY;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return WEATHER_SNOWY;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) return WEATHER_RAINY;
        return WEATHER_HAZE;
    }

    private static int clampTemp(int t) { return Math.max(-99, Math.min(99, t)); }

    private static byte[] utf16Fixed(String s, int chars) {
        StringBuilder sb = new StringBuilder(s.length() > chars ? s.substring(0, chars) : s);
        while (sb.length() < chars) sb.append(' ');
        return sb.toString().getBytes(StandardCharsets.UTF_16BE);
    }
}
