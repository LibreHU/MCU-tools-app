# MCU Jancar (HK32C030) : fonctionnement et interfaçage

Analyse du firmware `JCST_AC8257_8T7-2024.08.09_12:59` (`/vendor/data/mcu/JCST_AC8257_8T7/jacmcu.bin`, 17 296 octets)
et du service Android qui le pilote (`ivi-services.apk`, `com.jancar.services`, protocole `JAC_V1`).
Methode : desassemblage / decompilation statique (capstone, angr) + decompilation jadx de l'APK + trames reelles
capturees sur l'appareil. Le firmware n'est pas dans le depot (fichier constructeur).
Outils : `tools/mcu/` (`JACMCU=jacmcu.bin python3 tools/mcu/mdis.py 0x08003164 80`) et `tools/mcu/jacmcu.py`.

Niveau de confiance : **[V]** verifie sur l'appareil, **[F]** lu dans le firmware, **[A]** lu dans l'APK,
**[D]** deduit (coherent mais non prouve).

## Sommaire
1. [En bref : comment parler au MCU](#1-en-bref)
2. [Materiel, memoire, horloges](#2-materiel)
3. [Brochage deduit](#3-brochage)
4. [Architecture du firmware](#4-architecture)
5. [Machine d'etat d'alimentation](#5-alimentation)
6. [Protocole serie](#6-protocole)
7. [Configuration persistante](#7-configuration)
8. [Touches (volant, facade, molette)](#8-touches)
9. [LED de facade et PWM](#9-led)
10. [Passerelle boitier CAN](#10-can)
11. [Mise a jour du firmware](#11-maj)
12. [Cote Android (ivi-services)](#12-android)
13. [Interfacer : app Android, TWRP/Linux, PC](#13-interfacer)
14. [Divergences, limites, risques](#14-limites)

---

<a id="1-en-bref"></a>
## 1. En bref

- Lien : **`/dev/ttyS1` du SoC <-> USART2 du MCU, 115200 8N1** [V]. Trame `EE FA LEN CMD DATA.. CS`
  (`LEN` = octets de donnees + 1, `CS` = somme 8 bits de tous les octets precedents) [V].
- Le MCU acquitte **chaque** trame recue par `C0 <cmd> <d0> <d1>` (2 octets de donnees max recopies) [V].
- Au demarrage du SoC, envoyer **`1F 01`** (PC_READY). Le MCU repond ACC (`00`), puis la version (`0A`, +200 ms) et
  la date/heure (`09`, +400 ms) [V][F]. Il n'y a **aucun battement de coeur** a entretenir [F][A].
- Etats a la demande : **`F0 qq 00`** (`qq` = 00 ACC, 04 frein a main, 08 mute, 09 heure, 0A version, 0B feux,
  0D retroeclairage, 43 radio, `0F 07` option) [F].
- Sous Android, le port est deja ouvert par `ivi-services` : une app tierce passe par l'AIDL
  **`com.jancar.services.car.ICar`** (`sendPassthroughData`, `setCmdParam`, callbacks) au lieu d'ouvrir le port
  (section 13).

<a id="2-materiel"></a>
## 2. Materiel, memoire, horloges

| Element | Valeur |
|---|---|
| MCU | HK32C030C8T7 (Cortex-M0, clone STM32F030), 64 Ko flash, 8 Ko RAM [D] |
| Horloge | HSI -> PLL **48 MHz** (`SystemCoreClock = 0x02DC6C00`) [F] |
| SysTick | 100 us (48 MHz / 10 000) ; compteur 1 ms derive (`0x2000003C`) [F] |
| Chien de garde | IWDG interne, rafraichi toutes les 1 s par la boucle principale [F] |
| Flash `0x08000000-0x08001FFF` | **bootloader** de mise a jour (8 Ko, absent du fichier `.bin`) [F] |
| Flash `0x08002000-0x080023FF` | **page de configuration** (32 octets utiles, section 7) [F] |
| Flash `0x08002400-...` | **application** (= `jacmcu.bin` sans son en-tete de 16 octets) [F] |
| RAM | `.data` 0x60 octets en 0x20000000, `.bss` jusqu'a 0x20000BA8, pile en haut [F] |
| Table des vecteurs | en 0x08002400 ; pas de VTOR sur M0 : le bootloader la recopie en SRAM et remappe [D] |

En-tete du fichier : `JCST_AC8257_8T7 ` (16 octets ASCII), retire avant l'envoi au MCU [A][F].
Une variante `JCST_AC8257_N01` (2024.07.19) existe pour une autre carte ; non analysee.

<a id="3-brochage"></a>
## 3. Brochage deduit

### Entrees numeriques (filtrees, echantillonnees toutes les 10 ms) [F]
| Index | Broche | Filtre | Role | Effet |
|---|---|---|---|---|
| 0 | PC13 | 200 ms | **Frein a main** (actif bas) | trame `04` a chaque changement |
| 1 | PA11 | 200 ms | entree de veille [D] | autorise l'arret immediat apres coupure ACC |
| 2 | PB8 | 200 ms | **ILL / feux** (actif bas) | LED de facade + trame `0B` |
| 3 | PB9 | 1 s | **ACC** (actif bas) ; aussi EXTI9 = reveil du mode STOP | machine d'etat d'alimentation |
| 4 | PA12 | 50 ms | **marche arriere** [D] (fil BACK) | commute PB12/PB13 (camera) |

### Sorties [F]
| Index | Broche | Fonction (deduite des appels) |
|---|---|---|
| 0 | PA0 | **mute** ampli (cmd `08`, requete `F0 08`) |
| 1 | PB2 | impulsion de mise sous tension / reveil du SoC [D] |
| 2, 3 | PB10, PB11 | selection de la resistance de rappel des touches volant (3 echelles) [D] |
| 4 | PA15 | **retroeclairage** ecran (requete `F0 0D`) [D] |
| 6, 7 | PB4, PB5 | rails d'alimentation du SoC (coupes en veille profonde) [D] |
| 8 | PF6 | **alimentation antenne radio** (cmd `43`) |
| 9 | PB3 | **REM ampli externe** (cmd `44`) |
| 10 | PF7 | validation LED de facade |
| 13, 14 | PB12, PB13 | commutation camera / video de recul [D] |

### Analogique (ADC + DMA, 7 voies en continu, valeurs 12 bits ramenees a 8 bits) [F]
| Index | Voie | Broche | Usage |
|---|---|---|---|
| 0 | CH1 | PA1 | **tension batterie** (~6,9 points/V sur 8 bits, soit ~1/11 de pont diviseur) |
| 1 | CH4 | PA4 | touches facade (canal 3) |
| 2 | CH5 | PA5 | touches facade (canal 4) |
| 3 | CH6 | PA6 | **touches volant KEY1** (canal 5) |
| 4 | CH7 | PA7 | **touches volant KEY2** (canal 6), seulement si l'octet materiel `0x0800668B` >= 2 |
| 5 | CH8 | PB0 | molette analogique |
| 6 | CH9 | PB1 | molette analogique (sinon PWM bleu selon le type de facade) |

### Peripheriques [F]
| Bloc | Broches | Usage |
|---|---|---|
| USART2 | PA2/PA3 AF1 | **lien SoC** 115200 8N1 |
| USART1 | PA9/PA10 AF1 | **boitier CAN** (vitesse configurable, section 10) |
| TIM1 | PA8 (CH1), PB14 (CH2N), PB15 (CH3N) | PWM luminosite (cfg +8, +9) / LED |
| TIM3, TIM17 | PB1 (TIM3_CH4), PB7 (TIM17_CH1N) | PWM LED |
| PWM logiciel (SysTick) | PB15, PB7, PA13+PB1 | LED RGB de facade, periode 2,6 ms |
| RTC | - | horloge du MCU (trame `09`) et reveils de la veille |
| EXTI9 | PB9 | reveil sur ACC depuis le mode STOP |

PA13 (SWDIO) est reutilise en sortie LED : le SWD n'est accessible que "connect under reset".

<a id="4-architecture"></a>
## 4. Architecture du firmware

`main()` (0x080065C8) [F] :
1. flash / horloges, attente 500 ms ;
2. chargement de la configuration (page 0x08002000) ;
3. GPIO, UART (SoC + CAN), IWDG, ADC+DMA, SysTick, PWM/LED, alimentation, RTC, molette ;
4. envoi de 4 octets bruts `01 02 03 04` vers le SoC (hors trame) ;
5. boucle : ordonnanceur, vidage de la file d'emission, conversion ADC, gestion d'alimentation.

Ordonnanceur (base 1 ms, 0x08002FDC) [F] :
| Periode | Taches |
|---|---|
| 2 ms | balayage des touches (volant, facade), passerelle CAN -> SoC |
| 5 ms | etats des touches / apprentissage |
| 10 ms | filtrage des entrees numeriques (section 3) |
| 100 ms | expiration de reception, sauvegarde differee de la config, **machine d'alimentation**, cycle LED |
| 1 s | rafraichissement IWDG |

Interruptions : SysTick (compteurs + PWM logiciel LED + clignotement), USART1/USART2 (files de reception),
DMA1 canal 1 (ADC), EXTI4_15 (reveil ACC), RTC (reveil periodique en veille).

<a id="5-alimentation"></a>
## 5. Machine d'etat d'alimentation (tache 100 ms) [F]

Structure en `0x200002B4` : etat (`+1`), demande SoC (`+0` quartet bas : 1 = PC_READY recu, 2 = extinction demandee),
mode veille (`+0` quartet haut), minuterie de veille (`+8`), compteurs.

| Etat | Nom | Comportement |
|---|---|---|
| 0 | arret, attente ACC | si ACC present et tension < seuil haut : demute, alimente le SoC -> etat 1. Sans ACC pendant 2 s -> etat 4 |
| 1 | demarrage | **a froid** : rail SoC a +1,5 s, attente de `1F 01` (15 min max) ; ACC perdu -> etat 4. **A chaud** (sortie de veille) : impulsion PB2, octet brut `DD`, attente de `1F 01` 10 s max |
| 2 | marche | **surtension** : tension > seuil haut pendant 3 s -> envoie `00 00` (ACC coupe) -> etat 3. ACC coupe (filtre 1 s) -> `00 00` -> etat 3 |
| 3 | ACC coupe | ACC revient (et tension correcte) -> `00 01` -> etat 2. Sinon coupure du SoC au bout de 15 s s'il tourne encore, 40 s sinon -> etat 4 (sauvegarde de la config) |
| 4 | veille / arret | octet brut `11`, sorties coupees. Si une **duree de veille** (`F1`) est active : SoC maintenu en suspension, reveils RTC, decompte par minute ; batterie < seuil bas 20 fois (500 ms) -> arret complet. Mode STOP, reveil par ACC (EXTI9) -> etat 0 |

Evenements cote SoC :
- `1F 01` (PC_READY) : etat "SoC pret", demute, retroeclairage, renvoie `00 01` si ACC present, puis version (+200 ms)
  et date/heure (+400 ms) [V].
- `01 tt ..` avec `tt` = 3, 4 ou 5 : extinction demandee, decompte de 15 min (900 x 100 ms) puis coupure ;
  **`tt` = 2 (ce qu'Android envoie a la coupure ACC) est ignore** par ce firmware [F][A].
- `0E` : reset du SoC (octet brut `AB`, coupure puis remise sous tension des rails).
- `F1 n` : duree de veille = n x 420 min (Android calcule n en tranches de 6 h) [F][A].

Seuils de tension (ADC 8 bits, ~6,9 points/V) : bas 62..69 (9 / 9,5 / 10 V), haut 111..135 (16..20 V) ; defauts
**9 V et 16 V** [F].

Octets bruts hors trame emis vers le SoC (ignores par Android, utiles au debogage) : `01 02 03 04` (boot),
`11` (veille), `AA`/`BB` (marche arriere), `AB` (reset SoC), `AC` (reveil), `AD`/`BB` (fin de decompte),
`DD` (demarrage a chaud), octets de calibration de la molette [F].

<a id="6-protocole"></a>
## 6. Protocole serie

### 6.1 Trame (identique dans les deux sens)
```
EE FA LEN CMD D0 .. Dn-1 CS     LEN = n + 1     CS = (EE + FA + LEN + CMD + D0 + .. + Dn-1) & 0xFF
```
- Reception MCU (0x080030D0) : automate octet par octet, tampon circulaire de 300 octets, donnees <= 130 octets,
  abandon apres 1 s sans octet [F].
- **ACK** : toute trame valide (sauf `C0`) est acquittee par `C0 <cmd> <D0> <D1>` (au plus 3 octets) [V][F].
- Emission MCU : trames construites directement (fonction 0x08003584) [F].
- Android : lecture/ecriture sur 3 threads, renvoi apres 500 ms sans ACK (5 fois) pour `1F`, `08`, `01`, `F1`, `80`
  [A]. **Android plante sur une trame MCU avec `LEN` >= 0x80** (octet signe) [A].

Exemples : `EE FA 02 1F 01 0A` (PC_READY), `EE FA 03 F0 00 00 DB` (ACC ?), `EE FA 03 F0 0B 00 E6` (feux ?).

### 6.2 SoC -> MCU (dispatcher 0x08003164)
| Cmd | Donnees | Effet dans le firmware [F] | Usage Android [A] |
|---|---|---|---|
| `01` | `[type, ..]` | type 3..5 : extinction differee (15 min max). Autres : ignore | type 2 a la coupure ACC (ignore), type 3 reset usine / recovery |
| `08` | `[0/1]` | mute (PA0) | demute au boot, mute autour d'ACC / reboot |
| `09` | `[0, aa/100, aa%100, mois, jour]` / `[1, h, m, s]` | regle la RTC (valeurs bornees) | chaque minute, apres la 1re synchro venue du MCU |
| `0E` | `[0,0,0]` | reset du SoC | `PowerUtil.reboot()` |
| `0F` | `[sous-cmd, ..]` | configuration (6.4) | LED, tensions |
| `10` | octets | recopies tels quels sur USART1 (boitier CAN) | appli canbus |
| `11` | `[2]` / autre | apprentissage touches volant : debut / fin | appli d'apprentissage |
| `1F` | `[01]` | PC_READY (section 5) | boot, ACC on, +4 s si pas de version |
| `21` | `[2]` / autre | apprentissage touches facade / molette : debut / fin | idem |
| `43` | `[0/1]` | alimentation antenne radio (PF6) | reglage antenne |
| `44` | `[0/1]` | REM ampli externe (PB3) | reglage ampli (aussi "ventilateur") |
| `80` | `[00 ..]` | passe au **bootloader** (section 11) | mise a jour MCU |
| `F0` | `[qq, 00]` | requete (6.3) | `F0 00`, `F0 04` |
| `F1` | `[n]` | duree de veille n x 420 min | minuterie "Smart ACC off" |
| `31`, `33`, `45`, `0F 05`, `0F 0C` | - | **non geres** par ce firmware | envoyes par Android (IR, echelle volant, rotation ecran, molette, type de facade) |

### 6.3 Requetes `F0 qq 00` (0x080033C4) [F]
| `qq` | Reponse | Contenu |
|---|---|---|
| `00` | `00 [acc]` | 1 = ACC present |
| `04` | `04 [hb]` | 1 = frein a main serre |
| `08` | `08 [m]` | etat de la sortie mute |
| `09` | `09 ..` x2 | date puis heure |
| `0A` | `0A "JCST_..."` | version (32 octets ASCII) |
| `0B` | `0B [ill]` | 1 = feux / ILL actifs (SoC en marche) |
| `0D` | `0D [b]` | etat sortie retroeclairage (PA15) |
| `0F 07` | `0F [07, v]` | option de config `+0x11` |
| `43` | `43 [r]` | etat alimentation radio |

### 6.4 Sous-commandes `0F` (0x0800334C) [F]
| Sous-cmd | Donnees | Effet | Config |
|---|---|---|---|
| `00` | `[i]` | vitesse USART1 (CAN) : 9600 / 19200 / 38400 / 57600 / 115200 / 230400 / 460800 (i = 0..6) | `+0x14` |
| `02` | `[p]` | luminosite PWM TIM1 CH1 (PA8), 5..100 %, defaut 60 | `+0x08` |
| `03` | `[p]` | luminosite PWM TIM1 CH2 (PB14), 5..100 %, defaut 50 | `+0x09` |
| `04` | `[type, R, G, B, mode]` | LED de facade : type (1 = facade LED couleur), R/G/B 0..99, mode 1 auto / 2 manuel / 3 semi-auto (section 9) | `+0x0A..+0x0E` |
| `06` | `[a, b]` | parametres des deux voies de la molette | RAM |
| `07` | `[v]` | option `+0x11` (relue par `F0 0F 07`), defaut 1 [D] | `+0x11` |
| `08` | `[type]` | type de facade seul (reinitialise ADC / GPIO / PWM) | `+0x0A` |
| `0A` | `[i]` | seuil **haut** : 16 / 16,5 / 17 / 18 / 19 / 20 V (i = 0..5) | `+0x10` |
| `0B` | `[i]` | seuil **bas** : 9 / 9,5 / 10 V (i = 0..2) | `+0x0F` |
| `01`, `05`, `09`, >= `0C` | - | ignores | - |

### 6.5 MCU -> SoC [F][V]
| Cmd | Donnees | Quand |
|---|---|---|
| `00` | `[acc]` | apres PC_READY, a la coupure / au retour ACC, sur requete |
| `04` | `[hb]` | changement du frein a main, sur requete |
| `09` | `[0, 20, aa, mm, jj]` puis `[1, h, m, s]` | +400 ms apres PC_READY, sur requete |
| `0A` | 32 octets ASCII, ex. `JCST_AC8257_8T7-2024.08.09_12:59` + espaces | +200 ms apres PC_READY, sur requete |
| `0B` | `[ill]` | changement des feux, changement d'etat d'alimentation, sur requete |
| `0D`, `08`, `43`, `0F` | `[v]` | uniquement sur requete |
| `10` | jusqu'a 128 octets | donnees recues du boitier CAN (section 10) |
| `20` | `[ch, v1, v2, v3, v4]` | touche (section 8) |
| `30` | `[ch, v1, v2, v3, v4]` | touche en mode apprentissage |
| `C0` | `[cmd, d0, d1]` | acquittement |

Le firmware n'envoie **jamais** `1F` (etat groupe), `52` (rotation) ni la tension batterie, bien qu'Android
sache les traiter [F][A].

<a id="7-configuration"></a>
## 7. Configuration persistante (page flash 0x08002000, copie RAM 0x20000094) [F]

| Offset | Taille | Contenu | Defaut / bornes |
|---|---|---|---|
| `+0x00` | 4 | `0x00504149` ("IAP") = demande de bootloader | 0 |
| `+0x04` | 4 | marqueur de validite `22032510` (sinon valeurs par defaut) | - |
| `+0x08` | 1 | luminosite PWM 1 (`0F 02`) | 60 (5..100) |
| `+0x09` | 1 | luminosite PWM 2 (`0F 03`) | 50 (5..100) |
| `+0x0A` | 1 | type de facade (`0F 04`, `0F 08`) | 1 |
| `+0x0B` | 1 | mode LED | 2 (manuel) |
| `+0x0C..0E` | 3 | couleur LED R, G, B | - |
| `+0x0F` | 1 | seuil bas (ADC 8 bits) | 62 = 9 V (62..69) |
| `+0x10` | 1 | seuil haut (ADC 8 bits) | 111 = 16 V (111..135) |
| `+0x11` | 1 | option `0F 07` | 1 |
| `+0x14` | 4 | vitesse USART1 (CAN) | 38400 si invalide |
| `+0x19..1B` | 3 | calibration de la molette | - |

Toute modification est ecrite **3 s plus tard** (effacement de la page + 8 mots) pour limiter l'usure de la flash.

<a id="8-touches"></a>
## 8. Touches

| Canal | Source | Trame `20` / `30` |
|---|---|---|
| 5 | volant KEY1 (PA6) | appui : `[5, AA, bas, moyen, haut]` (3 mesures avec 3 resistances de rappel commutees par PB10/PB11) ; relache : `[5, AA, FF, FF, FF]` |
| 6 | volant KEY2 (PA7) | idem, canal 6 |
| 3, 4 | facade (PA4, PA5) | appui : `[ch, valeur, FF, FF, FF]` ; relache : `[ch, FF, ..]` |
| 2 | molette analogique (PB0/PB1), auto-calibree (4 positions) | `[2, id, sens, FF, FF]` |
| 1 | telecommande IR | **absente de ce firmware** (aucun decodeur) |

- Detection : valeur < reference - 20 ; 9 echantillons (3 par echelle) avant envoi ; repetition tant que la touche
  est tenue [F].
- Pendant l'apprentissage (`11 02` / `21 02`), les memes trames sortent en `30` au lieu de `20` [F].
- Android associe les plages apprises (`/jancar/config/ivi-studykey.ini`) a des actions (section 12) [A].
- Si la voiture passe par un **boitier CAN**, les touches arrivent dans les trames `10` (protocole du boitier),
  pas en `20` [D].

<a id="9-led"></a>
## 9. LED de facade et PWM [F]

- Couleur R/G/B 0..99 ramenee sur 26 pas de PWM logiciel (PB15, PB7, PA13+PB1) ; facade "couleur" : bleu aussi
  sur TIM3.
- Mode 1 (**auto**) : LED toujours allumees, cycle de 7 couleurs (rouge, vert, bleu, cyan pale, jaune, blanc,
  vert fonce), 1 s par couleur.
- Mode 2 (**manuel**) : couleur fixe, allumees seulement si ILL actif (feux) et SoC en marche.
- Mode 3 (**semi-auto**) : cycle de couleurs, seulement si ILL actif.
- Chaque changement d'etat envoie `0B` au SoC.

<a id="10-can"></a>
## 10. Passerelle boitier CAN [F]

- USART1 (PA9/PA10), vitesse `0F 00` (defaut 38400).
- Octets recus -> tampon circulaire de 512 octets -> envoyes au SoC en trames `10` d'au plus 128 octets, ~20 ms
  apres le dernier octet recu.
- Trames `10` du SoC -> octets recopies tels quels vers le boitier.
- Le MCU ne decode rien : le protocole du boitier (Raise, Hiworld, Simple...) est gere par l'appli Android
  `com.jancar.canservice` (`/jancar/config/can_config.json`).

<a id="11-maj"></a>
## 11. Mise a jour du firmware

- `80 00 ..` : l'application verifie qu'un bootloader est present (pile valide en 0x08000000), ecrit "IAP" dans la
  page de config, coupe les interruptions et les peripheriques, puis saute au bootloader [F].
- Le bootloader (non disponible) dialogue ensuite avec Android [A] : `80 [01 ..]` pret ; Android `80 [05 NH NL]`
  (nombre de blocs) ; le MCU demande chaque bloc `80 [06 BH BL]` ; Android repond `83 [BH BL + 128 octets]` ;
  fin `80 [03]` (OK) ou `80 [04]` (echec).
- Android reflashe automatiquement `/jancar/mcu/jacmcu.bin` 10 s apres PC_READY si la version recue est vide ou
  sans `-` [A].
- Il n'existe **aucune commande de lecture** de la flash : le firmware en place ne peut pas etre sauvegarde par le
  port serie. Le bootloader reste en place, donc une mise a jour ratee se rattrape normalement en relancant la
  mise a jour ; sinon, SWD (connect under reset) [D].
- Voies de secours de la puce (manuel utilisateur HK32C030 V2.2/V2.6, datasheet V1.3, pack Keil DFP 1.0.5 du SDK) :
  - **ROM bootloader** (System memory 0x1FFFEC00, 3 Ko, non effacable) : BOOT0 = PF8 (broche 44 en LQFP48,
    rappel au 0 interne de 50 kOhm) a 1 au reset -> reprogrammation par UART1 PA9/PA10 (= liaison boitier CAN de
    cette carte) ou UART2 PA14/PA15. Protocole non documente (compatibilite AN3155 non verifiee).
    Inaccessible si les option bytes ont reaffecte PF8 en GPIO (BOOT_SEL = 0xADBC, nBOOT_BIT_SEL = 0) ou en RDP
    niveau 2. Le firmware applicatif ne configure pas PF8 (GPIOF : PF6/PF7 seulement) et ne touche pas aux
    option bytes (pas de cle OPTKEYR) [F] ; leur etat reel reste inconnu.
  - **SWD** PA13/PA14 (non reconfigures par l'appli [F]) : algorithmes `HK32C030xx_FLASH.FLM` / `_OPT.FLM` du pack
    Keil (utilisables par pyOCD / Keil). RDP niveau 0 : lecture libre (sauvegarde complete possible, bootloader
    Jancar compris). Niveau 1 (valeur apres effacement des option bytes) : flash illisible en SWD / ROM bootloader,
    seul l'effacement total est possible (perd le bootloader Jancar, a remplacer par un petit lanceur vers
    0x08002400). Niveau 2 : SWD et ROM bootloader definitivement bloques.

<a id="12-android"></a>
## 12. Cote Android (ivi-services, `com.jancar.services`) [A]

- `CarService` ouvre `/dev/ttyS1` (code natif `libJanCarIVI.so`, 115200) au demarrage ; protocole `JAC_V1`.
- Evenements produits :
  - ACC : `ICarCallback.onAccChanged`, broadcast **`com.jancar.services.action.acc`** (extra `acc`),
    `Settings.Global accStatus` ;
  - frein a main : `onHandbrakeChanged` (restrictions video) ;
  - feux : `onHeadLightChanged` (luminosite de nuit, CarPlay) ;
  - version : `onMcuVersion` ; touches : `StudyKeyManager` -> actions `[RemoteControl]` ou `onADKeyChanged` ;
  - CAN : `IPassthroughDataCallback.onPassthroughData` (un seul client, l'appli canbus).
- Heure : accepte l'heure du MCU une fois par demarrage (et seulement vers l'avant), puis renvoie l'heure Android
  au MCU chaque minute.
- Fichiers de reglage : `ivi-config.ini` (`[Power] SHUTDOWN_*_VOLTAGE`, `[DateTime] SyncMcu`, ...),
  `ivi-customer.ini` (`PanelConfigID`), `ivi-studykey.ini` (touches apprises).
- Detail complet (fichiers / lignes jadx) : rapport d'analyse de l'APK, resume dans ce document.

<a id="13-interfacer"></a>
## 13. Interfacer

### 13.1 App Android (sans root) : AIDL `ICar`
Le port etant deja ouvert par `ivi-services`, **ne pas l'ouvrir une seconde fois** (deux lecteurs se volent les
octets). Passer par le service :

```java
// Liaison : action "com.jancar.services.action.car", paquet "com.jancar.services"
Intent i = new Intent("com.jancar.services.action.car").setPackage("com.jancar.services");
context.bindService(i, conn, Context.BIND_AUTO_CREATE);

// Dans onServiceConnected(name, IBinder b) : appels "bruts" sans les classes AIDL
static final String ICAR = "com.jancar.services.car.ICar";
static void sendToMcu(IBinder b, int cmd, byte[] data) throws RemoteException {   // transaction 20
    Parcel in = Parcel.obtain(), out = Parcel.obtain();
    try { in.writeInterfaceToken(ICAR); in.writeByte((byte) cmd); in.writeByteArray(data);
          b.transact(20, in, out, 0); out.readException(); }
    finally { in.recycle(); out.recycle(); }
}
static String mcuVersion(IBinder b) throws RemoteException {                       // transaction 1
    Parcel in = Parcel.obtain(), out = Parcel.obtain();
    try { in.writeInterfaceToken(ICAR); b.transact(1, in, out, 0); out.readException(); return out.readString(); }
    finally { in.recycle(); out.recycle(); }
}
// ex. : sendToMcu(b, 0xF0, new byte[]{0x0B, 0});   // demande l'etat des feux
//       sendToMcu(b, 0x0F, new byte[]{4, 1, 0, 99, 0, 2});  // LED vertes, mode manuel
```

| Transaction | Methode | Usage |
|---|---|---|
| 3 | `registerCallback(ICarCallback)` | evenements ACC / frein / feux / version / touches |
| 7 / 8 | `register/unRegisterPassthroughDataCallback` | recevoir les trames `10` (prend la place de l'appli canbus) |
| 20 | `sendPassthroughData(byte cmd, byte[] data)` | **envoyer n'importe quelle trame** |
| 34 | `setCmdParam(int cmd, byte[] data)` | idem |
| 27 | `upgradeMcu(String path, IMcuUpgradeCallback)` | mise a jour du MCU |
| 46 | `setADKey(int, int)` | 161 = touches normales, autre = touches brutes vers `onADKeyChanged` |
| 1 / 2 / 11 / 14 / 66 | `getProtocolMcuVersion` / `getCarId` / `getHandbrakeStatus` / `getHeadLightStatus` / `getProtocolCanVersion` | lectures |
| 52 | `isAccOn()` | **bouchon : renvoie toujours `false`** dans ivi-services 3.0.0 ; utiliser `onAccChanged` ou le broadcast |

`CarService` est exporte sans permission et ne verifie pas l'appelant (manifeste et `onBind` de l'APK 3.0.0).
Pour les callbacks (`ICarCallback`, `IPassthroughDataCallback`), recopier les interfaces AIDL depuis l'APK
decompile (meme nom de paquet, meme ordre des methodes) ou repondre aux codes de transaction (1..34) dans un
`Binder`. Sans liaison : ecouter le broadcast `com.jancar.services.action.acc` (extra booleen `acc`).

Trames brutes sans toucher au port : si le reglage `global_mcudatadebug` vaut `true` (fournisseur
`content://com.jancar.settings.provider/settings`), ivi-services journalise sous le tag `JLOG` chaque trame recue
(`CmdId = 0x0b, Data = | 01 |`, ACK exclus) et envoyee (`send: [ee fa 02 1f 01 0a ]`). Lecture par `logcat`
(root, ou permission `READ_LOGS` accordee par `adb shell pm grant`).

### 13.2 Avec root sous Android
`ivi-services` occupe toujours le port. Ecrire est sans danger si chaque trame part en **un seul `write()`** (le
pilote tty serialise les ecritures) et si l'on ne reconfigure pas le port ; l'ACK du MCU est alors lu par
ivi-services. Lire `/dev/ttyS1` en parallele vole les octets a ivi-services : preferer le journal `JLOG` ci-dessus.
C'est ce que fait l'app JacMCU de ce depot (racine, pont natif `jacbridge` lance par `su`).

### 13.3 TWRP / Linux / PC
Ici rien d'autre n'ouvre le port : acces direct a `/dev/ttyS1` (TWRP : `touchfix`, journal `/tmp/mcu.log`).
`tools/mcu/jacmcu.py` construit / decode les trames :

```sh
python3 tools/mcu/jacmcu.py frame F0 0B 00          # -> EE FA 03 F0 0B 00 E6
python3 tools/mcu/jacmcu.py decode "ee fa 02 04 01 ef"
python3 tools/mcu/jacmcu.py monitor /dev/ttyS1      # PC_READY + requetes, puis affiche les trames (Linux)
```

<a id="14-limites"></a>
## 14. Divergences, limites, risques

- **Divergences Android / firmware** : `01 02` (arret a la coupure ACC) ignore ; `33` (echelle volant), `0F 05`
  (molette), `0F 0C` (type de facade), `31` (IR), `45`/`52` (rotation) sans effet ; `F1` en tranches de 7 h et non
  6 h ; trames `10` de plus de 126 octets fatales au parseur Android.
- **Securite** : `01`, `0E`, `80` et `F1` coupent l'alimentation, redemarrent le SoC ou passent au bootloader ;
  ne pas les envoyer a la main en voiture. Les seuils `0F 0A` / `0F 0B` protegent la batterie : ne pas les
  desactiver.
- **Non verifie** : role exact de PA11, PB2, PB4/PB5, PB12/PB13 et de l'option `+0x11` ; calibration exacte de la
  mesure de tension ; bootloader non analyse (absent du fichier).
