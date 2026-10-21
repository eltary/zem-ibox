# Разработка Zem iBox

Основной обзор и инструкция пользователю: [README](../README.md).
Рабочая папка — `/home/user/projects/zem-ibox` в WSL; прежняя папка Windows
выведена из использования. Пакет/namespace: `io.zem.ibox`, имя: Zem iBox.

## Сборка

Требуются Java 17 и Android SDK. Путь SDK задаётся в игнорируемом
`local.properties`; в рабочей среде это `~/.cache/android-sdk`.
Используйте Gradle Wrapper проекта.

```bash
./gradlew :app:assembleRelease :app:lintDebug
python3 scripts/audit-release.py
```

Готовый проверенный APK: `release/Zem-iBox-<versionName>.apk`.
По умолчанию включены ARM64 и ARMv7. Выпуск остаётся `debuggable=false`.
Аудит сверяет версию, имя, пакет, pin сертификата, разрешения, собственные
модули, происхождение native-библиотек libVLC и выравнивание APK.

## Подпись и версии

Используется существующий общий ключ владельца из
`~/zem-android-sign/signing.properties`; debug и release подписываются им.
Публичный pin: [signing-certificate.json](signing-certificate.json).
Не заменяйте ключ и не добавляйте приватную конфигурацию/пароли в Git.
Рабочий сертификат выбран владельцем; release не становится от этого debug.
Проверка подписи APK не является заключением Google Play Защиты.

Источник версии — `app/build.gradle`. Один законченный набор изменений
повышает версию на один шаг. Формат base-100: `0.0.99 → 0.1.0`,
`0.99.99 → 1.0.0`. Android versionCode всегда растёт. Синхронизируйте
README.md, README.txt, CHANGELOG и имя выпуска. Полные правила: [AGENTS.md](../AGENTS.md).

## Локальные проверки

Только `emulator-5586` принадлежит этому проекту. Физические телефоны
и чужие эмуляторы не используются скриптами проекта.

```bash
# Проверки транспортов, сборки кадров и очереди без Android.
bash scripts/core-checks.sh

# В отдельном терминале — локальный CTP/HTTP/TCP/UDP симулятор.
python3 qa/device_simulator.py

# Проверка приложения на уже запущенном emulator-5586.
bash scripts/verify-emulator.sh
```

Для отдельного Android-сценария:

```bash
./gradlew -Pzem.abis=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5586 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5586 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5586 shell am instrument -w -e media_only true io.zem.ibox.test/io.zem.ibox.SmokeTest
```

Другие флаги сценариев: `availability_only`, `ux_only`, `ui_only`.
Без флага запускаются общие интеграционные проверки. Тестовый адрес
`10.0.2.2` разрешён только debug-сборкой. Снимки и отчёты 0.0.10 —
[qa/media-0.0.10](../qa/media-0.0.10/).

## Архитектура

| Часть | Назначение |
| --- | --- |
| MainActivity / Ui | Основные экраны, состояние подключения и единый визуальный стиль |
| JieliSession / CtpClient | Сеанс устройства, CTP и привязанный к Wi-Fi HTTP |
| TcpMediaReader / FrameAssembler / LiveStream | Приём, сборка и декодирование лайва |
| LiveFrameQueue / PhoneRecording | Ограниченная очередь предпросмотра и запись на телефон |
| TransferService / Library | Очередь скачивания, продолжение и MediaStore |
| MediaThumbnails | Последовательная загрузка миниатюр и ограниченный кэш |
| PlaybackQueue / PlayerActivity / WifiMediaProxy | Снимок списка видео, VLC и приватный Wi-Fi relay |
| Diag | Журнал, очистка, отключение и скрытие паролей |

Native-воспроизведение не должно привязывать к Wi-Fi весь процесс.
Сетевые действия и результаты привязаны к исходному сеансу;
отключившееся устройство не может передать отложенную команду новому сеансу.

Задняя камера включается пользователем в профиле конкретного устройства.
Команды удаления, форматирования и сброса заблокированы
`BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS=false`.

## История и зависимости

`baselines/` и `history/` хранят предыдущие исходники и эксперименты.
Они не являются активным приложением и не перенумеровываются задним числом.
libVLC закреплён на 3.7.7; происхождение и хэши —
[libvlc-provenance.json](libvlc-provenance.json), лицензии — в assets/.
Фирменная иконка — [design/icon/road-v3](../design/icon/road-v3/).
