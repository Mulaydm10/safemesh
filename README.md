# SafeMesh

**A free, offline safety mesh for Android.** Phones talk to each other directly over Bluetooth, so people can still reach each other and call for help when mobile networks are down, overloaded or jammed. You don't need a SIM, internet, an account or a server.

> **Status: early / experimental.** SafeMesh has not had an independent security audit. Don't make it your only way to call for help in a life-threatening situation.

---

## Features

| | |
|---|---|
| 🆘 **One-tap SOS** | The red **SOS** button sends your status (*Injured, Need medic, Detained, Lost / separated, In danger*) and GPS location to everyone in mesh range. Receivers get a high-priority alert, and the message shows in red. |
| 🔒 **Encrypted groups** | Same group name + same password = same group. Messages are sealed with AES-256-GCM before they go on the mesh, so relays and non-members only see ciphertext. |
| ✉️ **Private messages** | Direct messages are end-to-end encrypted with the [Noise protocol](https://noiseprotocol.org) (XX, X25519, ChaCha20-Poly1305). |
| 📡 **Multi-hop mesh** | Messages hop phone-to-phone over Bluetooth LE (up to 7 hops) to reach people beyond direct range. |
| 🧹 **Panic wipe** | Triple-tap the title to wipe all local data at once, including group keys. |
| 🚫 **No tracking** | No accounts, phone numbers, ads, analytics, payments or subscriptions. |

## How it works

### SOS

1. Tap **SOS** and pick a status.
2. SafeMesh uses a location fix from the last 2 minutes. If none exists, it tries a fresh GPS/network fix for up to 8 seconds. Without location permission it sends `unknown`.
3. It broadcasts a public mesh message:
   ```
   SOS! INJURED - need help. Location: 52.52000,13.40500 ±12m geo:52.52000,13.40500
   ```
4. Receivers get a max-priority notification on the `SOS alerts` channel.

SOS messages are **public on purpose**, so anyone nearby can help. Don't send one if your location must stay secret.

### Groups

```
key  = PBKDF2-HMAC-SHA256(password, salt = "safemesh/group/v1/" + lowercase(name), 100 000 iterations)
gid  = SHA-256("safemesh/gid/v1" || key)[0..8]
wire = "SMG1:" + hex(gid) + ":" + base64(iv || AES-256-GCM(key, text, aad = gid))
```

- **Fails closed.** A group whose key isn't loaded refuses to send ("Group locked") and never falls back to plaintext.
- **Drops what it can't read.** Envelopes that don't decrypt are discarded, so they never show up in the public timeline.
- **Keys live in memory only.** They're forgotten on restart, on leaving the group or on panic wipe, so you re-enter the password after a restart.
- **Passwords need at least 4 characters.** Use a long one: anyone who hears group traffic can try to guess it offline.

## Install

There's no signed release yet, so build it from source (below) and sideload the APK.

**Requirements:** Android 8.0 (API 26) or newer, with Bluetooth LE.

**Permissions** (requested at runtime):

- **Bluetooth:** to run the mesh.
- **Location:** Android requires it for BLE scanning, and SOS uses it for coordinates.
- **Notifications:** for SOS and message alerts.

## Build from source

You need Android Studio (or the Android SDK command-line tools) and JDK 17+.

```bash
git clone https://github.com/Mulaydm10/safemesh.git
cd safemesh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Tests and lint

```bash
./gradlew testDebugUnitTest      # unit tests (includes GroupEnvelopeTest)
./gradlew lintDebug              # lint
./gradlew connectedAndroidTest   # instrumented tests, needs a device or emulator
```

Emulators handle BLE mesh behaviour poorly. Protocol and crypto logic are covered by unit tests, but radio behaviour needs real phones.

## Project layout

| Path | What's there |
|---|---|
| `app/src/main/java/com/bitchat/android/mesh/GroupEnvelope.kt` | Group encryption (`GroupEnvelope`) and the in-memory key store (`GroupKeyring`) |
| `app/src/main/java/com/bitchat/android/mesh/MessageHandler.kt` | Incoming mesh messages, including group decryption |
| `app/src/main/java/com/bitchat/android/ui/SafetyComponents.kt` | SOS dialog and message format |
| `app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt` | Sending messages, SOS location lookup, joining groups |
| `app/src/main/java/com/bitchat/android/ui/NotificationManager.kt` | SOS alert notifications |
| `wear/` | Wear OS companion (inherited from bitchat) |
| `docs/` | Protocol specs inherited from bitchat |

The package name is still `com.bitchat.android` to keep the upstream diff small. The app ID is `app.safemesh`, so it installs alongside bitchat.

## Known limitations

- **SOS alerts aren't authenticated.** Any public message that starts with `SOS!` triggers an alert, and there's no rate limit yet.
- **Group names are case- and `#`-insensitive.** `#Foo` and `foo` are the same group.
- **Some bitchat internet features remain.** The optional ones (Nostr relays, geohash location channels) are still in the codebase. SafeMesh's safety features only use the Bluetooth mesh.
- **Android only.** There's no iOS app yet, and SafeMesh groups don't work with stock bitchat clients.

## Contributing

Issues and pull requests are welcome. Please run `./gradlew testDebugUnitTest lintDebug` before opening a PR. For crypto or SOS changes, explain the threat model in the PR description.

To report a security problem, please open a private [security advisory](https://github.com/Mulaydm10/safemesh/security/advisories/new) rather than a public issue.

## Credits and license

SafeMesh is a fork of [bitchat-android](https://github.com/permissionlesstech/bitchat-android) by permissionless tech. All credit for the mesh, Noise and transport layers goes to the bitchat contributors.

Licensed under the **GNU General Public License v3.0**; see [`LICENSE.md`](LICENSE.md). If you distribute builds, you must also make the source available under the same license.
