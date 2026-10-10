# Notes (Apple Notes-style) for Android

Native Kotlin / Jetpack Compose / Room app. Notes live in an on-device database.

## Build the APK
1. Install Android Studio (Ladybug or newer). Open this folder (File > Open).
2. Let Gradle sync (accept Gradle 8.9 / JDK 17 if prompted).
3. Plug in a phone with USB debugging, press Run. Or: Build > Build APK(s), then copy
   app/build/outputs/apk/debug/app-debug.apk to your phone and install it.

## Using it
- Folders screen: All Notes, your folders, Recently Deleted.
- Swipe right on a note to pin, left to delete. Deleted notes stay 30 days in Recently Deleted: tap to Recover.
- First line of a note is its title. Format bar: Title/Heading/Subheading/Body/Monospaced, checklist, bullet, dash, numbered list.
- "..." menu in a folder > Export as PDF saves the whole folder to Downloads.

## Added in v1.1
- Tap a checkbox in a note to tick / untick it.
- Locked notes: editor "..." menu > Lock Note. Unlocking uses your fingerprint, face or screen-lock PIN/pattern.
  Locked text is stored encrypted with a key in the Android Keystore. Needs a screen lock set on the phone.
- Backup: Folders screen "..." menu > Back up all notes (choose where to save the .json file, e.g. Drive or Files)
  and Restore from backup (adds notes that aren't already on the phone).
  Locked notes are NOT included in backups, because their key lives only on this phone.

## Added in v1.2
- Bold, italic and underline buttons (B / I / U) in the format bar. Select text and tap one, or tap with no
  selection to turn it on for what you type next. The button lights up when the style is active at the cursor.
  Styles also show up in PDF exports. Each style ends at the end of its line.

## Build without Android Studio (free, GitHub Actions)
1. Make a free GitHub account and create a new repository.
2. Upload all files from this folder (including the hidden .github folder) to the repository.
3. Open the repository's Actions tab. The "Build APK" run starts by itself (or press Run workflow).
4. When it shows a green check, open the run and download the "notes-apk" file at the bottom. Unzip it.
5. Copy app-debug.apk to your phone and install it (allow "Install unknown apps" for your file manager or browser).
