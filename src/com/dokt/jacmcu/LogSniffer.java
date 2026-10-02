package com.dokt.jacmcu;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Lecture passive du trafic MCU sans toucher au port : ivi-services journalise (tag JLOG) quand le reglage
 * global_mcudatadebug vaut "true" :
 *   "... CmdId = 0x0b, Data = | 01 |"        trame recue du MCU (hors ACK)
 *   "... send: [ee fa 02 1f 01 0a ]"          trame envoyee au MCU
 * Lance "logcat" directement (permission READ_LOGS accordee par adb) ou via su.
 */
public final class LogSniffer {
    private final Backend.Listener l;
    private final boolean useSu;
    private Process proc;
    private volatile boolean run;

    public LogSniffer(Backend.Listener l, boolean useSu) { this.l = l; this.useSu = useSu; }

    public void start() {
        run = true;
        Thread t = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "jacmcu-logcat");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        run = false;
        if (proc != null) proc.destroy();
    }

    private void loop() {
        String cmd = "logcat -v brief -T 1 JLOG:D *:S";
        try {
            proc = useSu ? new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                         : new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            l.onLog("journal ivi-services : lecture " + (useSu ? "(su)" : "(READ_LOGS)"));
            BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while (run && (line = r.readLine()) != null) parse(line);
        } catch (Exception e) {
            if (run) l.onLog("journal ivi-services : " + e.getMessage());
        }
    }

    void parse(String line) {
        int i = line.indexOf("CmdId = 0x");
        if (i >= 0) {
            int j = line.indexOf("Data = ", i);
            try {
                int cmd = Integer.parseInt(line.substring(i + 10, i + 12), 16);
                byte[] d = j >= 0 ? Frame.parseHex(line.substring(j + 7)) : new byte[0];
                if (d != null && d.length <= Frame.MAX_DATA) l.onFrame(true, Frame.build(cmd, d), "ivi");
            } catch (RuntimeException ignored) { }
            return;
        }
        i = line.indexOf("send: [");
        if (i >= 0) {
            int j = line.indexOf(']', i);
            byte[] f = Frame.parseHex(line.substring(i + 7, j > 0 ? j : line.length()));
            if (f != null && f.length >= 5) l.onFrame(false, f, "ivi");
            return;
        }
        if (line.contains("not allow send")) l.onLog("ivi-services : " + line.substring(Math.max(0, line.indexOf("id ="))));
    }
}
