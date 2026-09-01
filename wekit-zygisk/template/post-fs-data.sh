MODDIR=${0%/*}
# Fix SELinux label on the module directory (chained to every subdirectory).
# FolkPatch/APatch may install modules with an isolated MLS label
# (e.g. adb_data_file:s0:c139,c257,c512,c768); zygote cannot read that, so
# Zygisk Next fails to load our native loader with `avc: denied { read }`
# and the module never injects into WeChat. Rewrite the label back to the
# plain adb_data_file:s0 context every boot so zygote can enumerate modules.
for path in "$MODDIR" "$MODDIR/zygisk" "$MODDIR/zygisk/arm64-v8a.so" "$MODDIR/payload" /data/adb/wekit_zygisk; do
  chcon u:object_r:adb_data_file:s0 "$path" 2>/dev/null || true
done
