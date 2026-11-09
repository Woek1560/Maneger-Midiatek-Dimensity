#!/system/bin/sh
# MT6899 Manager Helper — inicialização opcional no boot; sem keepalive.
PKG=dev.leonardo.mt6899
DIR=/data/adb/mt6899
LOG=$DIR/module.log
mkdir -p "$DIR"
log() { echo "$(date '+%d/%m %H:%M:%S') $*" >> "$LOG"; }
if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 100000 ]; then
  tail -n 200 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
fi
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
sleep 20
if [ "$(cat "$DIR/want_boot" 2>/dev/null)" = "1" ]; then
  am start-foreground-service -n "$PKG/.GuardService" --ez from_boot true >/dev/null 2>&1
  log "solicitada inicialização do GuardService no boot"
else
  log "inicialização automática desativada pelo app"
fi
# O próprio Android/BootReceiver e o app gerenciam o ciclo de vida. Não manter loop root.
exit 0
