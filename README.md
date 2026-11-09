# AnyAuto

[![Download APK](https://img.shields.io/github/v/release/GitMag/AnyAuto?label=download%20APK&logo=android&color=success)](https://github.com/GitMag/AnyAuto/releases/latest)
[![Support on Ko-fi](https://img.shields.io/badge/support-Ko--fi-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/forsblomdev)

<p align="center"><img src="docs/icon.svg" alt="AnyAuto icon" width="128"></p>

AnyAuto puts **ordinary phone apps on the Android Auto screen**: a browser, a video app,
Settings, or anything else with a launcher icon. You don't change the apps. AnyAuto runs them
on a car-sized virtual display, draws that display onto the car screen, and sends the car's
taps, drags, flings and pinches back to the app as touches.

It uses [Shizuku](https://shizuku.rikka.app/), so you don't need root or a computer in the
car. It has no internet permission, no account and no analytics.

**[Download the APK](https://github.com/GitMag/AnyAuto/releases/latest)** from the latest release.

<p align="center"><img src="docs/screenshots/car-app.png" alt="Firefox showing a Wikipedia page on the Android Auto screen through AnyAuto" width="800"></p>

## Screenshots

<p align="center">
  <img src="docs/screenshots/phone-home.png" alt="AnyAuto's home screen: Shizuku is running, seven apps chosen for the car, each with a Try button" width="260">
  &nbsp;
  <img src="docs/screenshots/phone-picker.png" alt="Choose apps: search at the top, the apps in the car ticked, other apps below" width="260">
</p>

<p align="center"><em>Home: the apps you've chosen for the car. Choose apps: tick the apps to show in the car.</em></p>

<p align="center"><img src="docs/screenshots/car-dashboard.png" alt="Android Auto's dashboard with the app in AnyAuto's card on the left and the media card on the right" width="800"></p>

<p align="center"><em>On the Android Auto dashboard, next to the media card (blurred here).</em></p>

<p align="center"><img src="docs/screenshots/phone-preview.png" alt="Try: Chrome at car size on the phone, with Close, Direct, Card and Back on a rail at the right" width="800"></p>

<p align="center"><em>Try: an app at car size on the phone, with the same display and gestures as the car.</em></p>

## What you need

- An Android phone with Android 10 or newer.
- **Shizuku**, installed and running. On Android 11 and newer it starts on the phone itself.
  On Android 10 it needs a computer after every reboot.
- **[UnknownAuto](https://github.com/GitMag/UnknownAuto)** to install AnyAuto for the car.
  Android Auto runs only car apps that were installed from Google Play, and UnknownAuto
  installs an APK so that it counts as a Play install. The desktop head unit doesn't check
  this, so a plain `adb install` is enough there.

## Install

1. Download **AnyAuto-<version>.apk** from the
   [latest release](https://github.com/GitMag/AnyAuto/releases/latest) on the phone.
2. Open UnknownAuto, tap **Load APK or XAPK…**, pick the APK and tap **Install**. For the
   desktop head unit, a normal install is enough.
3. Add AnyAuto to Android Auto's launcher (Android Auto settings → *Customize launcher*).

Or build it yourself with the plain Android SDK tools (no Gradle; build-tools 35 and the
android-35 platform):

```
./build.sh            # build anyauto.apk
./build.sh install    # build, adb install -r, then open it (ANDROID_SERIAL picks the device)
```

The first build downloads the libraries (the Car App Library and the Shizuku API) into
`libs/` and creates a local signing key in `keys/`. An APK you build yourself is signed with
your own key, so it can't update the release APK: uninstall that one first.

## Set up (once)

1. **Start Shizuku.** Follow *Set up Shizuku* in
   [UnknownAuto's README](https://github.com/GitMag/UnknownAuto#set-up-shizuku-once). You
   pair the phone with itself over Wireless debugging, and no computer is needed. Android
   stops Shizuku at every reboot, so you have to start it again after one.
2. **Allow AnyAuto.** Open AnyAuto and tap **Allow** on the Shizuku card, then choose
   **Allow all the time**.
3. **Choose apps.** Tap **Choose apps** at the bottom and tick the apps you want in the car.

AnyAuto's home screen shows a card whenever something still needs doing: Shizuku missing,
stopped or not allowed, or AnyAuto not installed for the car.

## Use

**On the phone:** **Try** next to a chosen app opens it at car size on the phone. It is a
landscape preview with the same display and gesture handling as the car. The rail on the right
has **Close**, **Direct** (raw touches instead of car gestures), **Card** (the size of the
dashboard card) and **Back**. **Help** is in the ⋮ menu.

**In the car:** open AnyAuto and pick an app from the grid. The app you have open is marked
**Open**. The strip at the top has **Apps** (back to the grid) and **Back** (the app's own Back).
For drags, turn on the pan button in the map strip. AnyAuto also works on the Android Auto
dashboard: the app is drawn into the dashboard card.

AnyAuto is listed as a navigation app because that is the only Android Auto category that
gets the whole car screen to draw on. It never starts navigation, so Google Maps keeps
directions and "navigate to" requests.

## While an app is on the car screen

- **The phone stays awake with its screen off.** The car display can only run while the phone
  is awake, so AnyAuto keeps the phone awake and turns its panel off. The power button still
  puts the phone to sleep, and the car screen then pauses until you tap it. When Android Auto
  stops showing AnyAuto, the phone goes back to how it was (asleep if AnyAuto woke it). If you
  move between the grid, the app and other car screens, AnyAuto waits 8 seconds before it lets
  go, so the phone doesn't keep lighting up.
- **Apps on the car screen skip the phone's lock,** like Android Auto's own apps. Dark doesn't
  mean locked: if the phone was unlocked, it stays unlocked.
- **Some windows show black.** Apps that protect their windows show black on the car screen.
  Examples are banking apps, DRM video (Netflix and other streaming services) and Chrome
  incognito tabs. Non-DRM video, such as YouTube, works.
- **The keyboard opens on the phone,** not on the car screen.
- **One car display:** **Try** is unavailable while AnyAuto is on the car screen.

**Drive safely.** AnyAuto doesn't lock anything while the car is moving. Use video and other
distracting apps only when the car is parked. Follow the traffic laws where you drive.

## How it works

```
Android Auto (car screen)                AnyAuto app process              Shizuku process (shell user)
 NavigationTemplate surface ──Surface──▶ MirrorScreen ─▶ Mirror ──AIDL──▶ DisplayService
 onClick/onScroll/onFling/onScale ─────▶ Touch (gestures → MotionEvents) ─▶ inject into the display
                                                                          VirtualDisplay (trusted, public,
                                                                          always unlocked, own focus)
                                                                          └─ any app, started on it
                                                                          PhonePower: phone awake, panel off
 Compositor (GL): the display's frames → the visible part of the car surface
```

- **Shizuku:** only the shell user (or the system) can create a *trusted* virtual display
  that other apps may start on, and inject input into it. scrcpy's `--new-display` works the
  same way. Shizuku runs AnyAuto's `DisplayService` with the shell user's rights, on the phone
  itself.
- **No video encoding:** the car's Surface goes over binder to the Shizuku process, and the
  display draws into it there, so frames are never copied or encoded. An app that is already
  open on the phone moves to the car display.
- **Gestures (`Touch`):** Android Auto doesn't give a navigation app raw touches. It gives a tap
  position, scroll *distances*, a fling velocity and pinch steps, and `Touch` rebuilds touch
  streams from them. A drag starts in the middle of the visible area, first moves past the
  touch slop so no distance is lost, and ends 150 ms after the last step or at the screen edge.
- **Fitting the car screen (`Compositor`):** Android Auto shows only part of a navigation app's
  surface: the dashboard or launcher card, or everything below the action strip. The display
  draws into a texture, and OpenGL draws each frame into the car's stable area. The rest stays
  black. The display is resized to that area, and car coordinates are shifted to match.
- **Dark phone (`PhonePower`):** a virtual display sleeps with its display group. On Android 17
  builds that support desktop mode, every virtual display is put in the phone's group. So
  AnyAuto holds a wake lock and powers off only the phone's own panel
  (`IDisplayManager.requestDisplayPower`). Whenever the system turns the panel back on, AnyAuto
  turns it off again.

## Source

`src/com/mag/anyauto/` holds the phone app and the Shizuku service, and
`src/com/mag/anyauto/car/` the Android Auto side. Logs:
`adb logcat -s AnyAuto AnyAuto.shell AnyAuto.car`.

## Support

AnyAuto is free and stays free. If it got your apps into the car and you'd like to say
thanks, you can [buy me a coffee on Ko‑fi](https://ko-fi.com/forsblomdev).

## License

Copyright (C) 2026 GitMag

AnyAuto is free software: you can use, study, share and change it under the terms of the
[GNU General Public License, version 3](LICENSE). If you distribute AnyAuto or a modified
version of it, you must make its source code available under the same license.

The libraries built into the APK keep their own licenses: AndroidX and the Car App Library
(Apache-2.0), and the Shizuku API (Apache-2.0).
