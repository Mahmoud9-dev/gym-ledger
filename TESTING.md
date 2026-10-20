# Gym Ledger 1.1 verification

Verified on 21 October 2026.

## Passed

- Debug and signed release APK builds; release certificate matches version 1.0 (SHA-1 26:26:20:A8:E1:74:E0:A7:62:81:4E:31:B0:79:85:20:5B:15:98:CF). Version code 2 / version name 1.1.
- Android lint: zero errors; dependency-version advisories and optional URI convenience warning remain.
- All 23 JVM tests (10 sheet codec, 8 strength sync, 5 cardio). Cardio tests cover required positive duration, decimal minutes/km, optional fields, literal notes, import, reserved rows, same-date sessions, Wi-Fi gating, lost-response duplicate prevention, full sheets, changed headers, and occupied-row conflicts.
- All five Android 15 / API 35 emulator tests: **OK (5 tests)**. Includes the full strength logging flow and adding linked cardio; cardio input/effort/notes, screen recreation, finishing, offline history; database reopen and upload assignment retention; completed-session immutability; import deduplication; and a real version 1 → 2 Room migration preserving an existing strength draft and its set values.
- The emulator was updated with install -r, without clearing app data or uninstalling.
- Release signature verification passed. The personal signing key was retained; private signing files are excluded from the source archive.
- Added the Cardio tab (ID 184253101) to the original workbook: eight headers, 1,000 empty session rows, frozen header, readable column widths, wrapped notes. Verified metadata/cell values and inspected the native Google Sheets view at 100%. No sample sessions were written to the original workbook, and strength tabs were not edited.
- Inspected the cardio session preview. Home/strength/cardio screenshots were captured by Compose tests.

## Manual verification still required

- A full upload using the APK's Google authorization on your physical phone remains unverified. The emulator is AOSP without Google Play services and cannot verify Google sign-in or actual authenticated app uploads. This update does not establish the cause of the previously reported phone sync issue.
- You reported completing the Cloud setup. If uploads fail, report the exact Connection-screen message and pending-session error after Sync / Retry on Wi-Fi. Do not uninstall or clear storage while uploads are pending.
- The app assumes one phone and no simultaneous sheet edits; Sheets values writes cannot provide transactional conflict protection.
- Cardio capacity is initially 1,000 rows. Additional empty rows may be added to the Cardio tab. Keep its original sheet ID and all eight headers. Strength tabs retain their existing 50 blocks per routine.
- Uninstalling or clearing storage loses unsynced local data. Install the release APK over the previous signed release.

## Earlier strength integration check

Version 1.0 used an isolated sheet copy to verify exact date/set writes, literal notes, and unchanged neighboring formatting/formulas/validation through the Google Drive connector. That was not a physical APK upload. The isolated test copy remains separate from your workbook:
https://docs.google.com/spreadsheets/d/1ulZiB1oi4t5Cftdxo6uQDicFMR5SoYygh111e30xsFg/edit
