package com.dokt.jacmcu;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Mode root : ecriture directe sur /dev/ttyS1 par le binaire jacbridge (lib/arm64-v8a/libjacbridge.so) lance via su.
 * ivi-services garde le port : chaque trame part en UN seul write() (pas d'entrelacement), le port n'est pas reconfigure,
 * l'ACK du MCU est lu par ivi-services. Lecture :
 *   - par defaut passive, via le journal d'ivi-services (logcat JLOG, reglage global_mcudatadebug) ;
 *   - option "lecture directe" : jacbridge lit aussi le port -> les octets lus manquent a ivi-services (ACC, feux, ...).
 */
public final class RootBackend implements Backend {
    public static final String TTY = "/dev/ttyS1";
    private final Context ctx;
    private final boolean directRead;
    private Listener l;
    private Process bridge;
    private OutputStream bridgeIn;
    private LogSniffer sniffer;
    private volatile boolean run;
    private final Frame.Parser parser = new Frame.Parser();

    public RootBackend(Context ctx, boolean directRead) { this.ctx = ctx.getApplicationContext(); this.directRead = directRead; }

    public String name() { return directRead ? "Root (lecture directe)" : "Root"; }

    public static String bridgePath(Context c) { return c.getApplicationInfo().nativeLibraryDir + "/libjacbridge.so"; }

    /** Execute une commande en root et renvoie sa sortie (stdout+stderr), ou null si su est indisponible. */
    public static String su(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = r.readLine()) != null) sb.append(s).append('\n');
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    public void start(Listener listener) {
        l = listener;
        run = true;
        new Thread(new Runnable() { public void run() { open(); } }, "jacmcu-root").start();
    }

    private void open() {
        String id = su("id");
        if (id == null || !id.contains("uid=0")) {
            l.onStatus("su indisponible ou refuse" + (id != null ? " (" + id + ")" : ""), false);
            return;
        }
        String bin = bridgePath(ctx);
        if (!new File(bin).exists()) { l.onStatus("jacbridge absent : " + bin, false); return; }
        try {
            bridge = new ProcessBuilder("su", "-c", bin + (directRead ? " rw " : " w ") + TTY).redirectErrorStream(true).start();
            bridgeIn = bridge.getOutputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(bridge.getInputStream()));
            String s;
            while (run && (s = r.readLine()) != null) {
                if (s.startsWith("READY")) {
                    l.onStatus("root : " + TTY + (directRead ? " lecture+ecriture (exclusif)" : " ecriture"), true);
                    if (!directRead) { sniffer = new LogSniffer(l, true); sniffer.start(); }
                } else if (s.startsWith("R ")) {
                    byte[] b = Frame.parseHex(s.substring(2));
                    if (b == null) continue;
                    for (byte[] f : parser.feed(b, b.length)) l.onFrame(true, f, "port");
                    for (byte[] j : parser.junk) l.onLog("hors trame : " + Frame.hex(j));
                } else if (s.startsWith("E ")) {
                    l.onLog("jacbridge : erreur " + s.substring(2));
                    if (s.contains("open")) l.onStatus("ouverture de " + TTY + " impossible (" + s + ")", false);
                }
            }
        } catch (Exception e) {
            if (run) l.onStatus("jacbridge : " + e.getMessage(), false);
        }
        if (run) l.onStatus("jacbridge termine", false);
    }

    public synchronized boolean send(int cmd, byte[] data) {
        if (bridgeIn == null) { l.onLog("non envoye : pont root non pret"); return false; }
        byte[] f = Frame.build(cmd, data);
        try {
            bridgeIn.write((Frame.hex(f) + "\n").getBytes("US-ASCII"));
            bridgeIn.flush();
            l.onFrame(false, f, "app");
            return true;
        } catch (Exception e) {
            l.onLog("ecriture : " + e.getMessage());
            return false;
        }
    }

    public void enableIviLog(final boolean on) {
        new Thread(new Runnable() {
            public void run() {
                String uri = "content://com.jancar.settings.provider/settings";
                String q = su("content query --uri " + uri + " --where \"name='" + ServiceBackend.DEBUG_KEY + "'\"");
                String v = on ? "true" : "false";
                String r;
                if (q != null && q.contains("name=")) {
                    r = su("content update --uri " + uri + " --bind value:s:" + v + " --where \"name='" + ServiceBackend.DEBUG_KEY + "'\"");
                } else {
                    r = su("content insert --uri " + uri + " --bind name:s:" + ServiceBackend.DEBUG_KEY + " --bind value:s:" + v);
                }
                l.onLog("journal ivi-services " + (on ? "active" : "desactive") + (r != null && !r.isEmpty() ? " : " + r : ""));
            }
        }).start();
    }

    public void stop() {
        run = false;
        if (sniffer != null) sniffer.stop();
        try { if (bridgeIn != null) bridgeIn.close(); } catch (Exception ignored) { }
        if (bridge != null) bridge.destroy();
        bridgeIn = null;
    }
}
