# Inkwell Drop — AirDrop-style sharing between iPhone, iPad and Android

Inkwell Drop sends photos, videos, files, links and text between Apple and Android devices. Both devices need Inkwell Drop installed and Bluetooth turned on. Nothing else is needed: no account, no internet, no pairing, no cables.

- **In the share sheet.** Tap Share in any app, pick Inkwell Drop, tap a device. The transfer runs inside the share sheet, with a progress ring on the device, and the sheet closes when it is done.
- **Bluetooth only.** Devices find each other and transfer over Bluetooth LE. Wi-Fi can be off.
- **Faster on Wi-Fi, automatically.** If both devices are on the same Wi-Fi network, the file data moves to it without anyone doing anything. If not, it stays on Bluetooth.
- **Fast anywhere with a hotspot.** No Wi-Fi around? Turn on one phone's hotspot and connect the other phone to it. That counts as the same network, so transfers run at Wi-Fi speed with no router and no mobile data used. Phones remember each other's hotspot, so after the first time it is one switch.
- **Android receives at any time.** A small background service keeps the phone visible, and offers arrive as a notification with Accept and Decline.
- **Always accept.** Tick "Always accept from this device" once and later transfers from that device start by themselves.
- **Saved where you expect.** Photos and videos go to the gallery / Photos. Other files go to Downloads (Android) or the Files app (iOS).
- **Encrypted.** Every connection is encrypted end to end with fresh keys, and each device proves its identity before "always accept" applies.

## Install

Every push builds both apps. Download them from the [latest release](../../releases/tag/latest).

**Android (12 or later):** open `InkwellDrop.apk` on the phone and install it. Open Inkwell Drop once and allow *Nearby devices* and *Notifications*.

**iPhone / iPad (iOS 16 or later):** `InkwellDrop.ipa` is unsigned, because iOS only installs apps signed for your device. Install it with SideStore, AltStore or Sideloadly (a free Apple ID works, with a refresh every 7 days), or build it in Xcode with your own team. Open Inkwell Drop once and allow Bluetooth.

## Put Inkwell Drop at the front of the share sheet

Neither system lets an app choose its own position, but you can pin it:

- **iOS:** in the share sheet, scroll the row of apps to the end and tap **More**, then **Edit**. Add Inkwell Drop to Favourites and drag it to the top.
- **Android:** in the share sheet, press and hold Inkwell Drop and choose **Pin**. After you have sent to a device once, that device also appears by name in the top row, and tapping it sends straight to it.

## Using it

**Send:** Share → Inkwell Drop → tap the device. Or open Inkwell Drop and choose Photos, Files or Text.

**Receive on Android:** nothing to do. Accept the notification.

**Receive on iPhone or iPad:** open Inkwell Drop. iOS does not let an app be found over Bluetooth once it is closed. While Inkwell Drop is in the background (opened earlier and not swiped away) it can still pick up an Android sender and show a notification, but opening the app is the reliable way.

## Limits you should know about

- **Bluetooth is slow for big files.** Expect very roughly 50–150 KB per second: a photo takes a few seconds, a one-minute video takes minutes. This is the ceiling of what iOS allows. AirDrop is fast because it switches to a private Wi-Fi link that Apple does not open to other apps or to Android. Being on the same Wi-Fi network, or connecting one phone to the other's hotspot, removes the limit.
- **A faster direct link exists but isn't built yet.** iOS 26 added Wi-Fi Aware, a router-free Wi-Fi link that also works with Android. It needs iOS 26, a one-time pairing between the two devices, and an app signed with Apple's Wi-Fi Aware entitlement, which an unsigned sideloaded build doesn't carry.
- **The first Wi-Fi transfer on iOS may fall back to Bluetooth**, because iOS shows its "find devices on your local network" question at that moment. Allow it and the next transfer uses Wi-Fi.
- **The iOS share sheet shows up as "iPhone" or "iPad".** The name you set in the app applies to the app. iOS does not share settings between an app and its share extension unless the build is signed with an App Group.
- **Not yet run on real hardware.** The transfer engine is tested on every build, including the Android (Kotlin) and iOS (Swift) implementations talking to each other. The Bluetooth layers compile and follow each platform's documented behaviour, but were written without phones to try them on. If two devices don't find each other, that is the place to look.

## How it works

```
        Bluetooth LE advert  ──►  GATT read (L2CAP PSM)  ──►  L2CAP channel
                                                                   │
                         handshake: ephemeral P-256 ECDH, signed by each device's long-term key
                                                                   │
                         OFFER ──► ACCEPT ──► file data ──► DONE ──► RECEIVED      (AES-256-GCM frames)
                                       │
                                       └─ if ACCEPT lists a Wi-Fi address the sender can reach,
                                          the file data goes over TCP instead (same encryption,
                                          joined to the transfer by a one-time token)
```

- A device that can receive advertises one service UUID and exposes a GATT characteristic holding the PSM of its L2CAP listener. A sender scans, reads the PSM and opens an L2CAP channel. Once connected, either side can send.
- While its send screen is open, Android advertises a second "looking for receivers" UUID. A backgrounded iPhone scans for that UUID and connects to the sender, which is how it can be reached without being discoverable itself.
- Wire format: 4-byte length, then a frame. After the plaintext hello, each frame is `type || payload` sealed with AES-256-GCM (nonce = frame counter, one key per direction, keys from HKDF-SHA256 over the ECDH secret).

## Repository layout

| Path | What |
|---|---|
| `android/` | Android app (Kotlin, Jetpack Compose). `core/` is the transfer engine; the rest is Bluetooth, storage and UI. |
| `ios/Core/` | The same engine in Swift. Also builds as a Swift package on macOS and Linux. |
| `ios/Shared/` | CoreBluetooth transport, app state and the device picker, used by the app and the share extension. |
| `ios/App/`, `ios/ShareExtension/` | The iOS app and its share-sheet extension. `ios/project.yml` is the XcodeGen spec. |
| `ios/Tools/drop-cli/` | A command-line peer used by the tests. |

## Building

```sh
# Android
cd android && ./gradlew assembleRelease

# iOS (on a Mac)
cd ios && brew install xcodegen && xcodegen generate && open InkwellDrop.xcodeproj

# Protocol tests, including Kotlin against Swift
cd ios && swift build --product drop-cli
cd ../android && DROP_SWIFT_CLI=../ios/.build/debug/drop-cli ./gradlew testDebugUnitTest
```

`android/sideload.keystore` is a fixed signing key (password `android`) so each build installs over the last. It is public, so anyone could sign an APK that your phone would accept as an update to Inkwell Drop. Replace it with a private key before distributing the app to other people.
