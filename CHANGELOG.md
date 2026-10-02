# Changelog

## v1.0.0
- App Android **MCU Toolkit** (ex-JacMCU) : états, commandes, console du MCU Jancar (modes service Jancar / root).
- Onglet **Simulation** : injection locale de trames MCU → SoC (sans écriture sur le port).
- **Multi-langues** FR / EN (sélecteur AUTO / FR / EN dans l'en-tête).
- **Versionnement** dérivé de git (`versionName` = `git describe --tags`, `versionCode` = nombre de commits).
- **CI GitHub Actions** : build de l'APK, artefact versionné, Release sur tag `v*`.
- Documentation et outils de reverse engineering du MCU (`docs/mcu_firmware.md`, `tools/mcu/`).
