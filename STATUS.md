# Status

Текущий этап: Android 0.2.0, подготовлен к облачной debug-сборке.

Готово:
- выбор папки и persistable URI permission;
- рекурсивная очередь JPG/JPEG;
- SQLite-синхронизация очереди и восстановление после прерывания;
- pause/resume/stop;
- foreground mediaProcessing service + WakeLock;
- LiteRT YOLO11n GPU -> CPU fallback;
- умный crop под реальное соотношение экрана;
- строгие 5% безопасной зоны и округление только наружу;
- EXIF orientation mapping;
- strict lossless JPEG crop через libjpeg-turbo;
- byte-for-byte copy для полного кадра/кадров без людей;
- 20 000 randomized тестов геометрии + EXIF тесты;
- GitHub Actions pipeline для модели и APK.

Не считается завершённым, пока GitHub Actions реально не соберёт APK и он не будет проверен на устройстве.
