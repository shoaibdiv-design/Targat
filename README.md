# Field Tracker (Android) — v1.9.1

Developed by Shobi. Works with the Odoo 18 module `hr_field_tracking`.

## Build the APK
1. Install Android Studio (any 2024 or newer version).
2. File > Open > choose this `FieldTracker` folder. Let Gradle sync (needs internet the first time).
   If Android Studio offers to upgrade the Android Gradle Plugin, accepting is fine.
3. Build > Build App Bundle(s) / APK(s) > Build APK(s).
4. The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the riders' phones
   (WhatsApp, USB or Google Drive) and install. Android will ask to allow "Install unknown apps".

For a signed release APK use Build > Generate Signed App Bundle or APK, and keep the keystore safe:
future updates must be signed with the same key.

## Rider setup (one time)
1. Manager: Odoo > Employees > open the rider > Field tracking tab > Create rider login.
   Note the username and temporary password, plus the server address and database shown on the tab.
2. Rider: open Field Tracker, enter the server address (e.g. http://203.0.113.10:8069), database name,
   username and temporary password, tap Log in, then choose their own password.
3. Tap "Allow running in background" and accept. On Xiaomi, Oppo, Vivo, Realme and Infinix phones
   also set Settings > Apps > Field Tracker > Battery to "No restrictions" and turn on Autostart.

Forgotten password: the manager taps Reset password on the employee and gives the rider a new temporary one.

## Managers
A user with the Field Tracking Manager role logs in to the same app and gets the manager screens
(dashboard, live map, attendance with late riders, riders, send messages and tasks). Log out from the top bar.

## Daily use
Company vehicle: if a vehicle is issued to you in Odoo, the app asks for the odometer (km) when you start and end a shift.
Inbox tab: messages and tasks from the office. Messages: tap OK, read. Tasks: tick the checklist, add a note,
Complete and submit. Works without internet; it is sent when the connection is back.
Attendance: tap Punch in when starting work and Punch out when leaving. Works without internet too.
Start shift -> location is sent every 30 s (set in Odoo) even with the screen off.
Without internet, locations are stored on the phone and sent when the connection is back.
End shift -> tracking stops.

Needs Android 8.0 or newer with Google Play services.

## Build the APK without Android Studio (GitHub, free)
1. Create a free account at github.com and a new private repository.
2. Upload all files of this folder (including the hidden `.github` folder) to the repository.
3. Open the Actions tab. "Build APK" runs automatically (about 5 minutes).
4. Open the finished run and download `FieldTracker-apk` under Artifacts. Unzip it to get `app-debug.apk`.
