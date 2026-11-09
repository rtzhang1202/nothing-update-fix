#!/usr/bin/env bash
# install_lsposed.sh — LSPosed(=Vector) Zygisk 模块安装步骤（host 侧，git-bash 运行）
# 设备：Nothing Phone (3), Android 17 / NOS 5.0, Magisk v31.1 (Zygisk 内置)
# 包：NothingUpdateFix/tools/Vector-v2.2-3080-Release.zip (JingMatrix/Vector v2.2 stable)
#
# 选型说明：官方 LSPosed/LSPosed 自 2023-10 (v1.9.2) 停更且仅支持 Android 8.1-14，
# 不适用于 Android 17；采用其官方延续 JingMatrix/Vector（v2.0 起更名，v2.1+ 支持
# Android 8.1-17，持续维护）。zip 为标准 Magisk 模块（module.prop + lib/<abi>/libzygisk.so，
# customize.sh 安装时映射到 zygisk/<abi>.so）。
set -euo pipefail

ADB="${ADB:-C:/Users/25451/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"
ZIP="$TOOLS_DIR/Vector-v2.2-3080-Release.zip"
EXPECT_SHA256="9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc"

echo "== 1. 校验 zip sha256 =="
ACTUAL=$(sha256sum "$ZIP" | awk '{print $1}')
if [ "$ACTUAL" != "$EXPECT_SHA256" ]; then
  echo "FAIL: sha256 mismatch ($ACTUAL)"; exit 1
fi
echo "OK: $ACTUAL"

echo "== 2. 设备就绪检查 =="
"$ADB" get-state >/dev/null
"$ADB" shell 'su -c id' | grep -q uid=0 || { echo "FAIL: no root"; exit 1; }

echo "== 3. 前置条件：Magisk Zygisk 必须开启（Magisk v30.7+/v31.1 内置 Zygisk） =="
echo "   （在 Magisk App -> Settings -> Zygisk 确认为 Enabled；本机 v31.1 默认可用）"
echo "== 4. 防冲突：若存在旧 LSPosed 模块 zygisk_lsposed，Vector 安装时会自动打 disable 标记 =="
"$ADB" shell 'su -c "ls /data/adb/modules/ | grep -E \"lsposed|vector\""'

echo "== 5. 推送 zip 到设备 =="
"$ADB" push "$(cygpath -m "$ZIP")" /data/local/tmp/Vector-v2.2-3080-Release.zip

echo "== 6. 用 Magisk 安装模块（等价于 Magisk App -> Modules -> Install from storage） =="
"$ADB" shell 'su -c "magisk --install-module /data/local/tmp/Vector-v2.2-3080-Release.zip"'

echo "== 7. 重启生效 =="
"$ADB" reboot
echo "等待开机..."
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done

echo "== 8. 重启后必须：解锁（运行设备上的 /data/local/tmp/unlock.sh）+ 拉起 Clash =="
echo "   adb shell 'su -c \"sh /data/local/tmp/unlock.sh\"'"
echo "   Clash: 打开 com.github.metacubex.clash.meta 主页点『已停止 点此启动』(若未自启)，确认 tun0"
echo "   （可用 restore_all.sh 一键完成解锁+Clash+校验）"

echo "== 9. 验证 =="
"$ADB" shell 'su -c "cat /data/adb/modules/zygisk_vector/module.prop; ls /data/adb/modules/zygisk_vector/zygisk/"'
echo "安装完成。打开 Vector 管理器（通知栏或 manager.apk）配置模块作用域。"
