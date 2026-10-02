# Audio, caméra de recul et Bluetooth (ivi-services / ivi-audio-settings)

Analyse statique (jadx) de `ivi-services.apk` (`com.jancar.services`), `ivi-audio-settings.apk`
(`com.jancar.audiosettings`), `ivi-btservice.apk` et `ivi-bt.apk`, croisée avec un dump de l'UJC201
(`ro.build.display.id=UJC201-V1.1.35R6-250718_0429`, `ro.board.platform=ac8257`).
Les APK ne sont pas dans le dépôt (fichiers constructeur). Niveaux : **[A]** lu dans l'APK, **[V]** vu
dans le dump, **[D]** déduit.

## 1. En bref

- **Le traitement audio n'est pas fait par Android** : l'EQ, la balance et le fader, les délais par
  haut-parleur, le subwoofer et le loudness sont réglés dans une **puce audio externe sur l'I2C 6**,
  pilotée par ivi-services via `libJanCarIVI.so` [A].
- L'app d'égaliseur (`com.jancar.audiosettings`) n'est qu'une interface : tout passe par l'AIDL
  **`com.jancar.services.audio.IAudio`**, en particulier **`setParam(id, valeur)`** [A].
- Le service audio est **exporté sans permission** (`AudioService`, action `com.jancar.services.action.audio`),
  comme `CarService` : **une app tierce peut s'y lier et régler la puce** [A].
- La MCU n'intervient pas dans l'audio, à part le mute (`08`) et le REM de l'ampli (`44`) (voir `mcu_firmware.md`).

## 2. Quelle puce sur cette carte ?

ivi-services choisit le pilote d'après le **board id** lu dans
`/sys/devices/virtual/mtk-adc-cali/mtk-adc-cali/jancar_board_id` (la clé `BoardId=` de `[Platform]`
dans `ivi-config.ini` est vide sur ce dump [V]). Le **4e caractère** donne la puce [A] :

| 4e caractère | Puce | `ChipId` | Capacités exposées par ivi-services |
|---|---|---|---|
| `A` | **ROHM BD37534** (processeur analogique) | 2 | EQ 3 bandes matériel (présenté sur 6 curseurs), balance/fader, sub (gain), loudness |
| `B` | **ROHM BU32107** (DSP) — valeur **par défaut** si inconnu | 7 | EQ **16 bandes**, **délais par HP** (FL/FR/RL/RR, 0..20), P2Bass AV/AR, filtres IIR AV/AR, sub LPF/HPF |
| `C` | **AKM AK7604** (DSP) | 8 | EQ **13 bandes**, délais par HP, mêmes réglages avancés que le BU32107 |

Le SDK connaît aussi AK7601, TEF6638, TM2313, YMU836 et ES7243L (autres cartes). Pour lire la valeur sur l'appareil :
```sh
adb shell cat /sys/devices/virtual/mtk-adc-cali/mtk-adc-cali/jancar_board_id
adb logcat -d | grep -E "boardId|audioDeviceID"      # ivi-services journalise la valeur au démarrage
```
Dans `last_kmsg` [V], seul l'ampli « smart PA » MTK (`speaker_amp 3-0034`) apparaît, et son probe échoue (-121) :
il n'est pas utilisé. La puce audio, elle, est gérée en espace utilisateur (ivi-services).

## 3. AIDL `IAudio` (descripteur `com.jancar.services.audio.IAudio`)

Liaison : `new Intent("com.jancar.services.action.audio").setPackage("com.jancar.services")`.

| Transaction | Méthode | Notes |
|---|---|---|
| 2 / 3 | `registerCallback` / `unRegisterCallback(IAudioCallback)` | volume, mute, barre de volume, canal maître |
| 4..7 | `isParamAvailable` / `getParamMin/Max/DefaultValue(int id)` | à interroger pour savoir ce que la puce supporte |
| 8 | `int getParam(int id)` | |
| **9** | **`setParam(int id, int value)`** | **réglage principal** (voir les identifiants plus bas) |
| 10 | `int[] getEqGains(int eqMode)` | préréglages |
| 11..24 | pré-volumes par source (`BuildInPreVolume`, principal et secondaire) | en dB, par canal audio |
| 25..38 | gain de volume maître / secondaire | courbe de volume |
| 48 | `requestInternalShortMute(int ms)` | |
| 50 | `int getMasterAudioChannel()` | source active |
| 51 | `setChipParam(int chipId, int paramId, double v0..v3)` | accès bas niveau à la puce |
| 52..54 | effets « expert » (fichiers de coefficients) | |

Appel brut, sans les classes AIDL (même principe que pour `ICar`) :
```java
static void setAudioParam(IBinder b, int id, int value) throws RemoteException {   // transaction 9
    Parcel in = Parcel.obtain(), out = Parcel.obtain();
    try { in.writeInterfaceToken("com.jancar.services.audio.IAudio"); in.writeInt(id); in.writeInt(value);
          b.transact(9, in, out, 0); out.readException(); }
    finally { in.recycle(); out.recycle(); }
}
```

### Identifiants de `setParam` (`AudioParam.Id`) [A]
| Id | Nom | Plage typique (BU32107 / AK7604) |
|---|---|---|
| 1 / 2 | MUTE / MUTE_SECONDARY | 0/1 |
| 10..19, 65 | volumes (maître, média, BT, sonnerie, alarme, CCD, navi, TTS, secondaire, radio, téléphone) | 0..40 |
| 21 / 22 / 23 | BASS / MIDDLE / TREBLE | 0..20 |
| **24** | **BALANCE_FADE** | balance et fader **0..60** chacun (30 = centre), encodés `((balance+100) << 16) \| (fade+100)` |
| 25 | POSITION (zone d'écoute préréglée) | 0..4 |
| 27 | LOUDNESS | 0..10 |
| 31 | EQ_COUNT (lecture seule) | 6 / 16 / 13 selon la puce |
| 32 | EQ_MODE (préréglage) | 1..15 |
| **33..36** | **LF / LR / RL / RR_SPEAK_DELAY** (alignement temporel par haut-parleur) | 0..20 |
| 37..40 | FRONT/REAR_P2BASS_DB et _FC | 0..12 / 0..7 |
| 41 / 42 / 43 | CENTER / SUBWOOFER (gain) / SURROUND | sub 0..12 |
| 51 | ASL (volume asservi à la vitesse) | 0..3 |
| 52 | SUBWOOFER_SPEAKER_SWITCH | 0/1 |
| 101..108 | CAR_AMP_* (volume, graves, médiums, aigus, ASL, source, mute d'un **ampli d'origine**, via le CAN) | |
| 109..113 | réglages de Q de l'EQ | |
| 114 / 115 | gains micro (voix, appel BT) | |
| 116 / 117 / 118 | IIR_STRENGTH / IIR_FILTER_FRONT / IIR_FILTER_REAR | -20..20 / 0..230 |
| 119 / 120 | SUB_FILTER_LPF / SUB_FILTER_HPF | 0..11 |
| 1000+n / 1020+n / 1040+n | EQ bande n : gain / Q / fréquence centrale | gain 0..20 (10 = 0 dB) |

Constantes des haut-parleurs (`IVIAudio.Speaker`) : FL=0, FR=1, RL=2, RR=3, SWL=4, SWR=5.

## 4. Conséquences pour un égaliseur tiers (fork ViPER4Android)

- **ViPER agit sur le flux Android** (effet AudioFlinger, en général stéréo), **en amont** de la puce. Il ne
  peut donc pas traiter séparément l'avant et l'arrière : cette séparation n'existe que dans la puce [D].
- **EQ par haut-parleur, fader, balance, délais, sub** : à piloter **via `IAudio.setParam`**. C'est possible
  sans root, puisque le service est exporté sans permission.
- Combinaison naturelle : ViPER pour le « son » (convolver, bass, clarity, etc.), et un onglet « Véhicule »
  dans le fork qui règle la puce (fader/balance, délais FL/FR/RL/RR, EQ matériel, sub LPF/HPF, ampli).
  Ce que la puce supporte se lit avec `isParamAvailable` et les min/max (transactions 4..7).
- Sur une ROM **LineageOS sans ivi-services**, il faudrait réimplémenter le pilote I2C de la puce (registres
  du BD37534 publics dans la datasheet ROHM ; BU32107/AK7604 beaucoup moins documentés).

## 5. Caméra de recul [A]

- La marche arrière (« CCD ») est lue **sur un GPIO du SoC** (`getCcdStatus()`), **pas via la MCU**.
  Elle peut aussi venir du CAN (broadcast `action_backcar_notification`, extra `backcar`).
- Mode « fast reverse » Autochips géré tôt au démarrage (propriété système), puis l'app
  **`com.autochips.backcarapp`** prend le relais.
- Broadcasts système : `android.backcar.action.PREPARE_START`, `.STARTED`, `.FINISH`.
- Le dump contient la configuration caméra et AVM : `vendor/etc/atc_camera_config.xml`,
  `atc_camera_source_config.xml`, `/avm/*.xml` [V].

## 6. Bluetooth [A]

`com.jancar.btservice` et `com.jancar.bluetooth` (uid système) utilisent la **pile Bluetooth Android
standard** (profils voiture `HeadsetClient`, `A2dpSink`, `PbapClient`), pas de module série dédié.

## 7. Pilote natif du ROHM BD37534 (`libJanCarIVI.so`) [N]

**[N]** = lu par désassemblage de `lib/arm64-v8a/libJanCarIVI.so` (aarch64, « stripped », mais les symboles C++
exportés sont présents : classe `AudioBD37534`). Le registre de chaque écriture est l'argument
`I2C::write(registre, &octet, 1, 1)`.

- Puce sur **`/dev/i2c-6`** (`AUDIO_DSP_I2C_INDEX = 6` côté Java), adresse **0x40** (`I2C::open(bus, 0x40)`).
- `setParam(id, valeur)` est un `switch` sur l'identifiant :

| Id `setParam` | Fonction | Registre(s) BD37534 | Encodage |
|---|---|---|---|
| 10, 12, 13 (volumes) | volume principal | 0x20 | octet = 0x80 − dB, bridé à +15 dB |
| 21 / 22 / 23 | graves / médiums / aigus | 0x51 / 0x54 / 0x57 | g = 2·v − 20 dB (v = 0..20) ; g < 0 → `0x80 \| −g` (atténuation), sinon g |
| 24 | balance / fader | 0x28, 0x29, 0x2A, 0x2B (fader des 4 voies) | voir plus bas |
| 27 | loudness | `setloudness` (0x75) | |
| 42 | niveau du caisson | 0x2C (fader caisson) | octet = 0x80 − (base + niveau) ; base = **−5 dB** sur cette carte → niveau 0..12 = **−5..+7 dB** (6 = +1 dB) ; écrit **seulement si le caisson est activé** |
| **52** | **sortie caisson on/off** | 0x2C puis 0x02 | voir plus bas |
| 1000..1002 | « EQ » bandes 0/1/2 | 0x57 / 0x54 / 0x51 | **mêmes registres que 23 / 22 / 21** |
| 1003..1005 | « EQ » bandes 3/4/5 | 0x41 / 0x44 / 0x47 | choix fréquence / Q des filtres graves / médiums / aigus |
| 100..103 | setup graves / médiums / aigus, phase caisson | 0x41 / 0x44 / 0x47 / 0x02 (bit 7) | |
| autres (dont **119/120 filtres caisson**, délais 33..36) | — | — | ignorés (« unknown param » dans le journal) |

Conséquences : le BD37534 n'a **pas de vrai EQ 6 bandes**. Les « 6 bandes » d'ivi-services sont les 3 gains
plus leurs 3 sélecteurs de fréquence/Q. Les filtres caisson 119/120 et les délais par haut-parleur n'existent
pas sur cette puce.

### Configuration de la carte A0_AN [A]
`Platform.createConfig()` appelle toujours `identifyBoardTypeAC8257()` : `A0` + 4e caractère `A` →
**`Platform_AutoChips_8257_37534`** (section `[Audio_A0_AN]` d'`ivi-config.ini`). Cette classe :
- volume 0..40 sur la courbe `BD37534VolumeCurve` d'`ivi-config.ini` ; la valeur du dump (`-79.0(0)`) est trop
  courte, donc courbe par défaut : −79, −60, −55, −50 … −14 (pas 10) … 0 (pas 32) … **+5 dB** (pas 40) ;
- gains d'entrée : canal Android/PC (11) 0 dB, radio (3) et AV/AUX (0) +5 dB ; avec une radio interne,
  le canal radio est celui d'Android ;
- **`setSubWooferBaseValue(-5)`** : décalage de −5 dB ajouté au niveau du caisson.

### Sortie caisson (`setSubWooferOnOff`, id 52)
- **ON** : mémorise l'état, place le champ « fc du filtre passe-bas caisson » du registre 0x02 à **3**, puis
  réapplique le niveau (`setSubWoofer` → 0x2C = 0x80 − (base + niveau), base = −5 dB), avec une pause de 150 ms.
- **OFF** : écrit **0x00 dans 0x2C**, attend 2 × 150 ms (anti-pop), puis remet le champ fc à **0** dans 0x02.
- Registre 0x02 recomposé à chaque fois : `phase << 7 | champA << 5 | champB << 3 | fc`.
- D'après une bibliothèque Arduino tierce dérivée de la datasheet ([BD37534FV.h](https://github.com/AnatolyNevzoroff/AMPLIFIER_BD37534FV_TDA7293)),
  fc = 0/1/2/3/4 → OFF / 55 / 85 / **120 Hz** / 160 Hz : le caisson est donc filtré en **passe-bas fixe à 120 Hz**.
  La position exacte des autres champs (sortie caisson, mesure de niveau) diffère entre cette bibliothèque et
  le code Jancar, et la datasheet officielle n'a pas pu être consultée : **à vérifier**.
- La valeur **0x00 écrite dans 0x2C** est hors de la plage +15..−79 dB de cette bibliothèque (0x71..0xCF) :
  coupure probable, **non vérifiée**.

### Balance / fader (`setBalanceFade`, id 24)
- Entrées 0..60 (30 = centre), bridées à 60. Pour chaque haut-parleur, un indice 0..30 est calculé
  (30 = plein volume ; on retire la distance `sqrt(dx² + dy²)` au haut-parleur opposé à la direction choisie).
- Indice → dB par une table : 0 → −79, 1 → −60, 2 → −50, 3 → −40, 4 → −37, 5 → −34, 6 → −29, 7 → −26, 8 → −23,
  9..28 → −20..−1 dB, 29 → 0 dB, 30 → +1 dB.
- Écrit dans 0x28, 0x29, 0x2A, 0x2B (fader avant 1, avant 2, arrière 1, arrière 2). La puce sait régler ces
  4 voies **indépendamment**, mais ivi-services ne l'expose qu'à travers balance/fader.

### `setChipParam` (transaction 51) sur BD37534
Trois commandes seulement : 10 → gain d'entrée (0x06), 14 → décalage du volume, 42 → gain caisson. **Pas
d'écriture de registre arbitraire.**

### Pistes
- En root, un client peut écrire directement sur `/dev/i2c-6` @0x40 pour ce qu'ivi-services n'expose pas
  (niveau de chaque haut-parleur, fc du caisson 55/85/160 Hz, phase). ivi-services réécrit ces registres à
  chaque changement de balance ou de caisson : il faut les réappliquer après lui.
- Sur une ROM sans ivi-services (LineageOS), cette table suffit pour écrire un pilote du BD37534.
