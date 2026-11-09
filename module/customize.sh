#!/system/bin/sh
ui_print "- MT6899 Manager Helper"
ui_print "- Inicialização opcional no boot; sem keepalive ou isenções permanentes de bateria."
ui_print "- Instale o APK e ative Aplicar perfil no boot se desejar."
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
