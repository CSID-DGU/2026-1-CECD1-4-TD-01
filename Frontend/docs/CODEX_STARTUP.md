# Codex Startup Checklist

This project uses daily progress notes and date-stamped debug APK names. At the start of every Codex session:

1. Confirm today's date from the environment.
2. Read the latest `docs/YYYY-MM-DD-progress.md`.
3. Check `app/build.gradle.kts` for `versionName` and the `copyDatedDebugApk` output name.
4. If today's progress note does not exist, create `docs/YYYY-MM-DD-progress.md` from the latest note's status.
5. Keep the debug APK copy name in this format: `Counseling_MM_DD_vX.Y.Z_debug.apk`.
6. If behavior changes, update `versionName` according to the scope of the change. If only docs or notes change, keep the app version.
7. Before continuing feature work, scan the latest note's "next work" section and verify whether the existing code already covers any item.

Current handoff as of 2026-05-30:

- Latest completed progress note: `docs/2026-05-29-progress.md`
- Current in-progress note: `docs/2026-05-30-progress.md`
- Current app version: `1.0.0`
- Current dated APK name: `app/build/outputs/apk/debug/Counseling_05_30_v1.0.0_debug.apk`
- Main next step: build verification and real-device testing for model load, text counseling, immediate WAV recording input, Android speech recognition text input, session restore/switching, important memory save/delete, RAG-lite related-context injection, and Health Connect prompt injection.
- Secondary next steps: persist Health Connect prompt settings if desired, add session rename/delete, improve important memory UI, improve RAG-lite ranking, and consider splitting `MainActivity.kt`.

Build environment reminder:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug
```
