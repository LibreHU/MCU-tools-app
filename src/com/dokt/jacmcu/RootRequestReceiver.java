package com.dokt.jacmcu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * LibreHU : root pour toutes les applis LibreHU d'un coup (assistant de premier demarrage de LibreHU Launcher). Sur
 * org.librehu.action.REQUEST_ROOT envoye a ce paquet, lance une fois "su -c id" pour que SuperSU affiche maintenant la
 * demande de cette appli (a accorder avec "Se souvenir"), puis repond org.librehu.action.ROOT_RESULT (extras
 * "package", "granted") au paquet indique par "reply_to". Rien d'autre que "id" n'est lance ; une seconde demande
 * dans les 20 s est ignoree.
 */
public final class RootRequestReceiver extends BroadcastReceiver {
    public static final String ACTION_REQUEST = "org.librehu.action.REQUEST_ROOT";
    public static final String ACTION_RESULT = "org.librehu.action.ROOT_RESULT";
    private static volatile long last;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_REQUEST.equals(intent.getAction())) return;
        final String replyTo = intent.getStringExtra("reply_to");
        if (replyTo == null || !replyTo.matches("[A-Za-z0-9_.]{1,100}")) return;
        long now = SystemClock.elapsedRealtime();
        if (last != 0 && now - last < 20000) return;
        last = now;
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        // Pas de lambda : la compilation sans Gradle (javac + android.jar) n'a pas LambdaMetafactory.
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean granted = false;
                try {
                    Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
                    p.getOutputStream().close();
                    // SuperSU attend la reponse ; une diffusion en arriere-plan a jusqu'a une minute.
                    boolean done = p.waitFor(50, TimeUnit.SECONDS);
                    if (!done) {
                        p.destroy();
                    } else {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) if (line.contains("uid=0")) granted = true;
                    }
                } catch (Exception ignored) {
                }
                try {
                    app.sendBroadcast(new Intent(ACTION_RESULT).setPackage(replyTo)
                            .putExtra("package", app.getPackageName()).putExtra("granted", granted));
                } finally {
                    pending.finish();
                }
            }
        }, "librehu-root").start();
    }
}
