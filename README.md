# SSH KeepAlive

Android-App (Kotlin, Jetpack Compose), die eine vom Nutzer gestartete SSH-Sitzung dauerhaft offen hält –
z. B. für den StudNET-Login im Wohnheim (Studentenwerk Leipzig: `ssh <Mieternummer>@139.18.143.253`).

## Funktionen
- Echte SSH-Verbindung (Passwort / keyboard-interactive) über [JSch (mwiede-Fork)](https://github.com/mwiede/jsch)
- Hostschlüssel-Prüfung: unbekannter Server → Fingerprint (SHA256, wie `ssh-keygen -lf`) wird angezeigt und muss bestätigt werden; geänderter Schlüssel → Warnung
- Keepalive alle 30 s, Abbruch-Erkennung nach 3 unbeantworteten Keepalives
- Automatische Wiederverbindung mit exponentiellem Backoff (2 s … 5 min, mit Jitter)
- Netzwerkwechsel WLAN ↔ mobile Daten wird erkannt; Option „Nur über WLAN“ bindet die Verbindung gezielt ans WLAN
- Sichtbarer Vordergrunddienst (`specialUse`) mit dauerhafter Benachrichtigung und STOP-Aktion
- Zugangsdaten AES-256-GCM-verschlüsselt mit Schlüssel im Android Keystore; keine Backups, keine Logs
- Es werden keine Befehle ausgeführt und keine Eingaben an den Server gesendet – es wird nur (wie bei `ssh user@host`) ein Sitzungskanal geöffnet

## Grenzen
Android bzw. Hersteller-Energiesparfunktionen können Hintergrunddienste trotzdem beenden. Eine garantierte
24/7-Laufzeit gibt es nicht. Akku-Optimierung für die App zu deaktivieren hilft deutlich.
Der Dienst hält während der Verbindung einen Partial-Wakelock, damit Keepalives auch bei gesperrtem Bildschirm
gesendet werden – das kostet etwas Akku.

## Bauen
```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk (mit Debug-Key signiert)
./gradlew testDebugUnitTest   # Unit-Tests; SshIntegrationTest braucht SSH_TEST_HOST/USER/PASS/FPS
```
Die GitHub-Actions-Pipeline (`.github/workflows/build.yml`) baut die APK, testet gegen einen lokalen
OpenSSH-Server und legt APK + Prüfberichte auf den Branch `build-output`.

Voraussetzungen: JDK 17, Android SDK 36. Mindestversion: Android 8.0 (API 26).
