# Zem iBox

Work in /home/user/projects/zem-ibox under WSL. The former Windows
project directory is retired; do not recreate or build there.

Android applicationId/namespace: io.zem.ibox. Display name: Zem iBox.
Use the existing shared owner-managed key ~/zem-android-sign/signing.properties
for both debug and release. On 20.10.2026 the user explicitly requested the
debug certificate supplied in that directory instead of the initial Zem key.
Pin the actual public certificate in docs/signing-certificate.json and verify
the built APK against its SHA-256; never silently accept another signer.
Release builds must remain non-debuggable.
Never generate a replacement key or commit/print the keystore/passwords.

Use ./gradlew for Linux builds. Android SDK is configured in ignored
local.properties; default WSL SDK is ~/.cache/android-sdk.
The working RoadLink 0.1.3 snapshot in baselines/ is immutable history.
Legacy experiments in history/ are for reference, not the active app.
Keep destructive commands disabled unless the user explicitly changes that.
Only emulator-5586 belongs to this project; never operate on other emulators.

## Версионирование — правило пользователя

- Первая версия — `0.0.1`. По явному указанию пользователя 20.10.2026
  нумерация Zem iBox начинается заново с `0.0.1`, затем `0.0.2`, `0.0.3` и далее.
- Каждый завершённый согласованный набор изменений, включая код, конфигурацию
  и документацию, повышает версию ровно на один шаг. Несколько файлов одного
  набора и промежуточные сохранения не дают отдельных повышений.
- Формат `major.minor.patch`, без ведущих нулей. `minor` и `patch` — от 0 до 99:
  `0.0.99 → 0.1.0`, `0.99.99 → 1.0.0`, `1.99.99 → 2.0.0` и далее.
  Старший разряд не ограничен 99. Не выбирать major/minor произвольно по SemVer.
- Обычная повторная сборка, проверка или запуск без изменений версию не повышает.
- Источник версии — `app/build.gradle`. До сборки и передачи нового выпуска
  синхронизировать `versionName`, README, CHANGELOG и имена выдаваемых APK;
  проверки должны подтверждать версию именно собранного файла.
- Android `versionCode` всегда увеличивать для нового выпуска того же пакета,
  независимо от отображаемой версии. При сбросе `versionName` с `0.1.4` на
  `0.0.1` используется `versionCode = 6`, чтобы сохранить обновление поверх
  уже выданной сборки с кодом 5. Повторная сборка код не повышает.
- Каждый новый выпуск записывать в `CHANGELOG.md`. Исторические версии,
  baseline, отчёты и ранее выданные APK не перенумеровывать задним числом.
