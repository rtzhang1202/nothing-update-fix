# Evidence screenshots

Representative evidence captures, renamed to a normalized scheme. Sources: private analysis
workspace `D:/Hermes Desktop/NothingPhone3-Root/analysis/phase2|phase3/`. See
[`../docs/ROOT_CAUSE.md`](../docs/ROOT_CAUSE.md) for the full evidence chain.

| File | What it shows | Evidence ID |
|---|---|---|
| `01-update-page-broken-bare.png` | System Update page in the broken state: bare GLIF shell ("系统已是最新版本" + version lines + Check for updates), **no** What's New / changelog / firmware-info block | E1 |
| `02-update-page-after-forced-checkin.png` | Same page after a forced Google checkin (which succeeded — see logcat in ROOT_CAUSE §E3): only the "last checked" timestamp changed; content block still absent → pipeline healthy, nothing to render | E1/E3 |
| `03-exp-a-pif-disabled-before.png` | Update page before disabling PlayIntegrityFix (experiment A baseline) | E5a |
| `04-exp-a-pif-disabled-after.png` | After disabling PIF + reboot + re-check: page unchanged, gservices keys still ABSENT → PIF exonerated as cause | E5a |
| `05-exp-b-gservices-inject-before.png` | Update page before direct gservices injection of `update_title/update_description/update_size` (experiment B baseline) | E5b |
| `06-exp-b-gservices-inject-after.png` | After injection (rows verified in DB) + forced GMS restart: **still no What's New block** — rendering is pending-update-state-gated; injected rows were also wiped by GMS checkin | E5b |

## TODO — final UI screenshots (pending on-device verification)

Placeholders for captures to take once the module is installed and verified on the device.
Naming convention reserved:

- `10-after-module-software-info.png` — **TODO**: Software Info page with the injected
  「更新日志」/「固件信息」 entries visible (Link A).
- `11-after-module-changelog-activity.png` — **TODO**: module `ChangelogActivity` rendering the
  bundled changelog (target of the「更新日志」entry).
- `12-after-module-firmware-info.png` — **TODO**: Nothing `SoftVersionDisplayActivity` opened
  via the「固件信息」entry.
- `13-after-module-update-page.png` — **TODO**: System Update page with the What's New /
  changelog block rendered (Link B) — note the known pending-update state gate
  (ROOT_CAUSE §5): if the block stays hidden with no pending OTA, capture the hook hit log in
  the module's Status screen as the fallback verification artifact.
- `14-after-module-status-screen.png` — **TODO**: module Status screen showing hook hit logs
  for both links.
