# Zem iBox Drive

Work in /home/user/projects/zem-ibox under WSL. The former Windows
project directory is retired; do not recreate or build there.

Android applicationId/namespace: io.zem.ibox. Display name: Zem iBox Drive.
Use the shared owner-managed key ~/zem-android-sign/signing.properties,
alias zem, subject/issuer CN=Zem, O=Zem, for both debug and release.
Never generate a replacement key or commit/print the keystore/passwords.

Use ./gradlew for Linux builds. Android SDK is configured in ignored
local.properties; default WSL SDK is ~/.cache/android-sdk.
The working RoadLink 0.1.3 snapshot in baselines/ is immutable history.
Legacy experiments in history/ are for reference, not the active app.
Keep destructive commands disabled unless the user explicitly changes that.
Only emulator-5586 belongs to this project; never operate on other emulators.
