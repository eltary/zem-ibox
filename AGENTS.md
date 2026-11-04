# Zem iBox

Work from the root of this repository under WSL/Linux. Do not create a
second project checkout in the former Windows workspace.

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
Keep only active sources, tests and documentation in Git. Baselines, retired
experiments, APKs and generated reports were removed at the user's request.
Use ignored release/ for distributable builds, qa/generated/ for fixtures,
qa/reports/ for test results, and runs/ for temporary private task files.
Do not commit personal workstation paths, personal contact details or delivery
receipts. Attribute commits to the owner’s public GitHub profile using the
GitHub-provided noreply address; never publish a private email. Write commit
messages in Russian.
Keep destructive commands disabled unless the user explicitly changes that.
On 04.11.2026 the user explicitly enabled DVR microSD formatting. Keep it behind
its dedicated confirmation and captured-session path; generic FORMAT, file
deletion and factory reset stay blocked in default builds.
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
  синхронизировать `versionName`, CHANGELOG и имена выдаваемых APK;
  проверки должны подтверждать версию именно собранного файла.
- Android `versionCode` всегда увеличивать для нового выпуска того же пакета,
  независимо от отображаемой версии. При сбросе `versionName` с `0.1.4` на
  `0.0.1` используется `versionCode = 6`, чтобы сохранить обновление поверх
  уже выданной сборки с кодом 5. Повторная сборка код не повышает.
- Каждый новый выпуск записывать в `CHANGELOG.md`. Исторические версии,
  baseline, отчёты и ранее выданные APK не перенумеровывать задним числом.

## UI, playback and compatibility

- Keep preview at the top and use grouped control tabs; switching camera
  control sections must preserve the active Surface and stream.
- Rear-camera controls default to hidden. Device description quality arrays
  do not prove physical camera presence; store the user's choice per device.
- Device compatibility is protocol/capability based. Do not advertise untested
  brands or full original-app parity; maintain docs/COMPATIBILITY.md.
- DVR HTTP playback uses bundled libVLC through a private Wi-Fi-bound relay.
  Never bind the entire process to Wi-Fi for native playback. Phone videos open in the internal player by default;
  the row menu retains the system chooser and external VLC.
- libVLC is pinned to 3.7.7. Preserve LGPL notices/source links and review any
  native dependency change. Published releases include separate arm64-v8a, armeabi-v7a, x86 and x86_64
  APKs plus a universal APK;
  pass -Pzem.abis=x86_64 for this project's emulator QA builds.
- No letters (especially Z) in the launcher icon. Keep adaptive and monochrome
  versions, and inspect the real Android icon at small size.
- The launcher brand mark must be an abstract road/motion symbol with no
  camera housing, lens, eyes or face. This user correction supersedes the
  former camera/road direction. Keep color and monochrome artwork aligned.

## Visual system

- Use Ui.java for shared colors, native widgets, line icons, outlines,
  typography and selection states. Keep the graphite/cyan/mint palette
  consistent with the launcher artwork. Prefer native drawables over Unicode
  symbols for navigation and action icons.
- Camera preview stays first; its controls are grouped in a segmented bar.
  Keep Android insets, readable disabled/empty states and touch targets.
- Separate Settings into DVR and App sections. Do not recreate a long mixed
  list of unrelated buttons on the live page.
- Inspect actual Android screenshots at normal size and 320dp with font 130%.
  Visual updates must preserve stream/surface continuity and existing actions.

## Usability

- Give archive loading, failures, no card, empty folders and filtered results
  distinct messages and an actionable recovery path. Ignore stale results
  after the session/camera changes; preserve the existing list on refresh failure.
- Every media row has a visible actions menu. Long press is only a shortcut.
- Show elapsed time while recording to phone. Ask before user navigation,
  quality/camera changes, fullscreen or disconnect ends that recording.
  Lifecycle interruption still finalizes the file automatically.
- Text action rows use wrap_content with at least 48 dp touch targets.
  Check 320 dp at font scales 1.3 and 2.0; camera content scrolls as one page.
- Connection help must offer Wi-Fi selection and retry without requesting
  SSID/location permissions or creating an automatic reconnect loop.

- Name recording destinations explicitly: «На видеорегистратор» and
  «На телефон». Do not use the ambiguous «На карту» for DVR recording.

- Gate DVR settings on the current connection and card operations on a known,
  present microSD. Live/phone saving need video, not SD. Allow stopping an
  existing DVR recording after SD removal. Local files and connection setup
  stay available offline. Close obsolete device dialogs and bind queued
  commands/file reads to their captured session before sending anything.

- Archive/phone thumbnails load off the UI thread with bounded read/cache sizes.
  Keep DVR reads on the captured Wi-Fi/session; pause thumbnail work while
  the player is foreground, and ignore callbacks for recycled rows.
- In-player previous/next/list navigation follows the filtered video snapshot,
  excluding photos. Never pass a large archive through Intent extras. A DVR
  queue cannot migrate to a new connection; phone queues work offline.
- On 04.11.2026 the user changed the phone video default to the internal
  sequential player. The Android chooser and external VLC remain in the menu.

- Archive playback QA must verify changing visible video pixels, not only an
  advancing clock or a detected track. Include a long Full HD MOV with a tail
  index, seeks and output recovery. Keep the TextureView frame watchdog bounded;
  honour pauses/buffering and retain position/speed when changing decoder.
- Wi-Fi playback relay validates file size/ranges, bounds upstream reads and
  resumes read failures without repeating bytes. Consumer seek/cancellation is
  distinct from a failed device read. Never change networks or sessions silently.

## User-facing documentation and defaults

- README deliberately has no current app version number; it links to the latest release.
- Keep README, release descriptions and CHANGELOG readable for ordinary users.
  Do not include test counts, release audit output, versionCode, signing-key continuity,
  personal phone models or internal cleanup reports there. Developer instructions
  and reproducible QA belong in docs/DEVELOPMENT.md and source scripts.
- Diagnostic file/logcat recording defaults to off; preserve an explicitly saved choice.
- Android minimum is API 24 (7.0). Guard newer platform APIs and test on API 24.
- The owner recreated the repository as public on 04.11.2026. Keep only main
  and the latest published release. Public GitHub profile attribution is allowed;
  personal contacts, workstation paths and private email addresses stay excluded.
