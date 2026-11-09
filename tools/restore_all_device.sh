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
