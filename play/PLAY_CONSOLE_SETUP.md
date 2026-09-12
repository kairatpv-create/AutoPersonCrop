# Auto Person Crop — Google Play Console setup

Prepared for the Android 16 / API 36 Play requirements current on 12 September 2026.

## Package and release format
- Package/applicationId: `kz.autopersoncrop`
- targetSdk: 36 (Android 16)
- minSdk: 26 (Android 8.0)
- Publishing format: Android App Bundle (`.aab`)
- Current native ABI: `arm64-v8a`
- Native build: NDK 28.2.13676358
- 16 KB page-size compatibility is checked in CI with bundletool.

## Data safety draft
Based on the current source code and dependency set:
- Does the app collect user data? **No**.
- Does the app share user data with third parties? **No**.
- Photos are accessed only after the user chooses a folder through Android Storage Access Framework.
- Photos, filenames, EXIF metadata, folder URIs and processing results are not transmitted off-device by the app.
- The app has no `INTERNET` permission.
- No advertising SDK.
- No analytics SDK.
- No account registration.
- No cloud storage.
- Android app-data backup is disabled.

Re-check this declaration before every Play release if dependencies or app behavior change.

## Foreground service declaration draft
Foreground service type: `mediaProcessing`.

### Functionality
The user selects a folder and explicitly starts batch photo processing. The app detects people locally and crops JPEG photos while preserving originals. Large batches can take significant time, so processing continues as a user-visible foreground operation when the app is backgrounded or the screen turns off.

### Why immediate execution is needed
The user explicitly starts a batch and expects selected photos to be processed continuously. Deferring the work can make progress unpredictable and may leave a partially completed batch until the system chooses to run it.

### Impact if interrupted
An interruption pauses/stops the current batch. Completed outputs remain valid, queue state is persisted locally, and the user can restart processing; completed files are skipped.

### Reviewer video script
Record a short screen video showing:
1. Launch Auto Person Crop.
2. Tap `Выбрать папку` and choose a test photo folder.
3. Tap `Обработать всё`.
4. Show the ongoing foreground notification with progress.
5. Send the app to background / turn screen off briefly.
6. Return to the app and show that processing/progress continued.
7. Tap `Пауза`, `Продолжить`, and optionally `Остановить`.
8. Open the `CROP` folder and show created results while originals remain unchanged.

## Privacy policy
Public policy source is stored at repository root as `PRIVACY_POLICY.md` and the same core disclosure is available inside the app under `Конфиденциальность`.

Before production submission, use a stable public HTTPS URL for the privacy policy in Play Console. Do not use a temporary CI artifact URL.

## Store listing draft (Russian)
### App name
Auto Person Crop

### Short description
Офлайн-кадрирование фото по людям без изменения оригиналов.

### Full description
Auto Person Crop автоматически находит людей на фотографиях и создаёт аккуратно кадрированные копии для экрана телефона.

Приложение работает полностью локально на устройстве. Вы выбираете папку, запускаете пакетную обработку и получаете результат в отдельной папке CROP. Оригинальные фотографии не изменяются.

Основные возможности:
- автоматическое обнаружение людей на фото;
- пакетная обработка больших папок;
- поддержка групповых и вертикальных/горизонтальных кадров;
- безопасные отступы вокруг человека;
- строгая JPEG-обрезка без повторного сжатия там, где она технически возможна;
- пауза, продолжение и безопасный повторный запуск;
- сохранение прогресса после прерывания;
- полностью офлайн-обработка без загрузки фотографий в облако.

## Content declarations draft
- Ads: **No**.
- Account creation: **No**.
- News app: **No**.
- Primary purpose: photo utility / media processing.
- Target audience: general audience; not specifically designed for children.
- Restricted content: none expected from app-provided content; users process their own local photos.

## Release signing
Do not publish an AAB signed with an ephemeral/debug key.

Before the first Play upload:
1. Enable Google Play App Signing.
2. Create one persistent upload key/keystore.
3. Keep the private keystore and passwords outside the public repository.
4. Configure CI secrets or sign from a protected local environment.
5. Back up the upload key securely.

The current Play CI workflow intentionally builds an unsigned release AAB for structural/compatibility validation until the permanent upload key is created.

## Final pre-upload checklist
- [ ] Device testing completed on several real Android phones.
- [ ] Batch processing tested with interruption/resume.
- [ ] Android 15/16 foreground-service timeout behavior tested.
- [ ] `bundleRelease` succeeds.
- [ ] bundletool validation succeeds.
- [ ] `PAGE_ALIGNMENT_16K` confirmed.
- [ ] Permanent Play upload key created and backed up.
- [ ] Signed release AAB created.
- [ ] Privacy policy hosted at stable HTTPS URL.
- [ ] Data safety form completed.
- [ ] Foreground-service declaration completed with reviewer video.
- [ ] Store icon, feature graphic, phone screenshots and listing text uploaded.
- [ ] Content rating / target audience / ads declarations completed.
- [ ] If account is a new personal Play developer account, closed-test requirement completed before production access.
