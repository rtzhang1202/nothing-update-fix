#!/usr/bin/env bash
# restore_all.sh — 实验回滚保障（host 侧，git-bash 运行）
# 保证三件事：
#   1) /data/adb/modules/ 下 0 个 disable 标记（全部模块恢复启用）
#   2) gservices 注入行回滚（DELETE update_title/update_description/update_size）
#   3) Clash 拉起并确认网络（tun0 / 有网）
# 用法: bash restore_all.sh [--reboot]   (--reboot: 清理后重启并自动解锁+拉起Clash)
set -euo pipefail

ADB="${ADB:-C:/Users/25451/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"

# 注意：adb 是原生 Windows 程序，读不到 MSYS 的 /tmp —— 临时文件放工具目录（D:/ 风格路径）
DEV_SCRIPT="$TOOLS_DIR/restore_all_device.sh"
cat > "$DEV_SCRIPT" <<'DEVICE_EOF'
#!/system/bin/sh
echo "== 1) 删除全部模块 disable 标记 =="
find /data/adb/modules -maxdepth 2 -name disable -print -delete
echo "剩余 disable 标记数: $(find /data/adb/modules -maxdepth 2 -name disable | wc -l)"

echo "== 2) gservices 注入行回滚 =="
/data/data/com.termux/files/usr/bin/python - <<'PYEOF'
import sqlite3
db='/data/user/0/com.google.android.gms/databases/gservices.db'
c=sqlite3.connect(db)
c.execute("DELETE FROM main WHERE name IN ('update_title','update_description','update_size')")
c.commit()
for k in ('update_title','update_description','update_size'):
    hit=list(c.execute("SELECT name FROM main WHERE name=?",(k,)))
    print(k,'->','STILL_PRESENT' if hit else 'ABSENT_OK')
c.close()
PYEOF
su -c 'am force-stop com.google.android.gsf; am force-stop com.google.android.gms'

echo "== 3) Clash 拉起 =="
am start -n com.github.metacubex.clash.meta/com.github.kr328.clash.MainActivityAlias >/dev/null 2>&1
sleep 5
echo "-- tun0 状态 --"
ip addr show tun0 2>&1 | head -3
echo "-- 连通性 --"
ping -c 2 -W 3 www.baidu.com 2>&1 | tail -1
echo "RESTORE_DEVICE_DONE"
DEVICE_EOF

"$ADB" push "$(cygpath -m "$DEV_SCRIPT")" /data/local/tmp/restore_all_device.sh >/dev/null
"$ADB" shell 'su -c "sh /data/local/tmp/restore_all_device.sh"'

if [ "${1:-}" = "--reboot" ]; then
  echo "== 可选：重启并解锁 + 拉起 Clash =="
  "$ADB" reboot
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done
  sleep 5
  "$ADB" shell 'su -c "sh /data/local/tmp/unlock.sh"' >/dev/null 2>&1
  "$ADB" shell 'am start -n com.github.metacubex.clash.meta/com.github.kr328.clash.MainActivityAlias' >/dev/null 2>&1
  sleep 8
  "$ADB" shell 'ip addr show tun0 | head -3'
fi

echo "== 终验：模块 disable 标记 =="
"$ADB" shell 'su -c "find /data/adb/modules -maxdepth 2 -name disable | wc -l"'
echo "RESTORE_ALL_DONE"
