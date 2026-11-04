# Сборка и тестирование

Работайте из корня checkout в WSL/Linux. Пакет приложения: `io.zem.ibox`.
Минимальная версия ОС — Android 7.0 (API 24). Требуются Java 17, Android SDK 36 и Gradle Wrapper из проекта.
`ANDROID_HOME` или игнорируемый `local.properties` задают путь к SDK.

## Подпись

Применяется существующая внешняя конфигурация
`~/zem-android-sign/zem-signing.gradle` и `signing.properties` в том же каталоге.
Создайте собственную конфигурацию для своей копии проекта; не публикуйте пароли
или ключи. Для обновления уже установленной сборки требуется исходный ключ.
Проверяемый публичный сертификат указан в [signing-certificate.json](signing-certificate.json).
Релиз остаётся `debuggable=false`.

## Пять вариантов APK

```bash
python3 scripts/build-release.py
./gradlew :app:lintDebug
```

Скрипт собирает ARM64, ARMv7, x86, x86_64 и universal; каждый APK проверяется
на пакет, версию, подпись, разрешения, ABI, ZIP alignment и побайтное совпадение
библиотек/ресурсов с libVLC 3.7.7. Результаты: игнорируемый `release/`, включая
`SHA256SUMS.txt` и `release-manifest.json`. Подробные отчёты: `qa/reports/release/`.

Для отдельной сборки:

```bash
./gradlew :app:assembleRelease -Pzem.abis=arm64-v8a
python3 scripts/audit-release.py --abis arm64-v8a --variant arm64-v8a
```

Версия задаётся только в `app/build.gradle`. Каждое завершённое изменение
повышает versionName на один шаг и Android versionCode. Обновляйте CHANGELOG; README не содержит номера текущей версии. libVLC закреплён на 3.7.7; [происхождение](libvlc-provenance.json).

## Проверки

```bash
bash scripts/core-checks.sh
# ffmpeg и Python Pillow нужны только для синтетических тестовых видео.
python3 qa/prepare-fixtures.py
python3 qa/device_simulator.py
```

В другом терминале запустите собственный тестовый эмулятор и проверки:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pzem.abis=x86_64
bash scripts/verify-emulator.sh
python3 qa/run-playback-audit.py
python3 qa/run-live-audit.py
python3 qa/run-format-audit.py
```

Скрипты обращаются только к `emulator-5586`; физические устройства не затрагиваются.
Сценарий `speed_only` проверяет локальное и Wi-Fi воспроизведение на 4×, затем
возврат к 1×. Сценарий `readme_only` сохраняет реальные скриншоты приложения.
Оба передаются через `adb shell am instrument -w -e <scenario> true`.

Тестовый адрес `10.0.2.2` разрешён только в debug. Синтетические медиа создаются
в `qa/generated/fixtures/`, результаты — в `qa/reports/`. Эти каталоги,
`release/`, `build/`, кэши, логи и временные `runs/` исключены из Git.

## Важные ограничения

Сетевые сокеты привязываются к захваченному Wi-Fi отдельно; весь процесс
никогда не привязывается к сети регистратора. Отложенные действия проверяют
исходный CTP-сеанс. Задняя камера включается пользователем в профиле устройства.
Общие команды удаления и сброса заблокированы; форматирование проходит только
по отдельному подтверждаемому пути с проверкой ответа `FORMAT.frm=1`.

В репозитории остаются актуальные исходники, тесты, скрипты, документация и
скриншоты. Старые APK, baselines и выведенные из использования эксперименты
не должны возвращаться в Git. Коммиты используют нейтральное имя проекта.

## Android 7

`desugar_jdk_libs_nio:2.1.5` переносит java.time, java.nio.file и используемые
современные Java API на API 24. Локальные записи на API 24–28 публикуются в
общую папку Download/Zem iBox Drive после запроса разрешения на файлы;
на API 29+ используется MediaStore.Downloads без такого разрешения.
Разрешения READ/WRITE_EXTERNAL_STORAGE ограничены maxSdkVersion 28.

Сценарий `android7_only` запускается на чистой установке эмулятора API 24:
он проверяет выключенную диагностику, запрос разрешения, сохранение оригинала,
лайв, кадр, запись со звуком, внутренний плеер и обновление списка после удаления.
Для скриншотов: `python3 qa/prepare-readme-fixture.py`, затем запуск симулятора
с `ZEM_README_FIXTURE=1` и сценарий `readme_only`. Изображение дороги создано
программно; это демонстрация интерфейса, не запись с физического устройства.
