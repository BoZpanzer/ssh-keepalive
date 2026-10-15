#!/usr/bin/env bash
# Unofficial SSH KeepAlive for Linux / Raspberry Pi. Outbound SSH only.
set -Eeuo pipefail
umask 077

BASE="$HOME/.config/ssh-keepalive"
BIN="$HOME/.local/libexec/ssh-keepalive.sh"
SERVICE="ssh-keepalive.service"
UNIT="$HOME/.config/systemd/user/$SERVICE"

say() { printf '%s\n' "$*"; }
fail() { printf 'Fehler: %s\n' "$*" >&2; exit 1; }
need() { local p; for p in "$@"; do command -v "$p" >/dev/null 2>&1 || fail "Fehlendes Programm: $p"; done; }

help_text() {
  cat <<'HELP'
SSH KeepAlive – Linux / Raspberry Pi (inoffiziell)
  bash ssh-keepalive.sh setup      Verbindung und Autostart einrichten
  bash ssh-keepalive.sh start      Dienst starten
  bash ssh-keepalive.sh stop       Dienst beenden
  bash ssh-keepalive.sh status     Status anzeigen
  bash ssh-keepalive.sh logs       Letzte Log-Meldungen
  bash ssh-keepalive.sh uninstall  Dienst und Zugangsdaten entfernen

Debian/Raspberry Pi OS: sudo apt install openssh-client sshpass
Benötigt systemd --user. Passwort lokal im Modus 0600, NICHT verschlüsselt.
Nur für eigene, berechtigte SSH-Konten und Netze.
HELP
}

load_settings() {
  [[ -r "$BASE/host" && -r "$BASE/port" && -r "$BASE/username" && -r "$BASE/password" && -s "$BASE/known_hosts" ]] ||
    fail "Nicht eingerichtet. Nutze zuerst: bash ssh-keepalive.sh setup"
  IFS= read -r HOST < "$BASE/host"
  IFS= read -r PORT < "$BASE/port"
  IFS= read -r USERNAME < "$BASE/username"
}

setup() {
  need ssh sshpass systemctl mktemp cmp cp
  systemctl --user show-environment >/dev/null 2>&1 ||
    fail "systemd --user nicht erreichbar. Melde dich als normaler Benutzer an."
  local host="139.18.143.253" port="22" user="" answer pass confirmed tmp
  if [[ -r "$BASE/host" ]]; then IFS= read -r host < "$BASE/host"; fi
  if [[ -r "$BASE/port" ]]; then IFS= read -r port < "$BASE/port"; fi
  if [[ -r "$BASE/username" ]]; then IFS= read -r user < "$BASE/username"; fi
  say "=== SSH KeepAlive – Einmaliges Setup ==="
  read -r -p "Server [$host]: " answer
  if [[ -n "$answer" ]]; then host="$answer"; fi
  [[ "$host" =~ ^[A-Za-z0-9][A-Za-z0-9.:-]*$ ]] || fail "Ungültiger Server."
  read -r -p "Port [$port]: " answer
  if [[ -n "$answer" ]]; then port="$answer"; fi
  [[ "$port" =~ ^[0-9]{1,5}$ ]] || fail "Ungültiger Port."
  (( 10#$port >= 1 && 10#$port <= 65535 )) || fail "Port außerhalb 1–65535."
  read -r -p "Benutzername [$user]: " answer
  if [[ -n "$answer" ]]; then user="$answer"; fi
  [[ "$user" =~ ^[A-Za-z0-9][A-Za-z0-9_.@-]*$ ]] || fail "Ungültiger Benutzername."
  read -r -s -p "Passwort: " pass
  printf '\n'
  [[ -n "$pass" ]] || fail "Passwort fehlt."

  mkdir -p "$BASE" "$(dirname "$BIN")" "$(dirname "$UNIT")"
  chmod 700 "$BASE"
  tmp="$(mktemp "$BASE/keycheck.XXXXXXXX")"
  if [[ -s "$BASE/known_hosts" ]]; then cp -- "$BASE/known_hosts" "$tmp"; fi
  say "Prüfe die Serveridentität per OpenSSH (nur der angegebene SSH-Server)."
  say "Vergleiche den angezeigten SHA256-Fingerprint unabhängig mit einer vertrauenswürdigen Quelle."
  say "Ein unbekannter oder geänderter Schlüssel darf niemals ungeprüft akzeptiert werden."
  # Ignore the expected auth failure: first connection is only for interactive host-key verification.
  ssh -o StrictHostKeyChecking=ask \
    -o UserKnownHostsFile="$tmp" -o GlobalKnownHostsFile=/dev/null \
    -o UpdateHostKeys=no -o ConnectTimeout=15 -o ConnectionAttempts=1 \
    -o PreferredAuthentications=none -o NumberOfPasswordPrompts=0 \
    -p "$port" -l "$user" -- "$host" </dev/tty >/dev/tty 2>&1 || true
  if [[ ! -s "$tmp" ]]; then rm -f "$tmp"; fail "Kein bestätigter Hostschlüssel vorhanden."; fi
  read -r -p "Fingerprint unabhängig geprüft? Nur JA bestätigt: " confirmed
  if [[ "$confirmed" != "JA" ]]; then rm -f "$tmp"; fail "Abgebrochen."; fi

  systemctl --user stop "$SERVICE" >/dev/null 2>&1 || true
  printf '%s\n' "$host" > "$BASE/host"
  printf '%s\n' "$port" > "$BASE/port"
  printf '%s\n' "$user" > "$BASE/username"
  printf '%s\n' "$pass" > "$BASE/password"
  unset pass
  cp -- "$tmp" "$BASE/known_hosts"
  rm -f "$tmp"
  chmod 600 "$BASE/host" "$BASE/port" "$BASE/username" "$BASE/password" "$BASE/known_hosts"
  if ! cmp -s "$0" "$BIN"; then cp -- "$0" "$BIN"; fi
  chmod 700 "$BIN"

  cat > "$UNIT" <<'SYSTEMD'
[Unit]
Description=SSH KeepAlive - outbound SSH login
After=network.target

[Service]
Type=simple
ExecStart=%h/.local/libexec/ssh-keepalive.sh worker
Restart=no
KillMode=control-group
NoNewPrivileges=true
UMask=0077

[Install]
WantedBy=default.target
SYSTEMD
  chmod 600 "$UNIT"
  systemctl --user daemon-reload
  systemctl --user enable --now "$SERVICE"
  say ""
  say "Einrichtung abgeschlossen! Status: bash ssh-keepalive.sh status"
  say "Bei Raspberry Pi ohne Anmeldung nach Reboot: sudo loginctl enable-linger \"$(id -un)\""
  say "Das Passwort liegt nur lokal mit Dateirechten 0600 vor, ist aber NICHT verschlüsselt."
}

worker() {
  need ssh sshpass mkfifo mktemp
  load_settings
  trap 'exit 0' TERM INT
  local attempt=0 pause=2 rc=0 started=0 folder fifo i
  say "SSH-KeepAlive-Worker gestartet ($HOST:$PORT)."
  while :; do
    folder="$(mktemp -d "$BASE/run.XXXXXXXX")"
    fifo="$folder/empty-input"
    mkfifo -m 600 "$fifo"
    # Reader and writer remain open: SSH gets no keystrokes, but also no EOF.
    exec 3<> "$fifo"
    started="$SECONDS"
    say "Verbinde mit $HOST:$PORT ..."
    set +e
    sshpass -f "$BASE/password" ssh -tt \
      -o StrictHostKeyChecking=yes \
      -o UserKnownHostsFile="$BASE/known_hosts" \
      -o GlobalKnownHostsFile=/dev/null -o UpdateHostKeys=no \
      -o ConnectTimeout=15 -o ConnectionAttempts=1 \
      -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
      -o BatchMode=no -o PreferredAuthentications=password,keyboard-interactive \
      -p "$PORT" -l "$USERNAME" -- "$HOST" < "$fifo" >/dev/null 2>&1
    rc="$?"
    set -e
    exec 3>&-
    rm -f "$fifo"
    rmdir "$folder"
    case "$rc" in
      5) say "Falsches Passwort: Dienst gestoppt (keine weiteren Login-Versuche)."; exit 5 ;;
      6|7) say "Hostschlüssel unbekannt oder geändert: Dienst gestoppt."; exit 6 ;;
    esac
    if ((SECONDS - started >= 60)); then attempt=0; fi
    pause=2
    for ((i=0; i<attempt && pause<300; i++)); do pause=$((pause * 2)); done
    if ((pause>300)); then pause=300; fi
    attempt=$((attempt + 1))
    say "Verbindung beendet (Code $rc), Neuversuch in $pause s."
    sleep "$pause" &
    wait "$!" || true
  done
}

uninstall() {
  need systemctl
  systemctl --user disable --now "$SERVICE" >/dev/null 2>&1 || true
  rm -f "$UNIT" "$BIN" "$BASE/host" "$BASE/port" "$BASE/username" "$BASE/password" "$BASE/known_hosts"
  systemctl --user daemon-reload
  say "Dienst und gespeicherte Zugangsdaten entfernt."
}

if (( $# == 0 )); then help_text; exit 0; fi
case "$1" in
  setup) setup ;;
  start) need systemctl; systemctl --user start "$SERVICE" ;;
  stop) need systemctl; systemctl --user stop "$SERVICE" ;;
  status) need systemctl; systemctl --user status "$SERVICE" --no-pager || true ;;
  logs) need journalctl; journalctl --user -u "$SERVICE" -n 50 --no-pager ;;
  uninstall) uninstall ;;
  worker) worker ;;
  --help|-h|help) help_text ;;
  *) help_text; exit 2 ;;
esac
