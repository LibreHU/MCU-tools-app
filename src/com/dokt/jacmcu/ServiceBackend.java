package com.dokt.jacmcu;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Mode sans root : liaison au service exporte com.jancar.services / CarService (AIDL com.jancar.services.car.ICar),
 * appels par codes de transaction (interfaces recopiees de l'APK ivi-services 3.0.0, voir docs/mcu_firmware.md §12-13).
 * Envoi = ICar.sendPassthroughData(byte id, byte[] data) (transaction 20) : ivi-services construit la trame et gere l'ACK.
 */
public final class ServiceBackend implements Backend {
    static final String ICAR = "com.jancar.services.car.ICar";
    static final String ICB = "com.jancar.services.car.ICarCallback";
    static final String IPT = "com.jancar.services.car.IPassthroughDataCallback";
    static final int T_GET_MCU_VERSION = 1, T_GET_CAR_ID = 2, T_REGISTER = 3, T_UNREGISTER = 4, T_REG_PT = 7, T_UNREG_PT = 8,
            T_GET_HANDBRAKE = 11, T_GET_HEADLIGHT = 14, T_SEND = 20, T_SET_ADKEY = 46, T_GET_CAN_VERSION = 66;
    static final Uri SETTINGS = Uri.parse("content://com.jancar.settings.provider/settings");
    static final String DEBUG_KEY = "global_mcudatadebug";

    private final Context ctx;
    private Listener l;
    private IBinder car;
    private LogSniffer sniffer;
    private boolean bound, rawKeys, canCapture;

    public ServiceBackend(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public String name() { return "Service Jancar"; }

    // ------------------------------------------------------------------ callbacks ICarCallback (codes 1..34)
    private static final String[] CB = new String[35];
    static {
        String[] t = {
            "1 onMcuVersion s", "2 onAccChanged b", "3 onCcdChanged i", "4 onHandbrakeChanged b", "5 onDoorChanged ii",
            "6 onLightChanged ii", "7 onHeadLightChanged b", "8 onClimateChanged ii", "9 onOutsideTempChanged i",
            "10 onKeyPushed ii", "11 onAlertMessage i", "12 onTripChanged iif", "13 onRealTimeInfoChanged if",
            "14 onExtraStateChanged if", "15 onRadarChanged iB", "16 onCarSettingChanged iB", "17 onExtraDeviceChanged iiB",
            "18 onCmdParamChanged iB", "19 onMaintenanceChanged iii", "20 onCarVINChanged si", "21 onCarReportChanged iiI",
            "22 onAutoParkChanged i", "23 onEnergyFlowChanged iiiii", "24 onFastReverseChanged b", "25 onADKeyChanged iiiii",
            "26 onClusterMessage B", "27 onTirePressureChanged iiii", "28 onEventHardwareVersion isssss",
            "29 onMaintainWarning b", "30 onBackLightChanged i", "31 onCarModelChange i", "32 onPowerChange i",
            "33 onUsbPinCodeChange i", "34 onDvrSignalValueChange i"};
        for (String s : t) { String[] p = s.split(" "); CB[Integer.parseInt(p[0])] = p[1] + " " + p[2]; }
    }

    static Object[] read(Parcel d, String sig) {
        Object[] o = new Object[sig.length()];
        for (int i = 0; i < sig.length(); i++) {
            switch (sig.charAt(i)) {
                case 's': o[i] = d.readString(); break;
                case 'b': o[i] = d.readInt() != 0; break;
                case 'f': o[i] = d.readFloat(); break;
                case 'B': o[i] = d.createByteArray(); break;
                case 'I': o[i] = d.createIntArray(); break;
                default: o[i] = d.readInt();
            }
        }
        return o;
    }

    static String fmt(String name, Object[] a) {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            Object v = a[i];
            if (v instanceof byte[]) sb.append('[').append(Frame.hex((byte[]) v)).append(']');
            else if (v instanceof int[]) sb.append(java.util.Arrays.toString((int[]) v));
            else sb.append(v);
        }
        return sb.append(')').toString();
    }

    private final Binder callback = new Binder() {
        { attachInterface(null, ICB); }
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code < 1 || code > 34) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(ICB);
            String[] p = CB[code].split(" ");
            Object[] a = read(data, p[1]);
            if (reply != null) reply.writeNoException();
            Listener ll = l;
            if (ll == null) return true;
            ll.onLog("service : " + fmt(p[0], a));
            switch (code) {
                case 1: ll.onState("version", String.valueOf(a[0])); break;
                case 2: ll.onState("acc", onoff((Boolean) a[0])); break;
                case 4: ll.onState("hb", (Boolean) a[0] ? "serre" : "desserre"); break;
                case 7: ll.onState("ill", onoff((Boolean) a[0])); break;
                case 10: ll.onState("key", "touche " + a[0] + " type " + a[1]); break;
                case 25: ll.onState("key", "canal " + a[0] + " valeur " + a[1] + " (h " + a[2] + " m " + a[3] + " b " + a[4] + ")"); break;
                case 30: ll.onState("bl", String.valueOf(a[0])); break;
                case 32: ll.onState("power", String.valueOf(a[0])); break;
                default: break;
            }
            return true;
        }
    };

    private final Binder passthrough = new Binder() {
        { attachInterface(null, IPT); }
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code < 1 || code > 8) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(IPT);
            if (code == 1) {
                byte[] b = data.createByteArray();
                int n = data.readInt();
                if (reply != null) reply.writeNoException();
                if (b != null && l != null) {
                    byte[] d = Frame.slice(b, 0, Math.max(0, Math.min(n, Math.min(b.length, Frame.MAX_DATA))));
                    l.onFrame(true, Frame.build(0x10, d), "service");
                }
                return true;
            }
            String[] sig = {"", "", "iiii", "issii", "iii", "bi", "iss", "ssib", "si"};
            String[] nm = {"", "", "onMediaStateChanged", "onMediaInfoChanged", "onVolumeChanged", "onMuteStateChanged",
                    "onBluetoothConnectStatus", "onBluetoothCallStatus", "onEventDiskChanged"};
            Object[] a = read(data, sig[code]);
            if (reply != null) reply.writeNoException();
            if (l != null) l.onLog("service : " + fmt(nm[code], a));
            return true;
        }
    };

    static String onoff(boolean b) { return b ? "ON" : "OFF"; }

    // ------------------------------------------------------------------ liaison
    private final ServiceConnection conn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            car = b;
            l.onStatus("lie a " + n.flattenToShortString(), true);
            new Thread(new Runnable() { public void run() { afterConnect(); } }, "jacmcu-init").start();
        }
        public void onServiceDisconnected(ComponentName n) {
            car = null;
            l.onStatus("service deconnecte (ivi-services redemarre ?)", false);
        }
    };

    public void start(Listener listener) {
        l = listener;
        Intent i = new Intent("com.jancar.services.action.car").setPackage("com.jancar.services");
        try {
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
            l.onLog("liaison refusee : " + e.getMessage());
        }
        if (!bound) l.onStatus("com.jancar.services introuvable / liaison refusee", false);
        else l.onStatus("liaison en cours...", false);
        IntentFilter f = new IntentFilter("com.jancar.services.action.acc");
        ctx.registerReceiver(acc, f);
        if (ctx.checkCallingOrSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED) {
            sniffer = new LogSniffer(l, false);
            sniffer.start();
        } else {
            l.onLog("trames brutes : non disponibles sans READ_LOGS. Une fois depuis un PC : "
                    + "adb shell pm grant " + ctx.getPackageName() + " android.permission.READ_LOGS (puis relancer l'app)");
        }
    }

    private final BroadcastReceiver acc = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (l != null && i.hasExtra("acc")) l.onState("acc", onoff(i.getBooleanExtra("acc", false)));
        }
    };

    private void afterConnect() {
        try {
            call(T_REGISTER, binderArg(callback));
            l.onLog("callback ICarCallback enregistre");
        } catch (Exception e) { l.onLog("registerCallback : " + e); }
        refresh();
    }

    /** Lecture des getters utiles (isAccOn est un bouchon qui renvoie toujours false dans ivi-services 3.0.0). */
    public void refresh() {
        if (car == null) return;
        try {
            Parcel r = call(T_GET_MCU_VERSION, null);
            String v = r.readString(); r.recycle();
            if (v != null && !v.isEmpty()) l.onState("version", v);
            r = call(T_GET_HANDBRAKE, null); int hb = r.readInt(); r.recycle();
            l.onState("hb", hb != 0 ? "serre" : "desserre");
            r = call(T_GET_HEADLIGHT, null); boolean ill = r.readInt() != 0; r.recycle();
            l.onState("ill", onoff(ill));
            r = call(T_GET_CAR_ID, null); int id = r.readInt(); r.recycle();
            l.onState("carid", String.valueOf(id));
            r = call(T_GET_CAN_VERSION, null); String cv = r.readString(); r.recycle();
            l.onState("canver", cv == null || cv.isEmpty() ? "-" : cv);
        } catch (Exception e) {
            l.onLog("lecture service : " + e);
        }
    }

    interface Writer { void write(Parcel p); }

    static Writer binderArg(final IBinder b) { return new Writer() { public void write(Parcel p) { p.writeStrongBinder(b); } }; }

    /** transact() synchrone ; renvoie la reponse (positionnee apres l'en-tete d'exception) a recycler. */
    private Parcel call(int code, Writer w) throws Exception {
        IBinder b = car;
        if (b == null) throw new IllegalStateException("service non lie");
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(ICAR);
            if (w != null) w.write(d);
            if (!b.transact(code, d, r, 0)) throw new IllegalStateException("transaction " + code + " refusee");
            r.readException();
            return r;
        } catch (Exception e) {
            r.recycle();
            throw e;
        } finally {
            d.recycle();
        }
    }

    public boolean send(final int cmd, final byte[] data) {
        if (car == null) { l.onLog("non envoye : service non lie"); return false; }
        try {
            call(T_SEND, new Writer() { public void write(Parcel p) { p.writeByte((byte) cmd); p.writeByteArray(data); } }).recycle();
            l.onFrame(false, Frame.build(cmd, data), "app");
            return true;
        } catch (Exception e) {
            l.onLog("sendPassthroughData : " + e);
            return false;
        }
    }

    /** setADKey(canal, 0) : canal != 161 -> ivi-services remonte les valeurs ADC brutes par onADKeyChanged. */
    public void setRawKeys(boolean on) {
        rawKeys = on;
        final int ch = on ? 0 : 161;
        try {
            call(T_SET_ADKEY, new Writer() { public void write(Parcel p) { p.writeInt(ch); p.writeInt(0); } }).recycle();
            l.onLog("setADKey(" + ch + ") : touches brutes " + (on ? "ON" : "OFF"));
        } catch (Exception e) { l.onLog("setADKey : " + e); }
    }

    /** Remplace l'unique client IPassthroughDataCallback (normalement com.jancar.canservice) pour voir les trames 0x10. */
    public void setCanCapture(boolean on) {
        canCapture = on;
        try {
            call(on ? T_REG_PT : T_UNREG_PT, binderArg(passthrough)).recycle();
            l.onLog(on ? "capture CAN active (com.jancar.canservice ne recoit plus rien jusqu'au redemarrage)" : "capture CAN arretee");
        } catch (Exception e) { l.onLog("passthrough : " + e); }
    }

    public void enableIviLog(boolean on) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put("value", on ? "true" : "false");
            int n = cr.update(SETTINGS, v, "name=?", new String[]{DEBUG_KEY});
            if (n == 0) { v.put("name", DEBUG_KEY); cr.insert(SETTINGS, v); }
            l.onLog("journal ivi-services " + (on ? "active" : "desactive") + " (" + DEBUG_KEY + ")");
        } catch (Exception e) {
            l.onLog("reglage " + DEBUG_KEY + " : " + e.getMessage() + " -> utiliser le mode root");
        }
    }

    public String readIviLog() {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(SETTINGS, new String[]{"value"}, "name=?", new String[]{DEBUG_KEY}, null);
            return c != null && c.moveToFirst() ? c.getString(0) : "(absent)";
        } catch (Exception e) {
            return "illisible (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (c != null) c.close();
        }
    }

    public void stop() {
        if (sniffer != null) sniffer.stop();
        try { ctx.unregisterReceiver(acc); } catch (Exception ignored) { }
        if (car != null) {
            try {
                if (rawKeys) setRawKeys(false);
                if (canCapture) setCanCapture(false);
                call(T_UNREGISTER, binderArg(callback)).recycle();
            } catch (Exception ignored) { }
        }
        if (bound) try { ctx.unbindService(conn); } catch (Exception ignored) { }
        bound = false;
        car = null;
    }

    public boolean isRawKeys() { return rawKeys; }
    public boolean isCanCapture() { return canCapture; }
}
