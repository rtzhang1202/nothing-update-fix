# Nothing Update Fix

> LSPosed/Xposed module (classic API 82) that restores the missing **update changelog** and
> **firmware info** content in the System Update page and the Software Info page of a rooted
> **Nothing Phone (3)** (Nothing OS 5.0 / Android 17, build `C5.0-260924-2115`).

- Package: `io.github.rtzhang.nothingupdatefix` · Label: `Nothing update fix` · minSdk 26 / targetSdk 37
- Full root-cause analysis with evidence chain: [`docs/ROOT_CAUSE.md`](docs/ROOT_CAUSE.md)
- Evidence screenshots: [`screenshots/`](screenshots/)

## 1. Problem

After rooting (Magisk + LSPosed/Vector), two Nothing-customized content surfaces disappear:

| Surface | Stock behavior | Observed after root |
|---|---|---|
| **System Update page** (GMS gOTA client, `com.google.android.gms/.update.SystemUpdateActivity`) | "What's New" changelog block + firmware info line, fed by server-side OTA metadata | Bare GLIF shell: "系统已是最新版本" + version lines + **Check for updates** only |
| **Software Info page** (`Settings → About phone → Software info`, `DeviceSoftwareInfoFragment`) | Dynamic "更新日志" / "固件信息" cards | Only static cards + a black empty area |

## 2. Root cause (short version)

The content was never stored on the device — it is **server-delivered, and both delivery channels
broke independently**:

1. **Update page**: the changelog lives in the GMS Gservices flags `update_title` /
   `update_description` / `update_size`, assembled into `UpdateDescription` (4 `String` fields)
   and rendered in the GLIF layout. When `update_description` is empty, the whole block is hidden
   (`setVisibility(8)`). The device-side Gservices rows for these keys are currently absent —
   in part because GMS data was cleared during troubleshooting, which wipes the already-fetched
   OTA metadata cache (same failure mode as Huawei's official FAQ: "clear update cache → current
   version changelog is empty until the next OTA").
2. **Software Info page**: its dynamic cards come from Nothing's online config service
   `iota.nothing.tech` (`settings_recommendation` payload). That endpoint currently answers
   **503** (AWS ALB unhealthy target group — confirmed from 10 probes across 3 continents), and
   the APK-local fallback `res/raw/settings_recommendation.json` contains **no card for
   `DeviceSoftwareInfoFragment`** — so the page has nothing to show.

### Fix principle

```
        STOCK (broken)                              WITH THIS MODULE
  ┌─────────────────────────────┐          ┌──────────────────────────────────┐
  │ iota.nothing.tech  → 503    │          │ Link A (com.android.settings)    │
  │   settings_recommendation   │          │  hook onCreatePreferences(...)   │
  │   (no card for this page)   │  ──X──►  │   └ addPreference:               │
  │ Software Info = static +    │          │      "更新日志" → ChangelogActivity│
  │   black area                │          │      "固件信息" → SoftVersion…    │
  ├─────────────────────────────┤          ├──────────────────────────────────┤
  │ GMS Gservices               │          │ Link B (com.google.android.gms)  │
  │   update_title/desc/size    │          │  hook Gservices query()          │
  │   = ABSENT (cache wiped)    │  ──X──►  │   └ MatrixCursor re-injects      │
  │ UpdateDescription.b = null  │          │      update_title/description/…  │
  │   → setVisibility(8)        │          │      (built-in changelog HTML)   │
  └─────────────────────────────┘          └──────────────────────────────────┘
```

The module never touches system partitions — everything is done in-process via hooks, and all
displayed content is either bundled (changelog JSON) or read live from device properties.

## 3. Features

- **Link A — Software Info page entries** (scope `com.android.settings`):
  injects two preferences into `DeviceSoftwareInfoFragment`:
  - **更新日志 (Changelog)** → module's own `ChangelogActivity`, rendering the bundled changelog
    (`assets/changelog.json`: current build + 7 historical versions, sourced from
    nothingfirmware.github.io / nothingarchive.tech).
  - **固件信息 (Firmware info)** → Nothing's own `SoftVersionDisplayActivity`
    (`com.nothing.settings.softwareinfo`), fully local content.
  - Idempotent injection (`findPreference` de-dup guard), dual routing (interface hook with
    class-name guard + direct concrete-method hook fallback).
- **Link B — Update page changelog** (scope `com.google.android.gms`):
  hooks `ContentResolver.query` / `ContentProviderClient.query` (all public overloads); when the
  authority is `com.google.android.gsf.gservices`, the result cursor is rebuilt with a
  `MatrixCursor` that overwrites/appends `update_title` / `update_description` / `update_size`.
  Both query shapes are handled (key-list `selectionArgs` and `/prefix/<p>` URIs). The
  `update_description` HTML is assembled at runtime (changelog sections + firmware info table —
  baseband `gsm.version.baseband`, kernel `ro.kernel.version`/uname, build `ro.build.display.id`
  — all read live with try/catch fallbacks).
- **Bonus hook**: `com.google.android.gms.update.UpdateDescription(String,String,String,String)`
  constructor — fills **empty** fields only (`setIfEmpty`), so a real OTA description is never
  overwritten.
- **Status page**: hook hit log (via `XposedBridge.log` + a `ContentProvider` round-trip),
  last 40 lines, refresh/clear.
- **Built-in changelog viewer** and adaptive launcher icon (black + dot-matrix "N", Nothing
  dot-matrix aesthetics).

## 4. Requirements

- **Device**: Nothing Phone (3) (A024 / Metroid), Nothing OS 5.0 / Android 17 (API 37),
  build `C5.0-260924-2115` (may work on nearby builds; the GMS hook targets GMS 26.43.30
  internals, see limitations).
- **Root**: Magisk (tested with v31.1, systemless).
- **Xposed framework**: LSPosed — the original LSPosed is EOL and does not support Android 17;
  use the maintained **[JingMatrix/Vector](https://github.com/JingMatrix/LSPosed)** branch
  (v2.0+ renamed *Vector*; v2.1+ officially supports Android 8.1–17; tested with v2.2 / 3080,
  installed as Zygisk module `zygisk_vector`). An install helper is provided in
  [`tools/install_lsposed.sh`](tools/install_lsposed.sh).
- **Module scope** (declared in `assets/xposed_scope`, must be enabled in the manager):
  `com.android.settings` and `com.google.android.gms`.
- **Reboot** after enabling the module (or force-stop Settings + Google Play services).

## 5. Build

```bash
bash build.sh
```

Output: `release/NothingUpdateFix.apk` (zipalign 4 + v2/v3 signed) + `.sha256`.

Dependencies (the script resolves/downloads most of them):

- **Android SDK** build-tools `37.0.0` + platform `android-37.0` (Android 17 / API 37);
  override with `ANDROID_SDK_ROOT` / `ANDROID_HOME`.
- **JDK** — Android Studio JBR by default (`C:/Program Files/Android/Android Studio/jbr`),
  override with `JAVA_HOME`.
- `vendor/XposedBridgeApi-82.jar` — compile-time only, auto-downloaded from api.xposed.info if
  missing; **never packed into the dex** (build.sh enforces this with a `dexdump` check).
- Python 3 + Pillow (launcher icon generation only).

Signing: a `keystore/release.jks` is auto-generated on first build (password `nothingupdatefix`).
The keystore is **not** committed to git (see `.gitignore`) — a fresh checkout will produce an
equivalent APK signed with its own key.

The script runs full verification on every build: `aapt2 dump badging` (label / SDKs / icon),
`dexdump` (all module classes present, **zero** `de.robv.android.xposed.*` class definitions),
`apksigner verify` (v2/v3) and SHA-256 output.

## 6. Install & enable

1. Install `release/NothingUpdateFix.apk`.
2. In the LSPosed/Vector manager: enable **Nothing update fix**, scope =
   `com.android.settings` + `com.google.android.gms`.
3. Reboot (or force-stop Settings and Google Play services, then reopen the pages).
4. Check the app's **Status** section for hook hit logs.

## 7. Module architecture

```
MainHook (IXposedHookLoadPackage, xposed_init)
├─ Link A — handleLoadPackage("com.android.settings")
│   route1: hook androidx.preference.PreferenceFragmentCompat.onCreatePreferences(Bundle,String)
│           guard: thisObject class == com.nothing.settings.deviceinfo.aboutphone
│                  .DeviceSoftwareInfoFragment
│   route2: fallback — scan class hierarchy, hook most-derived concrete onCreatePreferences
│   inject:  PreferenceScreen.addPreference() ×2 (pure reflection, host-agnostic)
│           nuf_changelog  → ChangelogActivity (in-module, exported)
│           nuf_fwinfo     → com.nothing.settings.softwareinfo.SoftVersionDisplayActivity
│
├─ Link B — handleLoadPackage("com.google.android.gms")
│   hook ContentResolver.query ×4 overloads + ContentProviderClient.query ×4 overloads
│   when uri.authority == "com.google.android.gsf.gservices":
│       run original query → rebuild rows into MatrixCursor (same column names)
│       overwrite/append  update_title / update_description / update_size
│   plus: UpdateDescription(String,String,String,String) ctor → setIfEmpty fill
│
└─ StatusProvider (ContentProvider) — hook hit log sink for the in-app status screen
```

Supporting pieces: `ChangelogData` (JSON + runtime HTML assembly), `ChangelogView` /
`ChangelogActivity` (renderers), `scripts/gen_icons.py` (adaptive icon), `build.sh`
(reproducible pipeline with hard verification gates).

## 8. Known limitations

1. **Pending-update state gate**: when there is no pending OTA, GMS may gate the What's New
   block at the UI level even if `update_description` is populated (confirmed by a direct
   gservices injection probe — see `docs/ROOT_CAUSE.md` §E5). Link B restores the data the
   page reads; a fully hidden block in "already up to date" state can still occur.
2. **GMS checkin behavior**: GMS rewrites/clears the `update_%` namespace of Gservices on
   checkin — direct DB injection does not persist. This module's hooks are unaffected (they
   intercept at query time), but any manual DB edits will be wiped.
3. **GMS upgrade compatibility**: the bonus `UpdateDescription` hook relies on the field names
   `a/b/c/d` in GMS 26.43.30 (anchored by disassembly evidence). A GMS update may rename them —
   the hook then fails silently (fully try/catch'd) and the Gservices main hook still works.
4. **No settings toggle**: hooks are always on (XSharedPreferences is unreliable on modern
   Android). The module has no side effects beyond adding UI entries and display text.
5. **`update_size` shows the build string**, not a package size (real OTA size is unknowable
   offline). Delete the key if GMS formats it as a file size and looks odd.
6. **Not bit-reproducible builds** (zip timestamps); the pipeline itself is reproducible.
7. Device verification status: PC-side build and static checks are done; on-device validation
   is pending final confirmation — treat on-device behavior as *expected, pending verification*.

## 9. Acknowledgments

- **[JingMatrix/Vector](https://github.com/JingMatrix/LSPosed)** — the actively maintained
  LSPosed continuation that makes Xposed modules possible on Android 15–17.
- **LSPosed / XposedBridge** — the original framework and the classic module API (82) this
  module is built on; `XposedBridgeApi-82.jar` is used as compile-time dependency only.
- **[nothingarchive.tech](https://nothingarchive.tech)** community (and
  [nothingfirmware.github.io](https://nothingfirmware.github.io)) — firmware/changelog archives
  that made the bundled changelog data possible.
- **nothing.community** — firmware update threads used as changelog reference.

## 10. License & disclaimer

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 rtzhang.

**Use at your own risk.** Rooting, unlocking the bootloader and running Xposed-style hooks carry
real dangers: bootloops, data loss, tripped integrity checks (Play Integrity / Widevine L3),
voided warranty, and potential breakage after OTA or GMS updates. This module only *displays*
information and adds UI entries — it does not modify system partitions — but hooking
`com.android.settings` and `com.google.android.gms` can interact with future vendor/GMS changes
in unforeseen ways. Always keep a backup and know how to recover (stock image + fastboot).
The authors are not responsible for any damage, data loss, or warranty consequences.
