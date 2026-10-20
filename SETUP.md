# Gym Ledger — install and connect

## 1. Install the app

Copy `Gym-Ledger.apk` to your Android phone and open it from Files. If Android asks, allow that Files/browser app to install this APK. Requires Android 8.0 or later and Google Play services for Google connection.

You can log workouts immediately using the exercises preloaded from your tracker. Every entry saves on the phone. Tap **Finish workout** to queue it for the sheet. Google setup below is required before anything can upload.

## Updating to 1.1 (cardio)

Install this APK over the existing release app. Do not uninstall or clear storage. The update keeps existing strength drafts, entries, history, and pending uploads, and adds a cardio table through a version 1 → 2 database migration. The package and signing certificate are unchanged; no new Google scope is required.

The **Cardio** tab has been added to your original workbook (sheet ID `184253101`). It has space for 1,000 sessions. Keep its eight headers intact: Session ID, Date, Activity, Duration (min), Distance (km), Effort, Notes, Strength session ID. Do not delete/recreate the tab; if accidentally deleted, restore the original tab with Undo or sheet version history. Adding empty rows to the bottom increases cardio capacity.

## 2. Finish the one-time Google setup

The **Gym Ledger** Google Cloud project was created with **Google Sheets API enabled**. You reported completing the Google setup. If you already connected successfully, keep that configuration; use the checklist below only to check setup or reconnect. A physical phone upload has not yet been verified.

- Project ID: `snappy-way-512208-h4`
- [Open the project's Google Auth Platform](https://console.cloud.google.com/auth/overview?project=snappy-way-512208-h4)
- [Check Google Sheets API](https://console.cloud.google.com/apis/library/sheets.googleapis.com?project=snappy-way-512208-h4)

Once Google Auth Platform loads:

1. Choose **Get started** if prompted. Set the app name to **Gym Ledger** and use your Google account for support/developer contact.
2. Choose **External** audience and keep the app in **Testing** for personal use. Review any Google policy/terms acknowledgement yourself before accepting it.
3. Under **Audience → Test users**, add the Google account you will use on your phone. That account must have edit access to your existing workout tracker.
4. Under **Clients → Create client**, choose **Android**. Enter:

   | Setting | Value |
   | --- | --- |
   | Name | Gym Ledger Android |
   | Package name | `com.mahmoud.gymledger` |
   | SHA-1 certificate fingerprint | `26:26:20:A8:E1:74:E0:A7:62:81:4E:31:B0:79:85:20:5B:15:98:CF` |

5. Under **Data Access**, add the Google Sheets scope `https://www.googleapis.com/auth/spreadsheets`. Do not add Drive-wide scopes or create a service account/API key.
6. Save the configuration. Google may take a few minutes to recognize a newly registered Android client.
7. On your phone, connect Wi-Fi, open **Gym Ledger → Connection → Connect Google**, choose the correct account, and review Google's permission request.

**Permission scope:** Google describes this scope as access to all your spreadsheets. The app's code calls only the fixed workout workbook ID, but Google's permission itself is broader. An Android OAuth client is identified by package and signing certificate; no client-secret file or client ID needs to be pasted into the app.

Your tracker remains:
[Open the original tracker](https://docs.google.com/spreadsheets/d/1uTbsFmIRe_XM8ChOrIPOFjbuyuX8Ij06NvbNmM07oKk/edit)

## 3. At the gym

1. Select your workout day. Existing drafts show **Resume**.
2. Enter reps and kg, mark sets done, and expand **Effort / rest / note** when needed. Rest uses seconds.
3. Wait for **Saved locally** before leaving the app.
4. Tap **Finish workout**. Confirm if some sets are incomplete.
5. When you have Wi-Fi again, open the app. Completed workouts upload automatically. **History** shows both local and downloaded sessions.

You can browse history and log sets offline. Videos require internet. Sync does not run while the app is closed or on mobile data. **Sync / Retry** is available under Connection.

## Cardio

- Open **Cardio → Start cardio** for a standalone session. Choose Walking, Running, Cycling, Elliptical, or Other.
- Enter duration in minutes (required, greater than zero). Distance in km, effort, and notes are optional; decimal duration/distance are supported.
- Every edit saves offline. Use **Resume** in Cardio to reopen a draft; previous results are shown for the same activity without filling today's values.
- Tap **Finish cardio** to queue the session. Finished cardio appears under both Cardio history and the main History tab, with its upload status.
- After finishing a strength workout, tap **Add cardio after this workout**. This keeps that workout's date and stores its session link.
- Completed cardio uploads into one empty row of the Cardio tab only while the app is open on validated Wi-Fi. Retry checks the assigned row first; occupied/changed rows pause the upload instead of overwriting data.

## If something goes wrong

- **Google connection fails:** confirm the Android client has the exact package/SHA-1 above, your account is a test user, and Sheets API is enabled. The source's debug build has a different signing certificate; these release credentials apply to the supplied APK.
- **Waiting for Wi-Fi:** Wi-Fi must provide working internet; a captive-portal connection will not sync until its login is completed. Keep this app open.
- **Sheet cells changed / routine changed:** the app has paused rather than overwrite unexpected data. Restore the reserved cells or original exercise/set layout, then retry. Your workout stays local.
- **Sheet full:** the original template has 50 date blocks per workout tab. The app keeps additional workouts locally rather than adding columns or overwriting sessions.
- **Local save fails:** keep the app open, resolve available storage, and tap **Retry local save**. The pending edit remains queued in memory until it can be written.

Avoid simultaneous sheet edits while syncing. Uninstalling, clearing app storage, or losing the phone deletes unsynced workouts. Android backups and device-transfer backups are disabled for the app.

## Keep the signing backup

`Gym-Ledger-signing-backup.zip` contains the private signing key and its password. Keep it private and back it up securely. It is **not needed on the phone**; it lets you build future APK updates with the same identity without uninstalling the app. Never publish this archive.

The source archive excludes private signing files. Extract the signing backup into the source project's root only when rebuilding a signed release. See its README for build and test commands.

## Verification limits

Automated tests cover the sheet parser, retry/conflict handling, Room persistence, and the workout UI on an Android 15 emulator. Test writes and surrounding-cell checks used a separate copy of the sheet. Strength tabs were not changed for the cardio update. The update adds only the Cardio tab and its header/formatting; no sample sessions were added to your original workbook.

Google consent and a complete phone-to-Sheets upload still need verification on your phone after OAuth setup. Physical-phone testing has not been performed.
