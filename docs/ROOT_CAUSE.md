# Root Cause Analysis — Missing "Update Changelog / Firmware Info" on Nothing Phone (3)

Device: Nothing Phone (3) (A024 / Metroid), Nothing OS 5.0 / Android 17 (SDK 37),
build `C5.0-260924-2115`, Magisk v31.1 rooted. Analysis date: 2026-11-10.

Method: static disassembly of NothingSettings / GMS APKs (dexdump 37.0.0 + dex structure
parsing), live device probes (uiautomator dumps, logcat captures, root filesystem inspection,
gservices DB experiments), and multi-country network probes. All conclusions below are anchored
to evidence; disassembly line numbers refer to the private evidence workspace
(`D:/Hermes Desktop/NothingPhone3-Root/analysis/`), key excerpts are reproduced inline.

---

## 1. Symptoms

After rooting, two Nothing-customized content surfaces are gone:

1. **System Update page** (`Settings → System → System update`, which is the GMS gOTA client
   `com.google.android.gms/.update.SystemUpdateActivity`) — degraded to a bare Google GLIF
   shell: title "系统已是最新版本" (Your system is up to date), `Android 版本：17`,
   `Android 安全更新：2026年9月1日`, "上次成功检查更新的时间", and a **Check for updates**
   button. The stock "What's New" changelog block and firmware-info line are absent.
2. **Software Info page** (`Settings → About phone → Software info`,
   `DeviceSoftwareInfoFragment`) — only static cards remain plus a large black empty area; the
   dynamic "更新日志" (changelog) / "固件信息" (firmware info) cards are gone.

## 2. Root cause chain

The displayed content is **never stored on the device** — both surfaces are fed by remote
services, and both delivery channels are independently broken:

| # | Fact | Evidence |
|---|---|---|
| ① | The update page is the GMS gOTA client; its changelog ("What's New") and firmware info are delivered by the server with OTA metadata and rendered in `UpdateDescription` (4 `String` fields a/b/c/d). When field `b` (HTML changelog) is empty, the whole block is `setVisibility(8)`-hidden. | E2 |
| ② | During troubleshooting, GMS data was cleared, which wiped the already-fetched OTA metadata cache. The `update_title` / `update_description` / `update_size` Gservices rows are currently ABSENT. Same failure mode as Huawei's official FAQ ("clear update cache → current-version changelog shows no content until the next OTA push"). | E4, E8 |
| ③ | The Software Info page's dynamic cards come from Nothing's online config service `iota.nothing.tech` (`settings_recommendation` payload). The endpoint currently returns 503 (AWS ALB unhealthy target group). The APK-local fallback config contains **no card for this page**. | E6, E7 |
| ④ | `layoutType` is a GMS client-side fragment constant (1/2/5/6), **not** a server-delivered field — earlier "layoutType mismatch" hypotheses are a red herring. | E9 |

### Red herrings (excluded)

- `execution.ota_metadata_proto` / `last_metadata_url` — `last_metadata_url` belongs to
  `DynamicCarrierIdUpdateGmsTaskService` (dynamic carrier ID list), **not** an OTA changelog URL.
- `com.nothing.settings.experimental.UpdateConfigService` — experimental feature config refresh,
  unrelated to update logs.
- Secret code `*#*#8377466#*#*` (`SoftVersionDisplayActivity`) — fully local firmware page,
  works fine; not the broken surface.
- PIF (Play Integrity Fix) — experimentally exonerated (E5a).

## 3. Evidence chain

### E1 — UI anatomy of the broken state (live)

- `step1_update_page.png` / `step3_update_page_after.png` (→ `screenshots/01-…`, `02-…`):
  bare GLIF page, no changelog/firmware block, before and after a forced checkin.
- uiautomator dump (`step3_ui.xml`): `com.google.android.gms:id/suc_layout_title` =
  "系统已是最新版本"; `id/description` = the **GLIF default** description block
  ("Android 版本：17\nAndroid 安全更新：…\n\n上次成功检查更新的时间：…"), i.e. not the
  `UpdateDescription` HTML block.

### E2 — GMS bytecode: data model, fill chain, hide point (static)

- `com.google.android.gms.update.UpdateDescription extends AbstractSafeParcelable`:
  4 `public final String` fields **a/b/c/d**, ctor `(String,String,String,String)`,
  CREATOR reads 4 Strings from Parcel (`gms3_dis:3740368`).
- Single fill point `Lgstx;.b()` (`gms3_dis:3759856-3760215`) builds
  `SystemUpdateStatus.x = UpdateDescription(a,b,c,d)` from **Gservices flags** (log tag
  "GservicesUtils", error string "Failed to parse value from Gservices for flag."):
  - `a` (title) ← flag `update_title` (`gms3_dis:1199760-1199764`)
  - `b` (HTML changelog) ← flag `update_description` (`gms3_dis:3760060-3760063`)
  - `c` (version/size line) ← flag `update_size` (`gms3_dis:3760083-3760088`)
  - `d` ← `update_end_of_life_url` (by extraction order; not 100% register-locked)
- Render/hide (`classes13_dis:928799-928818`): `gtak.a()` does `Html.fromHtml(b)` into the
  changelog TextView; **`b` null/empty → `K().setVisibility(8)`, the entire block is hidden**.

### E3 — Update page pipeline is healthy (live checkin probe)

Forced checkin via the page's "Check for updates" (`step3_update_logcat.txt`):

```
I/Checkin: [CheckinRequestProcessor] Default Task : Checkin Succeeded:
           https://android.googleapis.com/checkin (fragment #1).
I/Checkin: [CheckinOperation] Checkin Operation finished with result: SUCCESS
I/SystemUpdate: [Api,CheckForConfigUpdateOperation] Checkin request finished with code Optional.of(21021).
I/SystemUpdate: [Common,RequestConfigUpdateLoader] Config update succeeded.
```

The "last successful check" timestamp updated live (11月9日 → 00:36). No
`UpdateDescription`/`gtak` errors, no metadata-fetch failures. Root filesystem probe
(`step4_gms_probe.txt`, `step4b_update_files.txt`): `UPDATE_INFO_PREFS` empty map,
`control.installation.current_update_url` empty, `status=0`, `finished-execution`, and a global
grep for `last_metadata|metadata_url` hits **zero** — a clean "no pending update" state, not a
"fetch broken" state. ⇒ hypothesis "GMS registration/metadata pull is broken" is **refuted**;
what remains is "nothing to render" (no pending update + flags absent).

### E4 — Gservices baseline: the three keys are absent (live)

`/data/user/0/com.google.android.gms/databases/gservices.db` (table `main(name TEXT PRIMARY KEY,
value TEXT)` — note GSF's own DB directory no longer exists; gservices lives in the GMS package):
28 `update_%` rows, all config-style keys (`update_streaming_enabled`, `update_notify_*`, …).
**`update_title` / `update_description` / `update_size` all ABSENT** (`baseline_update_rows.txt`).

### E5 — Direct injection experiments (live, rolled back)

- **E5a (PIF exonerated)**: disabling PlayIntegrityFix + reboot + re-check → gservices rows
  unchanged, keys still ABSENT (`p3_nopif_before/after.png`, `nopif_update_rows.txt`). PIF is
  not the cause.
- **E5b (state gate + checkin wipe)**: backing up the DB and INSERTing the three keys with test
  values → force-stop GMS → reopen update page → **no What's New block rendered at all**
  (`p3_inj_before/after.png`, `p3_inj_ui_before/after.xml`) even though the rows were verified
  present in the DB; after GMS ran its check, **all injected rows were wiped** by GMS. Two
  conclusions: ① with no pending update, What's New rendering is state-gated and gservices
  values alone don't drive the UI; ② direct DB writes don't persist (checkin rewrites/clears the
  `update_%` namespace). Hence this module hooks at **query time** instead of writing the DB.
  Everything was rolled back and verified byte-identical to baseline
  (`modules_restored_verify.txt`, `postrollback_update_rows.txt`).

### E6 — `iota.nothing.tech` is down server-side (network probes)

- Direct and proxied requests to `https://iota.nothing.tech/api/v2/…` return the **AWS ALB
  default 503 page** (`Server: awselb/2.0`, 162-byte body), all paths, both :80 (301→https)
  and :443.
- **check-host.net 10-probe matrix** (CH/FR/IR×2/MD/NL/RU/US×3, 3 continents, 3 distinct ALB
  backend IPs): **all 503** — server-side, not geo/WAF/routing (`iota_route_matrix.md`).
- DNS: `iota.nothing.tech` CNAME → `alb-nothing-74768411.eu-central-1.elb.amazonaws.com`;
  CN and overseas resolvers agree — no geo-DNS.
- Live capture on device: opening the Software Info page triggers a five-domain pull
  (`iota.nothing.tech` → `dc-api.nothingtech.link` → `asset.nothing.tech` → `nothingarchive.tech`
  → `support.nothing.tech`); smartcenter's sibling SDK hardcodes
  `https://iota.nothing.tech/api/v2/` as the config endpoint.

### E7 — Local fallback config has no card for this page (static)

The card pipeline (NothingSettings `classes4.dex` / `classes.dex`):
`com.nothing.onlineconfig.ConfigGrabber` (config name `settings_configs`, ROM-provided impl) →
`NTConfigManager.loadConfig/parseConfigData` → `settings_recommendation` card array
(`SettingsRecommendation_16B`) → grouped by `fragmentTag` (= Fragment class name) →
`SettingsPreferenceFragment.addRecommendationPreferences()` keys the map by
`SettingsActivity.getInitialFragmentName()` and injects `RecommendationPreference` cards.

The APK fallback `res/raw/settings_recommendation.json` (version=100000002) contains 18 cards
covering only **8 fragmentTags — none is `DeviceSoftwareInfoFragment`**. So this page's cards can
only come from the online config; with iota 503 the page is guaranteed to show the black area.
(The strings "更新日志" etc. exist in **no** local dex/resource — everything is remote-sourced.)

### E8 — Industry precedent

Huawei's official FAQ (earbuds firmware changelog empty): "after deleting the device card or
**clearing the software update cache, the current version's changelog shows no content**" —
restored only when a new version is pushed. Same server-delivered-cache mechanism as ②.

### E9 — `layoutType` is a client constant

`layoutType` values (1/2/5/6) are GMS client-side fragment/layout selection constants, not a
server metadata field; no server response in this case ever contained one.

## 4. Fix approach (what this module does and why)

Chosen alternatives were weighed against: (a) hosts/proxy MITM of `iota.nothing.tech` serving a
forged `settings_recommendation` (fragile TLS, title resource names must exist in the host APK);
(b) repacking NothingSettings.apk to edit `res/raw/settings_recommendation.json` (invasive,
breaks on OTA); (c) faking a complete "pending update" GMS state (high complexity, risk of
triggering real OTA flows/errors). The shipped solution is two low-impact hook chains:

- **Link A** (`com.android.settings`): hook `PreferenceFragmentCompat.onCreatePreferences`
  (class-name-guarded, with direct concrete-method fallback) and add two preferences to the
  Software Info page — "更新日志" → the module's own changelog viewer (bundled data), "固件信息"
  → Nothing's own fully-local `SoftVersionDisplayActivity`.
- **Link B** (`com.google.android.gms`): hook `ContentResolver/ContentProviderClient.query`
  for authority `com.google.android.gsf.gservices` and rebuild the result `MatrixCursor`,
  overwriting/appending `update_title` / `update_description` / `update_size` with a
  runtime-assembled changelog HTML + live firmware properties. Interception at query time means
  GMS checkin's DB cleanup (E5b) cannot defeat it. A bonus `UpdateDescription` constructor hook
  fills empty fields only (`setIfEmpty`), never overwriting real OTA content.

## 5. Residual unknowns

1. **Pending-update state gate** (E5b): with no pending OTA, GMS may still not show the What's
   New block even with `update_description` populated. The next real OTA push is the terminal
   test (Huawei precedent suggests the stock path self-heals then).
2. `UpdateDescription.d` binding is inferred by extraction order (a/b/c are string-anchored).
3. The real online `settings_recommendation` content was never captured (iota 503) — the exact
   stock cards for the Software Info page are reconstructed from the reported missing items.
4. Bonus hook depends on GMS 26.43.30 field names `a/b/c/d`; a GMS update may silently disable
   it (main Gservices hook unaffected).

## 6. Evidence index

| ID | Artifact (private workspace `analysis/`) | Copy in this repo |
|---|---|---|
| E1 | `phase2/step1_update_page.png`, `phase2/step3_update_page_after.png`, `phase2/step3_ui.xml` | `screenshots/01,02-*.png` |
| E2 | `phase2/dataflow_forensics.md` §2, `phase2/evidence_gms3_updateDescription_*.txt`, `phase2/evidence_gms3_ota_metadata_proto_key.txt`, `phase2/evidence_gms12_last_metadata_url.txt` | summarized here |
| E3 | `phase2/gms_state_probe.md`, `phase2/step3_update_logcat.txt`, `phase2/step4_gms_probe.txt`, `phase2/step4b_update_files.txt` | summarized here |
| E4 | `phase3/baseline_update_rows.txt`, `phase3/baseline_gservices_modules.txt` | summarized here |
| E5 | `phase3/phase3_report.md`, `phase3/p3_nopif_*.png`, `phase3/p3_inj_*.png`, `phase3/p3_inj_ui_*.xml`, `phase3/nopif_update_rows.txt`, `phase3/postrollback_update_rows.txt` | `screenshots/03–06-*.png` |
| E6 | `phase2/iota_route_matrix.md`, `phase2/pc_matrix_headers.txt`, `phase2/pc_matrix_raw.txt` | summarized here |
| E7 | `phase2/dataflow_forensics.md` §1, `phase2/evidence_nt1_recommendation_consumer.txt` | summarized here |
| E8 | `phase2/stock_ui_anatomy.md` §1.4 (Huawei FAQ citation) | summarized here |
| E9 | `phase2/iota_route_matrix.md` / GMS disassembly notes | summarized here |
