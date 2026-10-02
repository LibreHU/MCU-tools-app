package com.dokt.jacmcu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity implements Backend.Listener {
    static final int BG = 0xFF0E1216, CARD = 0xFF1A2027, CARD_HI = 0xFF232C36, ACCENT = 0xFF4DA3FF,
            OK = 0xFF46D39A, BAD = 0xFFF2645A, WARN = 0xFFF0A92B, TXT = 0xFFEAF0F6, DIM = 0xFF8A98A6,
            SIM = 0xFFB98AF0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Backend backend;
    private TextView status, logView, preview, infoView;
    private ScrollView logScroll;
    private final View[] pages = new View[5];
    private final Button[] tabs = new Button[5];
    private Button modeService, modeRoot;
    private CheckBox direct, hideAck, pause;
    private final Map<String, TextView> tiles = new LinkedHashMap<String, TextView>();
    private final Map<String, String> state = new LinkedHashMap<String, String>();
    private final ArrayDeque<String> log = new ArrayDeque<String>();
    private boolean logDirty;
    private int frames;
    private final SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE);

    // ================================================================== cycle de vie
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("jacmcu", MODE_PRIVATE);
        setContentView(buildUi());
        selectTab(prefs.getInt("tab", 0));
        startBackend(prefs.getString("mode", "service"));
        ui.post(flusher);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(flusher);
        if (backend != null) backend.stop();
        super.onDestroy();
    }

    private void startBackend(String mode) {
        if (backend != null) backend.stop();
        prefs.edit().putString("mode", mode).apply();
        boolean root = "root".equals(mode);
        backend = root ? new RootBackend(this, direct.isChecked()) : new ServiceBackend(this);
        modeService.setBackgroundDrawable(pill(root ? CARD : ACCENT));
        modeRoot.setBackgroundDrawable(pill(root ? ACCENT : CARD));
        direct.setEnabled(root);
        onStatus("demarrage " + backend.name() + "...", false);
        addLog("== mode " + backend.name());
        backend.start(this);
        refreshInfo();
    }

    // ================================================================== Backend.Listener
    public void onFrame(final boolean fromMcu, final byte[] f, final String src) {
        ui.post(new Runnable() {
            public void run() {
                frames++;
                setState("frames", String.valueOf(frames));
                if (fromMcu) decode(f);
                if (hideAck.isChecked() && fromMcu && Frame.cmd(f) == 0xC0) return;
                String bad = Frame.checksumOk(f) ? "" : "  [CS KO]";
                addLog((fromMcu ? "< " : "> ") + pad(src, 7) + pad(Frame.hex(f), 44) + " " + Frame.describe(f, fromMcu) + bad);
            }
        });
    }

    public void onState(final String key, final String value) {
        ui.post(new Runnable() { public void run() { setState(key, value); } });
    }

    public void onLog(final String text) {
        ui.post(new Runnable() { public void run() { addLog("# " + text); } });
    }

    public void onStatus(final String text, final boolean ok) {
        ui.post(new Runnable() {
            public void run() {
                status.setText((ok ? "● " : "○ ") + text);
                status.setTextColor(ok ? OK : WARN);
                addLog("# " + text);
            }
        });
    }

    /** Etat a partir des trames MCU -> SoC. */
    private void decode(byte[] f) {
        int c = Frame.cmd(f);
        byte[] d = Frame.data(f);
        int v = d.length > 0 ? d[0] & 0xFF : -1;
        switch (c) {
            case 0x00: setState("acc", v == 1 ? "ON" : "OFF"); break;
            case 0x04: setState("hb", v == 1 ? "serre" : "desserre"); break;
            case 0x08: setState("mute", v == 1 ? "coupe" : "actif"); break;
            case 0x0B: setState("ill", v == 1 ? "ON" : "OFF"); break;
            case 0x0D: setState("bl", String.valueOf(v)); break;
            case 0x43: setState("radio", v == 1 ? "ON" : "OFF"); break;
            case 0x0A: setState("version", Frame.ascii(d)); break;
            case 0x09:
                if (v == 0 && d.length >= 5) setState("date", String.format(Locale.ROOT, "%02d%02d-%02d-%02d", d[1], d[2], d[3], d[4]));
                if (v == 1 && d.length >= 4) setState("time", String.format(Locale.ROOT, "%02d:%02d:%02d", d[1], d[2], d[3]));
                break;
            case 0x0F: if (v == 7 && d.length >= 2) setState("opt", String.valueOf(d[1] & 0xFF)); break;
            case 0x20: case 0x30:
                if (d.length >= 5) setState("key", (c == 0x30 ? "[appr] " : "") + Frame.describe(f, true).replaceFirst("^\\S+\\s+", ""));
                break;
            default: break;
        }
    }

    private void setState(String k, String v) {
        state.put(k, v);
        TextView t = tiles.get(k);
        if (t != null) { t.setText(v); t.setTextColor(tileColor(k, v)); }
    }

    /** Couleur d'une valeur d'etat : vert = actif/present, rouge = coupe/perdu, ambre = attention. */
    private int tileColor(String k, String v) {
        if (v == null || v.equals("--")) return TXT;
        if (("acc".equals(k) || "radio".equals(k)) && v.equals("ON")) return OK;
        if (("acc".equals(k) || "radio".equals(k)) && v.equals("OFF")) return DIM;
        if ("ill".equals(k)) return v.equals("ON") ? WARN : DIM;
        if ("hb".equals(k)) return v.equals("serre") ? OK : WARN;
        if ("mute".equals(k)) return v.equals("coupe") ? BAD : OK;
        if ("key".equals(k)) return ACCENT;
        return TXT;
    }

    // ================================================================== envoi
    private void send(final int cmd, final int... data) {
        byte[] b = new byte[data.length];
        for (int i = 0; i < data.length; i++) b[i] = (byte) data[i];
        sendBytes(cmd, b);
    }

    private void sendBytes(final int cmd, final byte[] data) {
        if (Frame.forbidden(cmd)) {
            toast(String.format("%02X (bootloader) bloque dans l'app : risque de MCU inutilisable", cmd));
            return;
        }
        if (data.length > Frame.MAX_DATA) { toast("trop de donnees (max " + Frame.MAX_DATA + ")"); return; }
        String danger = Frame.danger(cmd);
        if (danger == null) { doSend(cmd, data); return; }
        new AlertDialog.Builder(this)
                .setTitle(String.format("Commande %02X : %s", cmd, danger))
                .setMessage("Cette commande peut eteindre ou redemarrer l'autoradio (eventuellement en roulant). Envoyer quand meme ?\n\n"
                        + Frame.hex(Frame.build(cmd, data)))
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Envoyer", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doSend(cmd, data); }
                }).show();
    }

    private void doSend(final int cmd, final byte[] data) {
        final Backend be = backend;
        new Thread(new Runnable() { public void run() { be.send(cmd, data); } }).start();
    }

    private void queryAll() {
        int[] q = {0x00, 0x04, 0x08, 0x0B, 0x0D, 0x43, 0x0A, 0x09};
        for (int x : q) send(0xF0, x, 0);
        send(0xF0, 0x0F, 0x07);
    }

    private void syncTime() {
        Calendar c = Calendar.getInstance();
        int y = c.get(Calendar.YEAR);
        send(0x09, 0, y / 100, y % 100, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        send(0x09, 1, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    // ================================================================== journal
    private void addLog(String s) {
        if (pause != null && pause.isChecked() && !s.startsWith("#") && !s.startsWith("==")) return;
        log.addLast(hms.format(new Date()) + "  " + s);
        while (log.size() > 800) log.removeFirst();
        logDirty = true;
    }

    private final Runnable flusher = new Runnable() {
        public void run() {
            if (logDirty && logView != null) {
                logDirty = false;
                StringBuilder sb = new StringBuilder();
                for (String s : log) sb.append(s).append('\n');
                logView.setText(sb);
                logScroll.post(new Runnable() { public void run() { logScroll.fullScroll(View.FOCUS_DOWN); } });
            }
            ui.postDelayed(this, 250);
        }
    };

    private void exportLog() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File f = new File(dir, "jacmcu-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".log");
            FileOutputStream o = new FileOutputStream(f);
            for (String s : log) o.write((s + "\n").getBytes("UTF-8"));
            o.close();
            toast("journal : " + f.getAbsolutePath());
            addLog("# exporte : " + f.getAbsolutePath());
        } catch (Exception e) {
            toast("export impossible : " + e.getMessage());
        }
    }

    // ================================================================== interface
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout top = hrow();
        top.setBackgroundDrawable(round(CARD, 14));
        top.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout brand = vcol();
        TextView title = text("MCU Toolkit", 22, TXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        brand.addView(title);
        brand.addView(text("MCU Jancar / AC8257", 11, DIM));
        top.addView(brand, new LinearLayout.LayoutParams(-2, -2));
        modeService = button("Service Jancar", new View.OnClickListener() { public void onClick(View v) { startBackend("service"); } });
        modeRoot = button("Root", new View.OnClickListener() { public void onClick(View v) { startBackend("root"); } });
        top.addView(space(16)); top.addView(modeService); top.addView(modeRoot);
        direct = check("lecture directe du port", prefs.getBoolean("direct", false));
        direct.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("direct", on).apply();
                if (on) toast("Lecture directe : ivi-services ne recoit plus les trames lues (ACC, feux...). A n'utiliser que pour un test court.");
                if (backend instanceof RootBackend) startBackend("root");
            }
        });
        top.addView(direct);
        status = text("", 14, WARN);
        status.setPadding(dp(16), 0, 0, 0);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        LinearLayout tabRow = hrow();
        String[] names = {"Etat", "Commandes", "Console", "Simulation", "Infos"};
        for (int i = 0; i < tabs.length; i++) {
            final int k = i;
            tabs[i] = button(names[i], new View.OnClickListener() { public void onClick(View v) { selectTab(k); } });
            tabRow.addView(tabs[i], new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(tabRow);

        FrameLayout content = new FrameLayout(this);
        pages[0] = pageState(); pages[1] = pageCommands(); pages[2] = pageConsole(); pages[3] = pageSimu(); pages[4] = pageInfo();
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void selectTab(int k) {
        prefs.edit().putInt("tab", k).apply();
        for (int i = 0; i < tabs.length; i++) {
            boolean sel = i == k;
            pages[i].setVisibility(sel ? View.VISIBLE : View.GONE);
            tabs[i].setBackgroundDrawable(pill(sel ? ACCENT : CARD));
            tabs[i].setTextColor(sel ? 0xFF0E1216 : TXT);
            tabs[i].setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (k == tabs.length - 1) refreshInfo();
    }

    private View pageState() {
        LinearLayout p = vcol();
        GridLayout g = new GridLayout(this);
        g.setColumnCount(4);
        String[][] t = {{"version", "Version MCU"}, {"acc", "ACC"}, {"hb", "Frein a main"}, {"ill", "Feux (ILL)"},
                {"mute", "Mute"}, {"bl", "Retroeclairage"}, {"radio", "Antenne radio"}, {"opt", "Option +0x11"},
                {"date", "Date MCU"}, {"time", "Heure MCU"}, {"key", "Derniere touche"}, {"power", "Alim. (service)"},
                {"carid", "Car ID"}, {"canver", "Version CAN"}, {"frames", "Trames vues"}};
        for (String[] e : t) {
            LinearLayout c = vcol();
            c.setBackgroundDrawable(round(CARD, 10));
            c.setPadding(dp(12), dp(8), dp(12), dp(8));
            c.addView(text(e[1], 13, DIM));
            TextView v = text("--", 19, TXT);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setSingleLine(false);
            c.addView(v);
            tiles.put(e[0], v);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, e[0].equals("version") || e[0].equals("key") ? 2 : 1, 1f));
            lp.width = 0;
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            g.addView(c, lp);
        }
        p.addView(g);
        LinearLayout r = hrow();
        r.addView(button("Interroger le MCU", new View.OnClickListener() { public void onClick(View v) { queryAll(); } }));
        r.addView(button("Relire le service", new View.OnClickListener() {
            public void onClick(View v) {
                if (backend instanceof ServiceBackend) new Thread(new Runnable() { public void run() { ((ServiceBackend) backend).refresh(); } }).start();
                else toast("mode service uniquement");
            }
        }));
        r.addView(button("Activer journal ivi-services", new View.OnClickListener() { public void onClick(View v) { backend.enableIviLog(true); } }));
        p.addView(r);
        p.addView(note("Les reponses aux requetes arrivent a ivi-services. Elles s'affichent ici via le journal d'ivi-services "
                + "(mode root, ou mode service avec READ_LOGS) ou via la lecture directe du port."));
        return scroll(p);
    }

    private View pageCommands() {
        LinearLayout p = vcol();
        p.addView(section("Sorties"));
        p.addView(row("Mute (08)", btn("Couper", 0x08, 1), btn("Retablir", 0x08, 0)));
        p.addView(row("Antenne radio (43)", btn("ON", 0x43, 1), btn("OFF", 0x43, 0)));
        p.addView(row("REM ampli (44)", btn("ON", 0x44, 1), btn("OFF", 0x44, 0)));

        p.addView(section("Luminosite / LED (0F)"));
        p.addView(slider("PWM CH1 % (0F 02)", 5, 100, 60, 0x02));
        p.addView(slider("PWM CH2 % (0F 03)", 5, 100, 50, 0x03));
        final SeekBar[] rgb = new SeekBar[3];
        LinearLayout led = hrow();
        led.addView(label("LED facade R/G/B"));
        for (int i = 0; i < 3; i++) { rgb[i] = seek(0, 99, 50); led.addView(rgb[i], new LinearLayout.LayoutParams(0, -2, 1)); }
        final int[] mode = {2};
        final Button mb = button("mode : manuel", null);
        mb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mode[0] = mode[0] % 3 + 1;
                mb.setText("mode : " + new String[]{"", "auto", "manuel", "semi-auto"}[mode[0]]);
            }
        });
        led.addView(mb);
        led.addView(button("Envoyer", new View.OnClickListener() {
            public void onClick(View v) { send(0x0F, 0x04, 1, rgb[0].getProgress(), rgb[1].getProgress(), rgb[2].getProgress(), mode[0]); }
        }));
        p.addView(led);

        p.addView(section("Protection batterie (0F 0A / 0F 0B)"));
        LinearLayout hi = hrow(); hi.addView(label("Seuil haut"));
        for (int i = 0; i < Frame.HIGH_V.length; i++) hi.addView(btn(Frame.HIGH_V[i] + " V", 0x0F, 0x0A, i));
        p.addView(hi);
        LinearLayout lo = hrow(); lo.addView(label("Seuil bas"));
        for (int i = 0; i < Frame.LOW_V.length; i++) lo.addView(btn(Frame.LOW_V[i] + " V", 0x0F, 0x0B, i));
        p.addView(lo);

        p.addView(section("Horloge / apprentissage"));
        p.addView(row("Heure MCU (09)", button("Envoyer l'heure Android", new View.OnClickListener() { public void onClick(View v) { syncTime(); } }),
                btn("Lire", 0xF0, 0x09, 0)));
        p.addView(row("Touches volant (11)", btn("Debut", 0x11, 2), btn("Fin", 0x11, 3)));
        p.addView(row("Facade / molette (21)", btn("Debut", 0x21, 2), btn("Fin", 0x21, 3)));
        p.addView(row("Touches brutes (service)", button("ON", new View.OnClickListener() { public void onClick(View v) { rawKeys(true); } }),
                button("OFF", new View.OnClickListener() { public void onClick(View v) { rawKeys(false); } })));

        p.addView(section("Boitier CAN (0F 00, USART1)"));
        LinearLayout baud = hrow();
        for (int i = 0; i < Frame.BAUDS.length; i++) baud.addView(btn(String.valueOf(Frame.BAUDS[i]), 0x0F, 0x00, i));
        p.addView(hscroll(baud));
        p.addView(note("01 (extinction), 0E (reset SoC) et F1 (veille) passent par la console avec confirmation. "
                + "80 (bootloader) est bloque. Les reglages 0F sont enregistres en flash par le MCU 3 s apres."));
        return scroll(p);
    }

    private void rawKeys(final boolean on) {
        if (!(backend instanceof ServiceBackend)) { toast("mode service uniquement"); return; }
        new Thread(new Runnable() { public void run() { ((ServiceBackend) backend).setRawKeys(on); } }).start();
    }

    private View pageConsole() {
        LinearLayout p = vcol();
        LinearLayout r = hrow();
        final EditText in = new EditText(this);
        in.setHint("CMD donnees... ex : F0 0A 00");
        in.setTextColor(TXT); in.setHintTextColor(DIM);
        in.setTypeface(Typeface.MONOSPACE);
        in.setSingleLine(true);
        in.setText(prefs.getString("last", "F0 0A 00"));
        r.addView(in, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(button("Envoyer", new View.OnClickListener() {
            public void onClick(View v) {
                byte[] b = Frame.parseHex(in.getText().toString());
                if (b == null || b.length == 0) { toast("hexa invalide"); return; }
                prefs.edit().putString("last", in.getText().toString()).apply();
                sendBytes(b[0] & 0xFF, Frame.slice(b, 1, b.length));
            }
        }));
        p.addView(r);
        preview = text("", 13, DIM);
        preview.setTypeface(Typeface.MONOSPACE);
        p.addView(preview);
        in.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) { updatePreview(e.toString()); }
        });
        updatePreview(in.getText().toString());

        LinearLayout q = hrow();
        String[][] qs = {{"ACC", "00"}, {"Frein", "04"}, {"Mute", "08"}, {"Heure", "09"}, {"Version", "0A"}, {"Feux", "0B"},
                {"Retroecl.", "0D"}, {"Antenne", "43"}};
        for (final String[] e : qs) {
            q.addView(button(e[0] + " ?", new View.OnClickListener() { public void onClick(View v) { send(0xF0, Integer.parseInt(e[1], 16), 0); } }));
        }
        q.addView(button("Option ?", new View.OnClickListener() { public void onClick(View v) { send(0xF0, 0x0F, 0x07); } }));
        p.addView(hscroll(q));

        LinearLayout o = hrow();
        hideAck = check("masquer ACK", prefs.getBoolean("hideack", false));
        hideAck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { prefs.edit().putBoolean("hideack", on).apply(); }
        });
        pause = check("pause", false);
        o.addView(hideAck); o.addView(pause);
        o.addView(button("Effacer", new View.OnClickListener() { public void onClick(View v) { log.clear(); logDirty = true; } }));
        o.addView(button("Exporter", new View.OnClickListener() { public void onClick(View v) { exportLog(); } }));
        o.addView(button("Capture CAN ON/OFF", new View.OnClickListener() { public void onClick(View v) { canCapture(); } }));
        p.addView(o);

        logView = text("", 12, TXT);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(logView);
        logScroll = new ScrollView(this);
        logScroll.setBackgroundDrawable(round(0xFF0A0D10, 8));
        logScroll.setPadding(dp(8), dp(6), dp(8), dp(6));
        logScroll.addView(hs);
        p.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return p;
    }

    private void canCapture() {
        if (!(backend instanceof ServiceBackend)) { toast("mode service uniquement"); return; }
        final ServiceBackend s = (ServiceBackend) backend;
        if (s.isCanCapture()) { new Thread(new Runnable() { public void run() { s.setCanCapture(false); } }).start(); return; }
        new AlertDialog.Builder(this).setTitle("Capturer les trames CAN (0x10) ?")
                .setMessage("ivi-services n'accepte qu'un client : com.jancar.canservice (appli canbus) ne recevra plus rien "
                        + "jusqu'au prochain redemarrage d'ivi-services / de l'autoradio.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Capturer", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() { public void run() { s.setCanCapture(true); } }).start();
                    }
                }).show();
    }

    private void updatePreview(String s) {
        byte[] b = Frame.parseHex(s);
        if (b == null || b.length == 0) { preview.setText("trame : --"); return; }
        if (b.length - 1 > Frame.MAX_DATA) { preview.setText("trop long"); return; }
        byte[] f = Frame.build(b[0] & 0xFF, Frame.slice(b, 1, b.length));
        String w = Frame.forbidden(b[0] & 0xFF) ? "   BLOQUE" : Frame.danger(b[0] & 0xFF) != null ? "   confirmation demandee" : "";
        preview.setText("trame : " + Frame.hex(f) + "   " + Frame.describe(f, false) + w);
    }

    // ================================================================== simulation (local)
    /** Injecte une trame MCU -> SoC synthetique dans le decodeur et le journal de l'app. N'ecrit RIEN sur le port. */
    private void simulate(int cmd, int... data) {
        byte[] d = new byte[data.length];
        for (int i = 0; i < data.length; i++) d[i] = (byte) data[i];
        onFrame(true, Frame.build(cmd, d), "SIM");
    }

    private void simulateBytes(int cmd, byte[] d) { onFrame(true, Frame.build(cmd, d), "SIM"); }

    private View pageSimu() {
        LinearLayout p = vcol();
        p.addView(note("Simulation locale : les trames ci-dessous sont injectees dans le decodeur et le journal de "
                + "l'app comme si le MCU les avait envoyees. Rien n'est ecrit sur /dev/ttyS1 : c'est pour tester "
                + "l'interface et le decodage sans la voiture. Les vrais ACC / frein / feux sont lus par le MCU sur "
                + "ses broches : ils ne peuvent pas etre simules par la liaison serie."));

        p.addView(section("Etats vehicule"));
        p.addView(row("ACC (00)", simBtn("Present", 0x00, 1), simBtn("Coupe", 0x00, 0)));
        p.addView(row("Frein a main (04)", simBtn("Serre", 0x04, 1), simBtn("Desserre", 0x04, 0)));
        p.addView(row("Feux / ILL (0B)", simBtn("ON", 0x0B, 1), simBtn("OFF", 0x0B, 0)));
        p.addView(row("Mute (08)", simBtn("Coupe", 0x08, 1), simBtn("Actif", 0x08, 0)));
        p.addView(row("Antenne radio (43)", simBtn("ON", 0x43, 1), simBtn("OFF", 0x43, 0)));

        p.addView(section("Touches volant (20)"));
        p.addView(row("KEY1 (canal 5)", simBtn("Appui", 0x20, 5, 0xAA, 0x30, 0x30, 0x30),
                simBtn("Relache", 0x20, 5, 0xAA, 0xFF, 0xFF, 0xFF)));
        p.addView(row("KEY2 (canal 6)", simBtn("Appui", 0x20, 6, 0xAA, 0x28, 0x28, 0x28),
                simBtn("Relache", 0x20, 6, 0xAA, 0xFF, 0xFF, 0xFF)));

        p.addView(section("Sequences"));
        p.addView(row("Demarrage (PC_READY)", button("Rejouer", new View.OnClickListener() {
            public void onClick(View v) { simBoot(); }
        })));
        p.addView(row("Version + heure", button("Injecter", new View.OnClickListener() {
            public void onClick(View v) { simVersionTime(); }
        })));

        p.addView(section("Trame libre"));
        LinearLayout r = hrow();
        final EditText in = new EditText(this);
        in.setHint("CMD donnees... ex : 0B 01");
        in.setTextColor(TXT); in.setHintTextColor(DIM);
        in.setTypeface(Typeface.MONOSPACE);
        in.setSingleLine(true);
        in.setText("0B 01");
        r.addView(in, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(button("Injecter", new View.OnClickListener() {
            public void onClick(View v) {
                byte[] b = Frame.parseHex(in.getText().toString());
                if (b == null || b.length == 0) { toast("hexa invalide"); return; }
                if (b.length - 1 > Frame.MAX_DATA) { toast("trop long"); return; }
                simulateBytes(b[0] & 0xFF, Frame.slice(b, 1, b.length));
            }
        }));
        p.addView(r);
        p.addView(note("Astuce : l'onglet Console permet d'exporter le journal, melant trames reelles et simulees."));
        return scroll(p);
    }

    private Button simBtn(String s, final int cmd, final int... data) {
        Button b = button(s, new View.OnClickListener() { public void onClick(View v) { simulate(cmd, data); } });
        b.setBackgroundDrawable(pill(0xFF2A2436));
        return b;
    }

    /** Rejoue la sequence de demarrage du doc (section 5) : ACC, puis version +200 ms, puis date/heure +400 ms. */
    private void simBoot() {
        addLog("== simulation : demarrage (PC_READY)");
        simulate(0x00, 1);
        ui.postDelayed(new Runnable() { public void run() { simVersion(); } }, 200);
        ui.postDelayed(new Runnable() { public void run() { simTime(); } }, 400);
        toast("sequence de demarrage injectee");
    }

    private void simVersionTime() { simVersion(); simTime(); }

    private void simVersion() {
        byte[] d = "JCST_AC8257_8T7-2024.08.09_12:59".getBytes();
        simulateBytes(0x0A, d);
    }

    private void simTime() {
        Calendar c = Calendar.getInstance();
        int y = c.get(Calendar.YEAR);
        simulate(0x09, 0, y / 100, y % 100, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        simulate(0x09, 1, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    private View pageInfo() {
        LinearLayout p = vcol();
        infoView = text("", 14, TXT);
        infoView.setTypeface(Typeface.MONOSPACE);
        infoView.setTextIsSelectable(true);
        p.addView(infoView);
        LinearLayout r = hrow();
        r.addView(button("Actualiser", new View.OnClickListener() { public void onClick(View v) { refreshInfo(); } }));
        r.addView(button("Journal ivi-services ON", new View.OnClickListener() { public void onClick(View v) { backend.enableIviLog(true); refreshInfoLater(); } }));
        r.addView(button("OFF", new View.OnClickListener() { public void onClick(View v) { backend.enableIviLog(false); refreshInfoLater(); } }));
        p.addView(r);
        p.addView(note(
                "Service Jancar (sans root) : l'app se lie a com.jancar.services (CarService, AIDL ICar) et envoie par "
                + "sendPassthroughData (transaction 20) ; ivi-services construit la trame et gere l'ACK. Etats : callbacks "
                + "ICarCallback (version, ACC, frein, feux, touches). Trames brutes : journal d'ivi-services, qui exige "
                + "READ_LOGS (adb shell pm grant com.dokt.jacmcu android.permission.READ_LOGS) et le reglage "
                + "global_mcudatadebug=true.\n\n"
                + "Root : jacbridge ecrit chaque trame sur /dev/ttyS1 en un seul write(), sans reconfigurer le port ; la "
                + "lecture passe par le journal d'ivi-services (passif). La lecture directe du port prive ivi-services des "
                + "octets lus : a reserver a un test court.\n\n"
                + "Protocole : EE FA LEN CMD donnees CS (docs/mcu_firmware.md du depot)."));
        return scroll(p);
    }

    private void refreshInfoLater() { ui.postDelayed(new Runnable() { public void run() { refreshInfo(); } }, 1500); }

    private void refreshInfo() {
        if (infoView == null) return;
        final boolean root = backend instanceof RootBackend;
        new Thread(new Runnable() {
            public void run() {
                final StringBuilder sb = new StringBuilder();
                sb.append("Mode           : ").append(backend == null ? "-" : backend.name()).append('\n');
                sb.append("Android        : ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("Modele         : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
                sb.append("READ_LOGS      : ").append(checkCallingOrSelfPermission("android.permission.READ_LOGS")
                        == PackageManager.PERMISSION_GRANTED ? "accordee" : "non accordee").append('\n');
                try {
                    getPackageManager().getPackageInfo("com.jancar.services", 0);
                    sb.append("ivi-services   : ").append(getPackageManager().getPackageInfo("com.jancar.services", 0).versionName).append('\n');
                } catch (Exception e) { sb.append("ivi-services   : absent\n"); }
                if (root) {
                    String v = RootBackend.su("content query --uri content://com.jancar.settings.provider/settings --where \"name='"
                            + ServiceBackend.DEBUG_KEY + "'\"");
                    sb.append("Journal ivi    : ").append(v == null ? "su indisponible" : v.replace('\n', ' ')).append('\n');
                    String t = RootBackend.su("ls -l " + RootBackend.TTY + "; getprop persist.jancar.canversion");
                    sb.append("Port           : ").append(t == null ? "-" : t.replace('\n', ' ')).append('\n');
                    sb.append("jacbridge      : ").append(RootBackend.bridgePath(MainActivity.this)).append('\n');
                } else if (backend instanceof ServiceBackend) {
                    sb.append("Journal ivi    : ").append(((ServiceBackend) backend).readIviLog()).append('\n');
                }
                for (Map.Entry<String, String> e : new LinkedHashMap<String, String>(state).entrySet())
                    sb.append(pad(e.getKey(), 15)).append(": ").append(e.getValue()).append('\n');
                ui.post(new Runnable() { public void run() { infoView.setText(sb); } });
            }
        }).start();
    }

    // ================================================================== petits outils d'UI
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private GradientDrawable pill(int color) { return round(color, 18); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 15, DIM);
        t.setMinWidth(dp(200));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 13, DIM);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 16, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(14), 0, dp(4));
        return t;
    }

    private View space(int w) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1)); return v; }

    private LinearLayout hrow() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, dp(4), 0, dp(4));
        return l;
    }

    private LinearLayout vcol() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private ScrollView scroll(View v) { ScrollView s = new ScrollView(this); s.addView(v); return s; }

    private HorizontalScrollView hscroll(View v) { HorizontalScrollView s = new HorizontalScrollView(this); s.addView(v); return s; }

    private Button button(String s, View.OnClickListener c) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setTextSize(15);
        b.setBackgroundDrawable(pill(CARD));
        b.setPadding(dp(16), dp(6), dp(16), dp(6));
        b.setMinHeight(dp(44));
        if (c != null) b.setOnClickListener(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    /** Bouton qui envoie CMD + donnees. */
    private Button btn(String s, final int cmd, final int... data) {
        return button(s, new View.OnClickListener() { public void onClick(View v) { send(cmd, data); } });
    }

    private CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(TXT);
        c.setChecked(on);
        return c;
    }

    private LinearLayout row(String name, View... vs) {
        LinearLayout r = hrow();
        r.addView(label(name));
        for (View v : vs) r.addView(v);
        return r;
    }

    private SeekBar seek(int min, int max, int val) {
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(val - min);
        s.setMinimumWidth(dp(160));
        return s;
    }

    private LinearLayout slider(String name, final int min, int max, int val, final int sub) {
        LinearLayout r = hrow();
        r.addView(label(name));
        final SeekBar s = seek(min, max, val);
        final TextView v = text(String.valueOf(val), 15, TXT);
        v.setMinWidth(dp(48));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) { v.setText(String.valueOf(p + min)); }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        r.addView(s, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(v);
        r.addView(button("Envoyer", new View.OnClickListener() { public void onClick(View x) { send(0x0F, sub, s.getProgress() + min); } }));
        return r;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
