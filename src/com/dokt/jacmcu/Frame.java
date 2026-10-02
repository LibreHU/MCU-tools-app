package com.dokt.jacmcu;

import java.util.ArrayList;
import java.util.List;

/** Trames MCU Jancar : EE FA LEN CMD D0..Dn-1 CS (LEN = n + 1, CS = somme 8 bits). Voir docs/mcu_firmware.md §6. */
public final class Frame {
    private Frame() {}

    public static final int MAX_DATA = 126;           // Android plante sur LEN >= 0x80

    public static byte[] build(int cmd, byte[] data) {
        if (data == null) data = new byte[0];
        if (data.length > MAX_DATA) throw new IllegalArgumentException("plus de " + MAX_DATA + " octets");
        byte[] f = new byte[data.length + 5];
        f[0] = (byte) 0xEE; f[1] = (byte) 0xFA; f[2] = (byte) (data.length + 1); f[3] = (byte) cmd;
        System.arraycopy(data, 0, f, 4, data.length);
        int s = 0;
        for (int i = 0; i < f.length - 1; i++) s += f[i] & 0xFF;
        f[f.length - 1] = (byte) s;
        return f;
    }

    public static boolean checksumOk(byte[] f) {
        int s = 0;
        for (int i = 0; i < f.length - 1; i++) s += f[i] & 0xFF;
        return (s & 0xFF) == (f[f.length - 1] & 0xFF);
    }

    public static int cmd(byte[] f) { return f[3] & 0xFF; }

    public static byte[] data(byte[] f) {
        byte[] d = new byte[Math.max(0, f.length - 5)];
        System.arraycopy(f, 4, d, 0, d.length);
        return d;
    }

    private static final char[] HX = "0123456789ABCDEF".toCharArray();

    public static String hex(byte[] b) { return b == null ? "" : hex(b, 0, b.length); }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            int v = b[off + i] & 0xFF;
            sb.append(HX[v >> 4]).append(HX[v & 15]);
        }
        return sb.toString();
    }

    /** "F0 0B 00", "f00b00", "0xF0,0x0B" -> octets. Renvoie null si invalide. */
    public static byte[] parseHex(String s) {
        if (s == null) return null;
        String t = s.replace(",", " ").replace(":", " ").replace("|", " ").replace("[", " ").replace("]", " ").trim();
        if (t.isEmpty()) return new byte[0];
        ArrayList<Byte> out = new ArrayList<Byte>();
        for (String tok : t.split("\\s+")) {
            if (tok.startsWith("0x") || tok.startsWith("0X")) tok = tok.substring(2);
            if (tok.isEmpty()) continue;
            if ((tok.length() & 1) == 1) tok = "0" + tok;
            for (int i = 0; i < tok.length(); i += 2) {
                int hi = Character.digit(tok.charAt(i), 16), lo = Character.digit(tok.charAt(i + 1), 16);
                if (hi < 0 || lo < 0) return null;
                out.add((byte) (hi * 16 + lo));
            }
        }
        byte[] r = new byte[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Decoupe un flux d'octets en trames (tampon interne). */
    public static final class Parser {
        private byte[] buf = new byte[0];
        public final List<byte[]> junk = new ArrayList<byte[]>();

        public List<byte[]> feed(byte[] b, int n) {
            byte[] nb = new byte[buf.length + n];
            System.arraycopy(buf, 0, nb, 0, buf.length);
            System.arraycopy(b, 0, nb, buf.length, n);
            buf = nb;
            List<byte[]> frames = new ArrayList<byte[]>();
            junk.clear();
            int i = 0, j0 = -1;
            while (i < buf.length) {
                boolean head = (buf[i] & 0xFF) == 0xEE && (i + 1 >= buf.length || (buf[i + 1] & 0xFF) == 0xFA);
                if (!head) { if (j0 < 0) j0 = i; i++; continue; }
                if (i + 3 > buf.length) break;
                int ln = buf[i + 2] & 0xFF;
                if (ln == 0 || ln > 131) { if (j0 < 0) j0 = i; i++; continue; }
                int end = i + 3 + ln + 1;
                if (end > buf.length) break;
                if (j0 >= 0) { junk.add(slice(buf, j0, i)); j0 = -1; }
                frames.add(slice(buf, i, end));
                i = end;
            }
            if (j0 >= 0) { junk.add(slice(buf, j0, i)); }
            buf = slice(buf, i, buf.length);
            if (buf.length > 512) buf = new byte[0];
            return frames;
        }
    }

    static byte[] slice(byte[] b, int a, int e) {
        byte[] r = new byte[e - a];
        System.arraycopy(b, a, r, 0, r.length);
        return r;
    }

    // ---------------------------------------------------------------- noms
    public static String nameTx(int c) {
        switch (c) {
            case 0x01: return "EXTINCTION";
            case 0x08: return "MUTE";
            case 0x09: return "REGLE_RTC";
            case 0x0E: return "RESET_SOC";
            case 0x0F: return "CONFIG";
            case 0x10: return "CAN_TX";
            case 0x11: return "APPR_VOLANT";
            case 0x1F: return "PC_READY";
            case 0x21: return "APPR_FACADE";
            case 0x31: return "IR (ignore)";
            case 0x33: return "ECHELLE_VOLANT (ignore)";
            case 0x43: return "ANTENNE";
            case 0x44: return "REM_AMPLI";
            case 0x45: return "ROTATION (ignore)";
            case 0x80: return "BOOTLOADER";
            case 0xF0: return "REQUETE";
            case 0xF1: return "DUREE_VEILLE";
            default: return String.format("CMD_%02X", c);
        }
    }

    public static String nameRx(int c) {
        switch (c) {
            case 0x00: return "ACC";
            case 0x04: return "FREIN_MAIN";
            case 0x08: return "MUTE";
            case 0x09: return "RTC";
            case 0x0A: return "VERSION";
            case 0x0B: return "FEUX";
            case 0x0D: return "RETROECL";
            case 0x0F: return "CONFIG";
            case 0x10: return "CAN_RX";
            case 0x20: return "TOUCHE";
            case 0x30: return "TOUCHE_APPR";
            case 0x43: return "ANTENNE";
            case 0xC0: return "ACK";
            default: return String.format("CMD_%02X", c);
        }
    }

    static final String[] QUERY = new String[256];
    static final String[] CFG = new String[16];
    static final int[] BAUDS = {9600, 19200, 38400, 57600, 115200, 230400, 460800};
    static final String[] HIGH_V = {"16", "16,5", "17", "18", "19", "20"};
    static final String[] LOW_V = {"9", "9,5", "10"};
    static {
        QUERY[0x00] = "ACC"; QUERY[0x04] = "frein a main"; QUERY[0x08] = "mute"; QUERY[0x09] = "date+heure";
        QUERY[0x0A] = "version"; QUERY[0x0B] = "feux/ILL"; QUERY[0x0D] = "retroeclairage"; QUERY[0x0F] = "option +0x11";
        QUERY[0x43] = "antenne radio";
        CFG[0x00] = "baud USART1"; CFG[0x02] = "PWM CH1 %"; CFG[0x03] = "PWM CH2 %"; CFG[0x04] = "LED facade";
        CFG[0x06] = "molette"; CFG[0x07] = "option +0x11"; CFG[0x08] = "type facade"; CFG[0x0A] = "seuil haut";
        CFG[0x0B] = "seuil bas";
    }

    /** Description lisible d'une trame. fromMcu = sens MCU -> SoC. */
    public static String describe(byte[] f, boolean fromMcu) {
        int c = cmd(f);
        byte[] d = data(f);
        String name = fromMcu ? nameRx(c) : nameTx(c);
        String info = "";
        if (!fromMcu && c == 0xF0 && d.length > 0) {
            String q = QUERY[d[0] & 0xFF];
            info = "? " + (q != null ? q : String.format("%02X", d[0] & 0xFF));
        } else if (!fromMcu && c == 0x0F && d.length > 0) {
            int sub = d[0] & 0xFF;
            String n = sub < CFG.length ? CFG[sub] : null;
            info = n != null ? n : String.format("sous-cmd %02X (ignoree)", sub);
            int a = d.length > 1 ? d[1] & 0xFF : -1;
            if (sub == 0x00 && a >= 0 && a < BAUDS.length) info += " = " + BAUDS[a];
            else if (sub == 0x0A && a >= 0 && a < HIGH_V.length) info += " = " + HIGH_V[a] + " V";
            else if (sub == 0x0B && a >= 0 && a < LOW_V.length) info += " = " + LOW_V[a] + " V";
            else if (sub == 0x04 && d.length >= 6) info += String.format(" type=%d R=%d G=%d B=%d mode=%d", d[1], d[2], d[3], d[4], d[5]);
            else if (a >= 0) info += " = " + a;
        } else if (c == 0x0A && fromMcu && d.length > 2) {
            info = "'" + ascii(d) + "'";
        } else if (c == 0x09 && d.length > 0) {
            if ((d[0] & 0xFF) == 0 && d.length >= 5) info = String.format("date %02d%02d-%02d-%02d", d[1], d[2], d[3], d[4]);
            else if ((d[0] & 0xFF) == 1 && d.length >= 4) info = String.format("heure %02d:%02d:%02d", d[1], d[2], d[3]);
        } else if ((c == 0x20 || c == 0x30) && fromMcu && d.length >= 5) {
            info = (d[2] & 0xFF) == 0xFF && (d[3] & 0xFF) == 0xFF && (d[4] & 0xFF) == 0xFF ? "relache"
                    : String.format("canal %d  adc %02X %02X %02X", d[0] & 0xFF, d[2] & 0xFF, d[3] & 0xFF, d[4] & 0xFF);
        } else if (c == 0xC0 && fromMcu && d.length > 0) {
            info = String.format("de %02X", d[0] & 0xFF);
        } else if (c == 0x10) {
            info = d.length + " octets boitier CAN";
        } else if (c == 0xF1 && !fromMcu && d.length > 0) {
            info = (d[0] & 0xFF) + " x 420 min";
        } else if ((c == 0x11 || c == 0x21) && !fromMcu && d.length > 0) {
            info = (d[0] & 0xFF) == 2 ? "debut" : "fin";
        } else if (d.length == 1) {
            info = String.valueOf(d[0] & 0xFF);
        } else if (d.length > 0) {
            info = hex(d);
        }
        return name + "  " + info;
    }

    public static String ascii(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) { int v = b & 0xFF; sb.append(v >= 32 && v < 127 ? (char) v : '.'); }
        return sb.toString().trim();
    }

    /** Commandes qui coupent / redemarrent / basculent le MCU : demandent confirmation. 0x80 est refuse. */
    public static String danger(int c) {
        switch (c) {
            case 0x01: return "extinction differee de l'autoradio";
            case 0x0E: return "reset du SoC (redemarrage brutal d'Android)";
            case 0xF1: return "duree de veille (n x 420 min)";
            default: return null;
        }
    }

    public static boolean forbidden(int c) { return c == 0x80; }
}
