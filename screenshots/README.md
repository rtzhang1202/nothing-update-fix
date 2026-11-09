# Evidence screenshots

Representative evidence captures, renamed to a normalized scheme. Sources: private analysis
workspace `D:/Hermes Desktop/NothingPhone3-Root/analysis/phase2|phase3/` (broken-state and
experiment captures) and `analysis/phase4/` (on-device verification captures). See
[`../docs/ROOT_CAUSE.md`](../docs/ROOT_CAUSE.md) for the full evidence chain.

| File | What it shows | Evidence ID |
|---|---|---|
| `01-update-page-broken-bare.png` | System Update page in the broken state: bare GLIF shell ("系统已是最新版本" + version lines + Check for updates), **no** What's New / changelog / firmware-info block | E1 |
| `02-update-page-after-forced-checkin.png` | Same page after a forced Google checkin (which succeeded — see logcat in ROOT_CAUSE §E3): only the "last checked" timestamp changed; content block still absent → pipeline healthy, nothing to render | E1/E3 |
| `03-exp-a-pif-disabled-before.png` | Update page before disabling PlayIntegrityFix (experiment A baseline) | E5a |
| `04-exp-a-pif-disabled-after.png` | After disabling PIF + reboot + re-check: page unchanged, gservices keys still ABSENT → PIF exonerated as cause | E5a |
| `05-exp-b-gservices-inject-before.png` | Update page before direct gservices injection of `update_title/update_description/update_size` (experiment B baseline) | E5b |
| `06-exp-b-gservices-inject-after.png` | After injection (rows verified in DB) + forced GMS restart: **still no What's New block** — rendering is pending-update-state-gated; injected rows were also wiped by GMS checkin | E5b |

## On-device verification screenshots (Nothing Phone (3), 2026-11-10)

Captured on the real device after installing the verified build
(`release/NothingUpdateFix.apk`, sha256 `6611d645…`), module enabled in LSPosed/Vector with
scope `com.android.settings` + `com.google.android.gms`. Sources: private analysis workspace
`D:/Hermes Desktop/NothingPhone3-Root/analysis/phase4/`.

| File | What it shows | Link / status |
|---|---|---|
| `device_chain_a_swinfo.png` | **Software Info page (`设置 → 关于手机 → 软件信息`) with the injected entries visible**: 更新日志 + 固件信息 (round-3 runtime probe fix) | Link A ✅ verified |
| `device_firmware_page.png` | Nothing's own `SoftVersionDisplayActivity` (软件版本 page: baseband / kernel / build / PSN) opened via the 固件信息 entry | Link A ✅ verified |
| `device_changelog.png` | Module `ChangelogActivity` rendering the bundled Nothing OS 5.0 changelog, opened via the 更新日志 entry | Link A ✅ verified |
| `device_update_page_state_gate.png` | System Update page after a manual check: still "系统已是最新版本" with **no What's New block** — the pending-update state gate (see limitations) | Link B ⚠ known limitation |
| `device_app_status.png` | Module Status screen: hook hit log (`chainA route2: added '更新日志' / '固件信息'` ×2) + built-in changelog viewer | verification artifact |

## TODO — additional captures (optional)

- `13-after-module-update-page.png` — System Update page **with a pending OTA** so the What's
  New / changelog block actually renders (Link B happy path; blocked today by the state gate —
  requires a real pending update on the device).
