package com.dokt.jacmcu;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * i18n minimaliste, adapte a l'UI construite en code (sans ressources Android).
 * Le texte source (francais) sert de cle ; chaque langue fournit une table de traduction.
 * Langue : "fr", "en", ou "auto" (= langue du systeme). Cle absente -> on renvoie le francais.
 * Pour ajouter une langue : creer une Map comme EN et l'aiguiller dans setLang().
 */
public final class I18n {
    private I18n() {}

    /** Codes proposes par le selecteur de l'app. */
    public static final String[] LANGS = {"auto", "fr", "en"};

    private static String lang = "auto";
    private static Map<String, String> table = null;   // null = francais (base)

    public static void setLang(String code) {
        lang = (code == null) ? "auto" : code;
        String eff = "auto".equals(lang) ? Locale.getDefault().getLanguage() : lang;
        table = "en".equals(eff) ? EN : null;
    }

    public static String lang() { return lang; }

    /** Traduit vers la langue courante ; renvoie le francais si la cle est absente. */
    public static String t(String fr) {
        if (table == null || fr == null) return fr;
        String v = table.get(fr);
        return v != null ? v : fr;
    }

    private static final Map<String, String> EN = new HashMap<String, String>();
    private static void p(String fr, String en) { EN.put(fr, en); }

    static {
        // Onglets
        p("Etat", "Status"); p("Commandes", "Commands"); p("Console", "Console");
        p("Simulation", "Simulation"); p("Infos", "Info");

        // En-tete / modes
        p("Service Jancar", "Jancar service"); p("Root", "Root");
        p("lecture directe du port", "direct port read");
        p("Lecture directe : ivi-services ne recoit plus les trames lues (ACC, feux...). A n'utiliser que pour un test court.",
          "Direct read: ivi-services no longer receives the frames read (ACC, lights...). Use only for a short test.");
        p("demarrage ", "starting ");

        // Onglet Etat : pastilles
        p("Version MCU", "MCU version"); p("Frein a main", "Handbrake"); p("Feux (ILL)", "Lights (ILL)");
        p("Mute", "Mute"); p("Retroeclairage", "Backlight"); p("Antenne radio", "Radio antenna");
        p("Option +0x11", "Option +0x11"); p("Date MCU", "MCU date"); p("Heure MCU", "MCU time");
        p("Derniere touche", "Last key"); p("Alim. (service)", "Power (service)"); p("Car ID", "Car ID");
        p("Version CAN", "CAN version"); p("Trames vues", "Frames seen");
        p("Interroger le MCU", "Query the MCU"); p("Relire le service", "Re-read service");
        p("Activer journal ivi-services", "Enable ivi-services log");
        p("mode service uniquement", "service mode only");
        p("Les reponses aux requetes arrivent a ivi-services. Elles s'affichent ici via le journal d'ivi-services (mode root, ou mode service avec READ_LOGS) ou via la lecture directe du port.",
          "Replies to queries go to ivi-services. They show here via the ivi-services log (root mode, or service mode with READ_LOGS) or via direct port read.");

        // Onglet Commandes
        p("Sorties", "Outputs"); p("Mute (08)", "Mute (08)"); p("Couper", "Cut"); p("Retablir", "Restore");
        p("Antenne radio (43)", "Radio antenna (43)"); p("REM ampli (44)", "Amp REM (44)");
        p("Luminosite / LED (0F)", "Brightness / LED (0F)"); p("LED facade R/G/B", "Panel LED R/G/B");
        p("mode : manuel", "mode: manual"); p("mode : ", "mode: ");
        p("auto", "auto"); p("manuel", "manual"); p("semi-auto", "semi-auto");
        p("Protection batterie (0F 0A / 0F 0B)", "Battery protection (0F 0A / 0F 0B)");
        p("Seuil haut", "High threshold"); p("Seuil bas", "Low threshold");
        p("Horloge / apprentissage", "Clock / learning"); p("Heure MCU (09)", "MCU time (09)");
        p("Envoyer l'heure Android", "Send Android time"); p("Lire", "Read");
        p("Touches volant (11)", "Steering keys (11)"); p("Debut", "Start"); p("Fin", "Stop");
        p("Facade / molette (21)", "Panel / wheel (21)"); p("Touches brutes (service)", "Raw keys (service)");
        p("Boitier CAN (0F 00, USART1)", "CAN box (0F 00, USART1)");
        p("01 (extinction), 0E (reset SoC) et F1 (veille) passent par la console avec confirmation. 80 (bootloader) est bloque. Les reglages 0F sont enregistres en flash par le MCU 3 s apres.",
          "01 (power off), 0E (SoC reset) and F1 (sleep) go through the console with confirmation. 80 (bootloader) is blocked. 0F settings are saved to flash by the MCU 3 s later.");

        // Onglet Console
        p("Envoyer", "Send"); p("Option ?", "Option?"); p("masquer ACK", "hide ACK"); p("pause", "pause");
        p("Effacer", "Clear"); p("Exporter", "Export"); p("Capture CAN ON/OFF", "CAN capture ON/OFF");
        p("hexa invalide", "invalid hex"); p("trop long", "too long");
        p("trop de donnees (max ", "too much data (max ");
        p("journal : ", "log: "); p("export impossible : ", "export failed: ");
        p("trame : --", "frame: --"); p("trame : ", "frame: ");
        p("   BLOQUE", "   BLOCKED"); p("   confirmation demandee", "   confirmation required");

        // Dialogues
        p("%02X (bootloader) bloque dans l'app : risque de MCU inutilisable",
          "%02X (bootloader) blocked in the app: risk of bricking the MCU");
        p("Commande %02X : %s", "Command %02X: %s");
        p("Cette commande peut eteindre ou redemarrer l'autoradio (eventuellement en roulant). Envoyer quand meme ?\n\n",
          "This command can power off or reboot the head unit (possibly while driving). Send anyway?\n\n");
        p("Annuler", "Cancel");
        p("Capturer les trames CAN (0x10) ?", "Capture CAN frames (0x10)?");
        p("ivi-services n'accepte qu'un client : com.jancar.canservice (appli canbus) ne recevra plus rien jusqu'au prochain redemarrage d'ivi-services / de l'autoradio.",
          "ivi-services accepts a single client: com.jancar.canservice (canbus app) will receive nothing until ivi-services / the head unit next restarts.");
        p("Capturer", "Capture");

        // Onglet Simulation
        p("Simulation locale : les trames ci-dessous sont injectees dans le decodeur et le journal de l'app comme si le MCU les avait envoyees. Rien n'est ecrit sur /dev/ttyS1 : c'est pour tester l'interface et le decodage sans la voiture. Les vrais ACC / frein / feux sont lus par le MCU sur ses broches : ils ne peuvent pas etre simules par la liaison serie.",
          "Local simulation: the frames below are injected into the app's decoder and log as if the MCU had sent them. Nothing is written to /dev/ttyS1: this is to test the UI and decoding without the car. The real ACC / handbrake / lights are read by the MCU on its pins: they cannot be simulated over the serial link.");
        p("Etats vehicule", "Vehicle states"); p("ACC (00)", "ACC (00)"); p("Frein a main (04)", "Handbrake (04)");
        p("Feux / ILL (0B)", "Lights / ILL (0B)"); p("Present", "Present"); p("Coupe", "Cut");
        p("Serre", "Applied"); p("Desserre", "Released"); p("Actif", "Active");
        p("Touches volant (20)", "Steering keys (20)"); p("KEY1 (canal 5)", "KEY1 (channel 5)");
        p("KEY2 (canal 6)", "KEY2 (channel 6)"); p("Appui", "Press"); p("Relache", "Release");
        p("Sequences", "Sequences"); p("Demarrage (PC_READY)", "Startup (PC_READY)"); p("Rejouer", "Replay");
        p("Version + heure", "Version + time"); p("Injecter", "Inject"); p("Trame libre", "Custom frame");
        p("sequence de demarrage injectee", "startup sequence injected");
        p("Astuce : l'onglet Console permet d'exporter le journal, melant trames reelles et simulees.",
          "Tip: the Console tab can export the log, mixing real and simulated frames.");

        // Onglet Infos
        p("Actualiser", "Refresh"); p("Journal ivi-services ON", "ivi-services log ON");
        p("Service Jancar (sans root) : l'app se lie a com.jancar.services (CarService, AIDL ICar) et envoie par sendPassthroughData (transaction 20) ; ivi-services construit la trame et gere l'ACK. Etats : callbacks ICarCallback (version, ACC, frein, feux, touches). Trames brutes : journal d'ivi-services, qui exige READ_LOGS (adb shell pm grant com.dokt.jacmcu android.permission.READ_LOGS) et le reglage global_mcudatadebug=true.\n\nRoot : jacbridge ecrit chaque trame sur /dev/ttyS1 en un seul write(), sans reconfigurer le port ; la lecture passe par le journal d'ivi-services (passif). La lecture directe du port prive ivi-services des octets lus : a reserver a un test court.\n\nProtocole : EE FA LEN CMD donnees CS (docs/mcu_firmware.md du depot).",
          "Jancar service (no root): the app binds to com.jancar.services (CarService, AIDL ICar) and sends via sendPassthroughData (transaction 20); ivi-services builds the frame and handles the ACK. States: ICarCallback callbacks (version, ACC, handbrake, lights, keys). Raw frames: the ivi-services log, which requires READ_LOGS (adb shell pm grant com.dokt.jacmcu android.permission.READ_LOGS) and the global_mcudatadebug=true setting.\n\nRoot: jacbridge writes each frame to /dev/ttyS1 in a single write(), without reconfiguring the port; reading goes through the ivi-services log (passive). Reading the port directly steals bytes from ivi-services: reserve for a short test.\n\nProtocol: EE FA LEN CMD data CS (docs/mcu_firmware.md of the repo).");

        // Valeurs d'etat decodees (affichage ; la logique garde le francais canonique)
        p("serre", "applied"); p("desserre", "released"); p("coupe", "muted"); p("actif", "active");
    }
}
