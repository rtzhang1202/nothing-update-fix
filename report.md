# Nothing update fix — 构建与交付报告

日期：2026-11-10 · PC 侧构建 + 真机验证（Nothing Phone (3) 实测通过）· 已开源：https://github.com/rtzhang1202/nothing-update-fix

## 1. 交付物

| 项 | 值 |
|---|---|
| APK | `release/NothingUpdateFix.apk`（50,263 B，zipalign 4 + v2/v3 签名，apksigner verify 通过，真机安装验证通过；v1.0.1 构建） |
| SHA256 | `c3f49ca469f6ae091a2913bbbe57fce4af22f9b4ad9ccd6f6ab57e199dc17605`（**v1.0.1 = 当前发布版**，GMS 更新页双卡注入 + About 链接修复，真机验证通过；历史：v1.0.0 为 `6611d64538ad9a8cc6967aae408f9f2c5cc6c4de222c231e69f10d46a926ee93`，更早 `eed418063aba93d751d296d23e541763d6b850797f6802d616e9e8660bbc0b59` / `71e5c116f889522f7d6cd44bf9fe4a3e528abeb0bb98156cbc7294fd4de33928`） |
| 构建命令 | `cd "D:/Hermes Desktop/NothingPhone3-Root/NothingUpdateFix" && bash build.sh` |
| 包名 / label | `io.github.rtzhang.nothingupdatefix` / `Nothing update fix`（badging 精确核对） |
| SDK | minSdk 26 / targetSdk 37（已装 platform android-37.0 = Android 17，compileSdk 37） |
| 签名密钥 | `keystore/release.jks`（首次构建自动生成并保留，RSA 2048 / 10000 天，密码 `nothingupdatefix`） |

## 2. 验证结果（build.sh 内置，全部通过）

- `aapt2 dump badging`：`application-label:'Nothing update fix'`、`minSdkVersion:'26'`、
  `targetSdkVersion:'37'`、图标解析为自适应 `res/mipmap-anydpi-v26/ic_launcher.xml`（另有
  `mipmap-xxhdpi-v4` 传统 PNG 兜底）、launchable-activity = `.MainActivity`。
- `dexdump`（对最终 APK 内 classes.dex）：`MainHook / ChangelogActivity / MainActivity /
  StatusProvider / ChangelogData / ChangelogView / GmsPageInjector` 7 类均以 class descriptor 存在；
  **无任何 `Lde/robv/android/xposed/*` 类定义**（使用真 XposedBridgeApi-82.jar 仅作编译期
  classpath，d8 只收模块自身类；校验按 "Class descriptor" 行比对，排除签名引用误报）。
- `aapt2 dump xmltree`：`xposedmodule=true`、`xposeddescription`（英文描述）、
  `xposedminversion=82` 三条 meta-data 齐全；`assets/xposed_init` =
  `io.github.rtzhang.nothingupdatefix.MainHook`；`assets/xposed_scope` =
  `["com.android.settings","com.google.android.gms"]`；ChangelogActivity/StatusProvider
  `exported=true`。
- 功能级验证（PC 侧可做的都做了）：`ChangelogData.buildUpdateDescriptionHtml()` 在桌面 JVM
  实跑，产出 HTML 结构与规格逐项吻合（h3 'Nothing OS 5.0 (C5.0-260924-2115)' → 分节
  h4+ul/li+p → h3 '固件信息' + 表格（Android 版本 17 / 安全更新 2026-09-01 / 基带 / 内核 /
  Build 号）→ a 链接 https://nothingarchive.tech/docs/changelogs；运行时基带读
  `gsm.version.baseband`、内核读 `ro.kernel.version`→`os.version`→`/proc/version`、
  build 号读 `ro.build.display.id`→`ro.build.version.incremental`，全部 try/catch 兜底）。
  图标经视觉核验：黑底 + 白点阵 N（Nothing 点阵美学）。

## 3. 功能实现清单

1. **Xposed 经典模块（API 82）**：入口类 `MainHook implements IXposedHookLoadPackage`；
   manifest 三条 meta-data；`xposed_scope` 双作用域；真 API82 jar（api.xposed.info 下载，
   vendored 于 `vendor/`），无自写桩 → 不存在桩类混入 dex 的风险面（另有构建期硬校验）。
2. **链路 A（Settings）**：
   - route1：hook `androidx.preference.PreferenceFragmentCompat.onCreatePreferences(Bundle,String)`，
     回调内校验 `thisObject` 类名 ==
     `com.nothing.settings.deviceinfo.aboutphone.DeviceSoftwareInfoFragment`（远端配置契约类名，
     不混淆）；若该方法为 abstract 无法 hook（LSPosed 抛错）自动落 route2。
   - route2：沿类层级向上扫描最派生的**具体** `onCreatePreferences(Bundle,String)` 直接 hook
     （覆盖 Fragment 自身覆写与基类 `SettingsPreferenceFragment` 覆写两种形态）。
   - 注入：`getPreferenceScreen().addPreference()` 两条 androidx.preference.Preference
     （全反射构造，不依赖宿主 androidx 版本）：「更新日志」→ Intent 进本 APP ChangelogActivity
     （exported，FLAG_ACTIVITY_NEW_TASK）；「固件信息」→
     `Intent(ComponentName('com.android.settings','com.nothing.settings.softwareinfo.SoftVersionDisplayActivity'))`。
   - 去重防重入：每条注入前 `findPreference(key)` 探测（key = `nuf_changelog` / `nuf_fwinfo`），
     route1/route2 双命中时天然幂等。
3. **链路 B（GMS）**：
   - hook `android.content.ContentResolver.query` 全部 4 个公开重载
     （Uri,String[],String,String[],String / +CancellationSignal / Uri,String[],Bundle,CancellationSignal /
     Uri,String[],Bundle）及 `android.content.ContentProviderClient.query` 等价 4 重载。
   - `uri.authority == 'com.google.android.gsf.gservices'` 时：原查询执行后取结果 Cursor，
     按原列名（`name`/`value`）`MatrixCursor` 重建，行级覆盖 update_* 三键；兼容
     selectionArgs=键名列表形态（缺失键按需补行）与 `/prefix/<p>` URI 形态（补前缀命中键），
     全表查询则三键全量注入。不关闭原 Cursor（归属调用方）。
   - 值：`update_title='Nothing OS 5.0'`；`update_description`=运行时拼装 HTML（内置
     C5.0-260924-2115 changelog 全部分节 + 固件信息表 + nothingarchive 链接）；
     `update_size='C5.0-260924-2115'`（按报告 size 旗标为字符串形态，充当版本/大小信息行，
     避免伪造未知包体大小）。
   - 加分项（已实现）：hook 非混淆类 `com.google.android.gms.update.UpdateDescription`
     的 `(String,String,String,String)` 构造器，after 时仅在字段 a/b/c/d 为空时补
     标题/HTML/版本/链接（setIfEmpty，防覆盖真实 OTA 描述；final 字段反射写入全部 try/catch）。
4. **APP 自身**（单 Activity + 程序化 UI，零 layout 资源）：
   - 状态区：钩子命中日志。钩子经 `XposedBridge.log`（tag `NothingUpdateFix`）+
     `ContentResolver.call`→本 APP `StatusProvider` 双路上报，APP 内展示最近 40 行，
     可刷新/清空。
   - Changelog 查看器：内置 `assets/changelog.json`（当前版 C5.0-260924-2115 + 7 个历史版，
     2026-11-10 抓取自 nothingfirmware.github.io/device/nothing-phone-3.html 与
     nothingarchive.tech/docs/changelogs），ChangelogActivity（Settings 深链目标）同款渲染。
   - 链接区：nothingarchive.tech / nothingfirmware.github.io / nothing.community；
     About + GitHub 链接（v1.0.1 已修正为真实仓库 github.com/rtzhang1202/nothing-update-fix，
     v1.0.0 曾指向占位地址 github.com/rtzhang/NothingUpdateFix）。
   - 设置开关：**未实现，钩子常开**（见问题 2）。
5. **自适应启动器图标**：Python PIL 生成（scripts/gen_icons.py，确定性输出）：黑底 +
   白点阵 N 字形（含齿轮点阵角标），`mipmap-anydpi-v26` 自适应 XML + `mipmap-xxhdpi` 传统
   PNG 兜底（108dp 画布安全区内）。

## 4. 问题与已知限制

1. **真机验证已完成（2026-11-10，Nothing Phone (3) / C5.0-260924-2115）**：
   - 链路 A 通过——软件信息页成功注入「更新日志」（→ 模块 ChangelogActivity 正常渲染）
     与「固件信息」（→ Nothing 自带 SoftVersionDisplayActivity/软件版本页正常打开）。
     期间经历 3 轮修复：①Vector legacy bridge `callMethod` 拒绝 String→CharSequence 参数
     ②NothingSettings 对 `androidx.preference` 做了 R8 混淆 ③改为运行时签名 + 行为探针
     （`titleField=F`、`intentField=K`、`addPreference=J1` 探针）解析宿主字段/方法——
     这是链路 A 真机跑通的关键。截图见 `screenshots/device_chain_a_swinfo.png`、
     `device_firmware_page.png`、`device_changelog.png`、`device_app_status.png`。
   - 链路 B 钩子命中（`UpdateDescription` 构造钩命中 5 次），但 What's New 块存在
     **状态门**：仅在有 pending update 时渲染（原厂行为，见限制 8），「已是最新版本」
     状态下整块仍被隐藏。截图见 `screenshots/device_update_page_state_gate.png`。
   - 其余 PC 侧约束项（链路 A 注入时序、链路 B GSF Cursor 列名假设）经真机实测均成立。
2. **设置开关未做**：XSharedPreferences 需要 world-readable prefs，现代 Android 上不可靠，
   风险大于收益 → 钩子永远开启（模块无副作用数据面，仅补 UI 入口/补展示文案）。
3. **链路 A route1 依赖 androidx 实现形态**：`PreferenceFragmentCompat.onCreatePreferences`
   为抽象方法时 Xposed 不可 hook（已按设计回落 route2 直钩，构建产物同时保留双路由）。
4. **UpdateDescription 加分钩依赖 GMS 26.43.30 的字段名 a/b/c/d**（由 gms3_dis 证据锚定）；
   GMS 升级改名时该加分钩静默失效（setIfEmpty 全 try/catch，不影响 Gservices 主钩）。
5. **update_size 值为 build 号字符串**而非包体大小：真实 OTA 体积不可知，宁缺毋滥；
   若调度方实测发现 GMS 把该字段格式化为文件大小并显示异常，可删掉该键（规格允许省略）。
6. 构建非位级可复现（zip 时间戳）；构建流程可复现：同一脚本+已提交 keystore 可重复产出
   等价 APK。JDK 用 Android Studio JBR 25（可用，未下载 Temurin）。
7. `StatusProvider` exported 无权限保护（其他 app 可写入日志行）——本模块威胁模型下可接受，
   仅影响状态区展示。
8. **What's New 块状态门（真机实测）**——**v1.0.1 已由视图注入解决，见 §6**：链路 B 的
   Gservices 注入与 `UpdateDescription` 构造钩都会命中，但更新页 UI 只在存在 pending OTA 时
   渲染 What's New 块；无待装更新时整块保持隐藏（原厂行为）。截图：
   `screenshots/device_update_page_state_gate.png`（与 ROOT_CAUSE §E5 gservices 直注探针结论一致）。

## 5. 工件清单（项目根 `D:/Hermes Desktop/NothingPhone3-Root/NothingUpdateFix/`）

- `build.sh` — 可复现构建脚本（含下载 Xposed API、生成图标、构建、签名、全部校验）
- `app/src/main/` — AndroidManifest.xml、java/（7 个源文件，v1.0.1 新增 `GmsPageInjector.java`）、assets/
  （xposed_init、xposed_scope、changelog.json）、res/（图标）
- `scripts/gen_icons.py` — PIL 图标生成
- `vendor/XposedBridgeApi-82.jar` — 编译期依赖（api.xposed.info 官方 jar，25,478 B）
- `keystore/release.jks` — 签名密钥（自动生成并保留）
- `release/NothingUpdateFix.apk` + `.sha256`（+ `.idsig`）
- `out/` — 中间产物（badging.txt、dexdump.txt、dex_classes.txt 等校验留痕）

## 6. v1.0.1（2026-11-10）— GMS 更新页视图注入 + About 链接修复

### 6.1 变更

| # | 变更 | 摘要 |
|---|---|---|
| 1 | **GMS 系统更新页注入两张 Nothing 深色卡**（`feat(gms)` a83ecfe） | 新增 `GmsPageInjector`：在 `SystemUpdateActivity` 的 GLIF 页上、版本摘要 `id/description` 之后插入 What's New 卡（内置 `changelog.json` 当前版本正文 + 分节要点 + 「查看全部更新日志 ›」→ 模块 `ChangelogActivity`）与固件信息卡（型号 A024 / 设备代号 Metroid / Android 17 / 系统版本 / Build 号 / 基带 / 内核 / 安全补丁 / 序列号，运行时读 `Build`/`ro.*`/`/proc/version`）。**无 pending update 也渲染**（与 OTA 状态机解耦），v1.0.0 的「状态门」限制就此解决 |
| 2 | **About 链接指向真实仓库**（`fix(app)` fca2622） | 由占位 `github.com/rtzhang/NothingUpdateFix` 改为 `github.com/rtzhang1202/nothing-update-fix` |

注入幂等：卡片容器视图 tag = `nuf_gms_cards_v1`，点「检查更新」、返回重进均不重复插入。

### 6.2 真机验证（Nothing Phone (3) / C5.0-260924-2115 / 2026-11-10，APK sha256 `c3f49ca4…`）

| 判据 | 结果 | 证据（`analysis/phase6/verify/` → `screenshots/device_v101_*.png`） |
|---|---|---|
| 两卡渲染（无 pending OTA） | PASS | `r3_page.png`（更新内容卡）/ `r3_bottom.png`（固件信息卡）→ `device_v101_update_cards.png` / `device_v101_firmware_card.png` |
| What's New 卡正文 | PASS | `r3_ui.xml`：标题 + `Nothing OS 5.0 · C5.0-260924-2115` + 7 个分节（渲染前 3 节 × 前 4 条） |
| 「查看全部更新日志 ›」跳转 | PASS | `r3_link_bounds.xml`（clickable）→ 点击 → `r3_changelog.png`，`topResumedActivity` = `.ChangelogActivity` → `device_v101_changelog.png` |
| 固件信息卡 9 行 | PASS | `r3_bottom.xml`：A024 / Metroid / 17 / C5.0-260924-2115 / 2609242115 / MPSS.DE… / 6.6 / 2026-09-01 / 序列号 — |
| 幂等 | PASS | `r3_after_check.xml`、`r2_after_back.xml`：每个注入标签各 1 处 |
| 链路 A 回归 | PASS | `r3_chainA.png` / `r3_chainA_ui.xml`：软件信息页两入口仍在 → `device_v101_chainA.png` |
| 锚点 | L1 命中 | `r3_lsposed_log.txt`：`L1 hooked …#onCreate/#onResume` → `container Activity resolved: com.google.android.gms.update.SystemUpdateActivity` → `added 2 cards (via onCreate, anchor=description, host=LinearLayout)` |

### 6.3 Chimera 委托坑（本轮真根因）

`com.google.android.gms.update.phone.SystemUpdateChimeraActivity` **不是** `android.app.Activity`：
继承链为 `… < agdp < agdw < agco < agdo < com.google.android.chimera.android.Activity < afxg <
ContextThemeWrapper`，是 Chimera 的*模块委托*；窗口由容器 Activity
`com.google.android.gms.update.SystemUpdateActivity` 持有。

- 轮 1 首装完全没注入：钩 `Activity#onCreate` 后强转 `thisObject` 抛
  `ClassCastException: …SystemUpdateChimeraActivity cannot be cast to android.app.Activity`。
- 修法 `resolveActivity()`：先反射委托对象上的 `Activity`（0 参 getter / `Activity` 字段），
  再退 `ActivityThread.getActivities()` 全量扫描，优先选 `findViewById` 能命中锚点 id 的实例；
  锚点/控件 id 一律按**资源名**运行时 `getIdentifier` 解析（数值随 GMS 版本变化）。
- 轮 2 重建后仅 `force-stop gms/gms.ui` 热重载即出现 `added 2 cards`，两卡上屏。
- L2（`gtah.i` / `gtak` 渲染器形状探针）已装但真机未观测到触发——只在「有更新」状态族可达，
  不影响主链路；L1 每次 resume 会重注入。

### 6.4 发布信息

- 仓库：https://github.com/rtzhang1202/nothing-update-fix
- Release：`v1.0.1`（tag `v1.0.1`），附件 `NothingUpdateFix.apk`
- APK sha256：`c3f49ca469f6ae091a2913bbbe57fce4af22f9b4ad9ccd6f6ab57e199dc17605`
- 注：该 APK 即真机验证版，未在打 tag 后重建，内部 `versionName` 仍为 `1.0`（重建会改变 sha256）。
