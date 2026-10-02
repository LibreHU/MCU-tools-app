package com.dokt.jacmcu;

/** Acces au MCU : via le service Jancar (AIDL ICar) ou en root (ecriture directe sur /dev/ttyS1). */
public interface Backend {
    /** Appels depuis des threads quelconques. */
    interface Listener {
        /** Trame vue sur la liaison. fromMcu = MCU -> SoC ; source = "app", "ivi" (journal ivi-services), "port", "service". */
        void onFrame(boolean fromMcu, byte[] frame, String source);
        /** Etat decode : cle (acc, hb, ill, mute, bl, radio, version, date, time, key, power, carid...) -> valeur. */
        void onState(String key, String value);
        /** Ligne de journal libre. */
        void onLog(String text);
        /** Etat de la connexion. */
        void onStatus(String text, boolean ok);
    }

    String name();
    void start(Listener l);
    void stop();
    /** Envoie CMD + donnees. Renvoie false si non envoye (message deja journalise). */
    boolean send(int cmd, byte[] data);
    /** Active le journal des trames d'ivi-services (reglage global_mcudatadebug). */
    void enableIviLog(boolean on);
}
