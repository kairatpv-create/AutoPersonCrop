# Organizer Pro mobile preparation

`android/` is the recovered **existing** Organizer Pro 0.9.38 (71) application,
not a new application. `BASELINE_PROVENANCE.json` records all 42 recovered file
hashes and the original Git commit. The first recovery commit is an unchanged
baseline; subsequent preparation changes are ordinary diffs against it.

Historical archives, patches and build workflows remain in `organizer/` and
`.github/workflows/`. They continue to reconstruct the old build independently.
The preparation workflow builds `mobile/android` directly, without replaying
the historical patch chain. A Gradle installation is currently required: the
old source archive did not include a complete executable Gradle wrapper.

The Android package remains `kz.kairat.organizer`; `organizer.db` remains schema
9. No database migration, production API write, signing-key rotation or release
is part of this preparation branch. Version metadata remains 0.9.38 (71) until
the next release is deliberately versioned and signed with the original key.

The application is **not ready for store publication**. See the audit under
`docs/mobile-readiness/` before enabling additional clients against production.
Host audit results do not replace Android/iPhone device tests.
