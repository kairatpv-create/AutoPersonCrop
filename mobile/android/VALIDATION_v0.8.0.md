# MyOrganizer 0.8.0 — validation

## Compatibility baseline recovered from stable 0.7.4
- applicationId / namespace: `kz.kairat.organizer`
- versionName: `0.8.0`
- versionCode: `23` (stable 0.7.4 was build 22)
- DB name: `organizer.db`
- DB schema remains version 4; no destructive migration.
- Legacy tables/models for notes, tasks, projects, money and reminders are kept.
- Legacy preference keys are retained: `organizer_settings`, `dark_theme`, `font_scale`, `bold_text`, `backup_mode`, `backup_email`, `last_backup_at`, `pin_enabled`, `pin_hash`, `pin_salt`.
- Legacy project records remain in DB/backup for compatibility but Projects are hidden from visible UI in 0.8.0.

## 0.8.0 checks
- Voice recording: MediaRecorder -> MPEG-4/AAC `.m4a`.
- Saved voice block: Play/Pause, waveform, duration; no microphone icon.
- Note editor: Attachment + Record buttons are equal-width (50/50); Save is full width.
- Voice files are included in JSON backup payload with MIME, duration and Base64 data.
- RU and KK UI dictionaries are built in and selectable in Settings.
- Old Speech-to-Text implementation is not used by app source.
- File/photo attachments are retained for notes and tasks.
- Reminders are retained for notes and tasks; reboot rescheduling is retained.
- PIN lock, change PIN and verified disable flow are retained.
- Main visual identity retained: dark mode support, blue top bar, notification/lock actions, yellow accent action.

## Structural validation performed
- All XML files parse successfully.
- Kotlin source passes a parser-level `kotlinc` pass: no `expecting`, unclosed token, missing brace, unexpected-token, or parser syntax diagnostics were found.
- Source-level gates confirm package/version/DB/preferences, RU/KK, voice recording/player, backup inclusion, hidden Projects UI, equal editor buttons and full-width Save.
- ZIP integrity is tested after packaging.

## Compile gate
A full Android Gradle compile is not claimed in the current execution container because it has JDK/Kotlin but no Android SDK, AAPT2 or Android Gradle dependency cache. The archive includes `BUILD_TEST_APK.bat`, which detects the Android Studio SDK and bootstraps Gradle 8.11.1 when needed, plus a GitHub Actions workflow for `assembleDebug`.
