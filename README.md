# JacMCU — app Android pour le MCU Jancar (UJC201 / AC8257)

Lit l'état du MCU, envoie des commandes et affiche le trafic série. Le protocole est décrit dans
[`docs/mcu_firmware.md`](docs/mcu_firmware.md).

## Modes
| | Service Jancar (sans root) | Root |
|---|---|---|
| Envoi | `ICar.sendPassthroughData` (transaction 20) : ivi-services construit la trame et gère l'ACK | `jacbridge` (via `su`) écrit la trame sur `/dev/ttyS1` en un seul `write()`, sans reconfigurer le port |
| États | callbacks `ICarCallback` (version, ACC, frein, feux, touches) + getters | trames décodées |
| Trames brutes | journal `JLOG` d'ivi-services si `READ_LOGS` est accordée | journal `JLOG` via `su logcat` ; option « lecture directe » du port |
| Spécifique | touches brutes (`setADKey`), capture CAN `0x10` | — |

Le journal d'ivi-services doit être activé (bouton « Activer journal ivi-services », réglage `global_mcudatadebug`).
En mode service sans root : `adb shell pm grant com.dokt.jacmcu android.permission.READ_LOGS`, puis relancer l'app.

**Lecture directe** (root) : jacbridge lit aussi le port, donc ivi-services ne reçoit plus les octets lus
(ACC, feux, version...). À réserver à un test court, voiture à l'arrêt.

## Onglets
- **État** : version, ACC, frein à main, feux, mute, rétroéclairage, antenne, date/heure MCU, dernière touche.
- **Commandes** : mute, antenne, REM ampli, PWM, LED de façade, seuils de tension, heure, apprentissage des touches,
  vitesse du boîtier CAN.
- **Console** : trame hexa libre (`CMD données`, la checksum est ajoutée), requêtes `F0`, journal décodé, export.
- **Simulation** : injecte *localement* des trames MCU → SoC (ACC, frein, feux, touches volant, version/heure,
  séquence de démarrage `PC_READY`, trame libre) dans le décodeur et le journal, sans rien écrire sur `/dev/ttyS1`.
  Pour tester l'interface et le décodage sans la voiture. Les vrais ACC / frein / feux sont lus par le MCU sur ses
  broches (voir `docs/mcu_firmware.md` §3) : ils ne se simulent pas par la liaison série.
- **Infos** : environnement, état du journal ivi-services, aide.

Sécurité : `80` (bootloader) est bloqué ; `01` (extinction), `0E` (reset SoC) et `F1` (veille) demandent une
confirmation.

## Build
```sh
./build.sh                    # ANDROID_HOME, ou ANDROID_JAR + AAPT2 + (D8 | DX_JAR)
```
Sortie : `out/JacMCU.apk` (arm64, Android 6+, cible API 28). La clé de signature
`jacmcu.keystore` est créée au premier build et n'est pas versionnée : garder la même pour les mises à jour.

## Reverse engineering du MCU
- [`docs/mcu_firmware.md`](docs/mcu_firmware.md) : référence complète du MCU (brochage, alimentation, protocole,
  interfaçage Android, mise à jour, voies de secours).
- [`tools/mcu/`](tools/mcu) : désassemblage/analyse du firmware (`mdis.py`, `ana.py`, `ana2.py`), `jacmcu.py`
  (trames, décodage de `/tmp/mcu.log`, moniteur série) et `swd/hk32c030-readonly.cfg` (OpenOCD, lecture seule via
  un Raspberry Pi 2/3).

Copiés depuis [android_device_alps_ac8257_demo](https://github.com/LibreHU/android_device_alps_ac8257_demo)
(commit `917417c`), où se trouvent aussi `touchfix` et l'intégration TWRP.
