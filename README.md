# Gym Ledger

A personal Android workout logger for the five-day Built With Science tracker. Native Kotlin, Compose, Room, and Google Sheets API. Android 8.0 or later; Google Play services is required for Google authorization.

## Use

Choose a workout, enter each set, and mark it done. Effort, rest, and notes expand beneath each set. Every edit is committed to the phone's private Room database; **Saved locally** means all queued local writes have completed. Finish queues a read-only session for upload. Incomplete sets are allowed after confirmation.

Only finished workouts sync, and only while the app is in the foreground on validated Wi-Fi. Each API connection is bound to that network so it cannot fall back to mobile data. Opening the app, reconnecting Wi-Fi, or choosing **Sync / Retry** resumes synchronization. Existing histories and the last session remain available offline.

The initial routine cache was read from the user's workbook on 20 October 2026. Connect Google to refresh it and download sheet history. No account secrets or access tokens are bundled in the app. Tokens stay in memory; Google Play services handles subsequent authorization.

## Google setup

See the separately supplied `SETUP.md` for this build's signing fingerprint and Google Cloud project. Register an **Android** OAuth client for package `com.mahmoud.gymledger` and the release signing SHA-1, enable Sheets API, configure OAuth branding/audience, and add your account as a test user. The requested scope is `https://www.googleapis.com/auth/spreadsheets`.

Android AuthorizationClient resolves its OAuth client from the package and signing certificate. No web client ID, client secret, service account, Firebase configuration, or `google-services.json` is required.

## Build

Install JDK 17 and Android SDK platform 36. Point `ANDROID_HOME` to that SDK, or create an untracked `local.properties` containing `sdk.dir=/your/sdk/path`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:coreCoverage
./gradlew :app:connectedDebugAndroidTest
```

The last command needs a running Android emulator or connected test device. Device tests create isolated temporary databases and a local Push workout. Use a disposable device/emulator, not a phone containing workouts you wish to preserve.

For an updatable signed APK, extract the private signing backup into this source folder. This restores `signing.properties` and `signing/gym-ledger.jks`; neither is included in the source archive.

```sh
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. Without the signing backup, release output is unsigned; debug builds use a different certificate and require a separate Android OAuth registration to test authorization.

## Data and synchronization

- The workbook ID and the five original sheet IDs are deliberately fixed in `SheetsClient.kt`.
- The parser reads bounded ranges `A1:IS90`, maps the template's exercise/tutorial/set rows, validates five-column logging headers, and reads effort choices from their actual dropdown range. The template provides 50 session blocks per workout tab.
- A session stores its own routine snapshot. Changing exercise/set mappings on the sheet pauses upload of incompatible pending sessions. Renaming a tab is safe because sheet IDs remain stable.
- Each pending upload persists its block assignment before sending. Retries first compare the existing values with the complete expected session. A matching block is acknowledged without another write; a nonempty conflicting block is never overwritten.
- Writes use the values API with `RAW`: numeric reps/weights/rest, exact effort labels, literal notes, and an ISO calendar-date string. ISO dates remain readable without changing the template's formatting or interpreting notes as formulas.
- Date-only sessions and separate sessions on the same day are supported. Cached imports are keyed by sheet ID and block, not date. Imports never replace local drafts or pending uploads.
- Completed sessions are immutable in the app. Changing a routine in the sheet refreshes future sessions; in-progress sessions retain their snapshot.
- If a block conflicts or the template is full, the session remains local with an explanatory error. Retry after restoring the expected layout/cells or making space. No rows, columns, extra tabs, or app metadata are added.

**Single writer:** Sheets values writes do not provide compare-and-swap transactions. Avoid simultaneous edits from another phone or the browser while syncing. Structural edits and unsynchronized reinstalls are outside v1's guarantees.

**Storage:** Android backup is disabled. Uninstalling, clearing app storage, losing the phone, or losing its storage deletes unsynced sessions. Keep the signing backup private and preserve it for future updates.

## Sources

- [Android offline-first architecture](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [Google API authorization on Android](https://developer.android.com/identity/authorization)
- [Sheets API scopes](https://developers.google.com/workspace/sheets/api/scopes)
- [Sheets values batch update](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets.values/batchUpdate)
- [Android network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)

## Version 1.1: cardio

Adds standalone and post-strength cardio, decimal minutes and km, optional effort/notes, persistent drafts, offline history, and serial Wi-Fi uploads to the dedicated Cardio tab. Room migrates version 1 to 2 without replacing strength data. The release package/certificate are unchanged. See SETUP.md for use and the required eight-column Cardio schema.
