# Validation record — 2026-10-21

## Baseline and preservation

- Original Android HEAD: `69f6301d22472417da803e3f934769732b0cfdd2`.
- Original web/server HEAD: `3a28807ee98aa657b90bba5a35c776ead8c2145f`.
- Exact readable Android recovery commit: `bd2d800239645a2930a89a45bcc8a71efc76001e`.
- Preparation code commit: `9c32e63594bdb5c82223f7fab4a19d937a1a0d7e`.
- A Git bundle of the initial available refs was made before source changes.
- 42 baseline source files have hashes in `mobile/BASELINE_PROVENANCE.json`.
- Only `OrganizerUi.kt` differs from the recovered Android baseline. Database,
  backup, sync, settings, permissions, package and version files are unchanged.
- `personal-organizer-v1`, `personal-organizer-web-v1` and `main` were not moved.
- No device reinstall/clear, production auth/register/sync write, infrastructure
  mutation, payment operation, store upload or invitation was performed.

## Android compilation

[Actions run 39643357026](https://github.com/kairatpv-create/AutoPersonCrop/actions/runs/39643357026)
completed **successfully** on 2026-10-21, head
`9c32e63594bdb5c82223f7fab4a19d937a1a0d7e`.

Actual command: `gradle --no-daemon --build-cache -p mobile/android assembleDebug`.
Logs include `:app:compileDebugKotlin` and `BUILD SUCCESSFUL`. Checkout, Java/SDK,
Gradle, assembly and evidence upload steps all succeeded.

Artifact `organizer-mobile-preparation-debug-INTERNAL-DO-NOT-INSTALL`, ID
`12366382271`, was retained for 30 days. It uses temporary CI debug signing and
retains version metadata 0.9.38 (71). It is for compilation evidence, not an
update to install over the user's original-key APK. No new installable release
or ready-for-store binary is being delivered.

This validates compilation. Android UI screenshots, instrumentation, Android
upgrade/data preservation and iPhone tests were not run.

## Host Kotlin and API tests

| Suite | PASS | RISK_REPRODUCED | Harness failures |
|---|---:|---:|---:|
| Existing Kotlin code / SQLite JDBC | 4 | 8 | 0 |
| Existing Express API / in-memory pg adapter | 13 | 2 | 0 |
| Total | 17 | 10 | 0 |

`RISK_REPRODUCED` is a demonstrated defect, not an application safety pass.
The code and limitations are documented in `tools/audit/README.md`.

Detailed results:

- `evidence/jvm-baseline-results.json`
- `evidence/server-results.json`

## Preserved original APK

`Organizer-Pro-v0.9.38-original-key.apk`: version 0.9.38 / code 71,
`kz.kairat.organizer`, minSdk 26, targetSdk 35, debuggable=true.

Android apksig 8.10.1 verified v2 and v3 signatures successfully. APK SHA-256:
`ae45e46bbc45a35c4353c3145f14f1a1703994dde8c6e3ae80d7e13811160019`.
Certificate SHA-256:
`a0564fdcf4e365a0077cbb0f56cf4ddce9538a45ba772b1793ab3f549b8f90b2`.

`Button text size` is present in DEX; `Buttone` is absent. A literal string
check does not replace visually inspecting the user's specific screen.

ELF LOAD alignment for the existing graphics.path native libraries is 16384
bytes across all four packaged ABIs. Actual 16 KiB Android runtime/AAB ZIP
alignment tests remain pending.

## Server observation

`evidence/live-readonly.json` records only unauthenticated GET responses:
health 200, version 200, account/sync 401. There was an earlier timeout before
the successful health request. No uptime conclusion follows from this sample;
transport timings include this environment and cannot be used as Render p95.

Current plan, expiration, actual deployed commit and PostgreSQL backup inventory
remain unverified. The Render connector had no selected workspace. One available
workspace was listed (`My Workspace`); a selection question returned no answer.
No resource reads were attempted under an inferred workspace.

The exact consulted [render-monitor SKILL.md](skill://plugin_asdk_app_6a624c56bfe081918f7544f7d58f6faf/render-monitor/SKILL.md)
requires “establish access and verify the intended workspace”; its
[render-access.md](skill://plugin_asdk_app_6a624c56bfe081918f7544f7d58f6faf/render-monitor/references/render-access.md)
states “Check the selected workspace before reading or changing resources.”
The source blueprint's `free` plans are not treated as current billing facts.

## iOS environment

`bash -n tools/ios/preflight.sh`: passed syntax validation.
Running the script in this Linux environment exited 2 with the expected
message that macOS/Xcode are required. No Xcode compilation, simulator run,
signing, provisioning, IPA creation, TestFlight upload or App Store Connect
mutation occurred. iOS identity and implementation/build test plans are prepared;
an actual functioning iOS application remains to be implemented.

## Release gate

Publication is blocked by the open P0 data risks and P1 store requirements.
Safe Android UI preparation compiling successfully does not close those risks.
The next work is backup/restore hardening, account isolation and non-destructive
sync conflict handling, tested with independent clients and real isolated
PostgreSQL before any production change.
