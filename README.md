# Inkwell Drop — AirDrop-style sharing between iPhone, iPad and Android

Inkwell Drop sends photos, videos, files, links and text between Apple and Android devices. Both devices need Inkwell Drop installed and Bluetooth turned on. Nothing else is needed: no account, no internet, no pairing, no cables.

- **In the share sheet.** Tap Share in any app, pick Inkwell Drop, tap a device. The transfer runs inside the share sheet, with a progress ring on the device, and the sheet closes when it is done.
- **Bluetooth only.** Devices find each other and transfer over Bluetooth LE. Wi-Fi can be off.
- **Fast without a Wi-Fi network.** File data moves off Bluetooth onto Wi-Fi on its own whenever it can (see *How fast is it?*). No setup for the shared-network and hotspot cases, and nothing goes over the internet.
- **Android receives at any time.** A small background service keeps the phone visible, and offers arrive as a notification with Accept and Decline.
- **Trusted devices.** In Settings › Trusted devices, pick people from your recent history (or whoever is nearby). Anything they send starts right away, without asking you to accept. You can also tap "Trust" on an incoming request. Remove a device there at any time.
- **Saved where you expect.** Photos and videos go to the gallery / Photos. Other files go to Downloads (Android) or the Files app (iOS).
- **Encrypted.** Every connection is encrypted end to end with fresh keys, and each device proves its identity before "always accept" applies.

## Install

Every push builds both apps. Download them from the [latest release](../../releases/tag/latest).

**Android (12 or later):** open `InkwellDrop.apk` on the phone and install it. Open Inkwell Drop once and allow *Nearby devices* and *Notifications*.

**iPhone / iPad (iOS 16 or later):** `InkwellDrop.ipa` is unsigned, because iOS only installs apps signed for your device. Install it with SideStore, AltStore or Sideloadly (a free Apple ID works, with a refresh every 7 days), or build it in Xcode with your own team. Open Inkwell Drop once and allow Bluetooth.

## How fast is it?

Every transfer starts over Bluetooth, then moves to the fastest link the two devices can use:

| Link | Works between | Setup | Speed |
|---|---|---|---|
| Same Wi-Fi network | any two devices | none | fast |
| One phone joins the other's hotspot | any two devices | turn on a hotspot | fast |
| Wi-Fi Aware, direct phone-to-phone Wi-Fi | two Android phones (Android 13+, Wi-Fi switched on) | none | fast |
| Wi-Fi Aware, direct device-to-device Wi-Fi | two iPhones or iPads (iOS 26, iPhone 12 or newer) | pair once in Settings | fast |
| Bluetooth | any two devices | none | slow for large files |

The progress card shows ⚡ Fast when a transfer is on one of the Wi-Fi links.

Wi-Fi Aware between an iPhone and an Android phone isn't supported yet: Apple only allows it with paired devices, and pairing with Android needs Wi-Fi Aware 4.0 support that few Android phones have. Between an iPhone and an Android phone, use the same Wi-Fi or a hotspot for speed.

On iPhone, Wi-Fi Aware needs Apple's Wi-Fi Aware entitlement. The IPA asks for it, but whether you get it depends on how you install the app: a free Apple ID probably won't include it, a paid developer account can. Without it, the "Fast anywhere" pairing section just won't work, and everything else does.

## Control Center and Quick Settings

- **iPhone / iPad (iOS 18+):** open Control Center, touch and hold an empty spot, tap **Add a Control** and search "Inkwell". There are buttons to receive, send photos and send files. They can also go on the Lock Screen or the Action button.
- **Android:** in Inkwell Drop open Settings and tap **Add** next to *Quick Settings tile* (Android 13+), or add it yourself from the Quick Settings editor. Tap the tile to turn receiving on or off; touch and hold it to open the app.

## Put Inkwell Drop at the front of the share sheet

Neither system lets an app choose its own position, but you can pin it:

- **iOS:** in the share sheet, scroll the row of apps to the end and tap **More**, then **Edit**. Add Inkwell Drop to Favourites and drag it to the top.
- **Android:** in the share sheet, press and hold Inkwell Drop and choose **Pin**. After you have sent to a device once, that device also appears by name in the top row, and tapping it sends straight to it.

## Using it

**Send:** Share → Inkwell Drop → tap the device. Or open Inkwell Drop and choose Photos, Files or Text.

**Receive on Android:** nothing to do. Accept the notification.

**Receive on iPhone or iPad:** open Inkwell Drop. iOS does not let an app be found over Bluetooth once it is closed. While Inkwell Drop is in the background (opened earlier and not swiped away) it can still pick up an Android sender and show a notification, but opening the app is the reliable way.

## Limits you should know about

- **Bluetooth on its own is slow for big files.** Expect very roughly 50–150 KB per second: a photo takes a few seconds, a one-minute video takes minutes. Use one of the Wi-Fi links above for anything large.
- **iOS asks once to find devices on your local network.** Inkwell Drop triggers this question when it first opens. Allow it, or transfers stay on Bluetooth and the direct links.
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
                                       └─ if ACCEPT lists a Wi-Fi address the sender can reach, or a
                                          Wi-Fi Aware link, the file data goes over TCP on it instead
                                          (same encryption, joined to the transfer by a one-time token)
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
| `ios/Controls/` | Control Center buttons and the App Intents behind them. |
| `ios/Entitlements/` | Entitlements the CI build signs into the IPA (Wi-Fi Aware). |
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
