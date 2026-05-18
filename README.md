# View Assist Companion App — Android TV Fork

> **This is a fork of [msp1974/ViewAssistCompanionApp](https://github.com/msp1974/ViewAssistCompanionApp).**  
> Its **sole purpose** is to add Android TV / D-pad remote support to the upstream app so it can be used on TV boxes, Fire TV sticks, NVIDIA Shield, Chromecast with Google TV, and similar devices.  
> For general documentation, feature requests, and bug reports unrelated to Android TV, please refer to the **[upstream repository](https://github.com/msp1974/ViewAssistCompanionApp)**.

---

The **View Assist Companion App** (VACA) is an Android application that turns any Android device — phone, tablet, or **Android TV** box — into a Home Assistant voice-satellite and WebView display terminal.  
It pairs with the [View Assist](https://github.com/dinki/View-Assist) Home Assistant integration and communicates via the **Wyoming** protocol to provide local wake-word detection, microphone streaming, and a full-screen dashboard experience.

---

## Features

| Feature | Details |
|---|---|
| **Wyoming satellite** | Acts as a Wyoming-protocol voice satellite; HA discovers it automatically via mDNS/Zeroconf |
| **Wake-word detection** | On-device detection using [openWakeWord](https://github.com/dscripka/openWakeWord) or [microWakeWord](https://github.com/kahrendt/microWakeWord) (model files downloaded at runtime) |
| **Full-screen dashboard** | Hosts a Chromium-based WebView that loads the configured HA dashboard |
| **Swipe-to-refresh** | Pull down on the WebView to reload the dashboard (configurable) |
| **Dark mode** | Automatically honours system dark/light preference inside the WebView |
| **Auto-update** | Self-updating APK download and install when HA signals a new version |
| **Camera** | Optional camera integration for presence detection |
| **Do-Not-Disturb mode** | DND state shown as a red border around the dashboard |
| **Diagnostic overlay** | Optional overlay showing mic level, wake-word detection level, audio routing state, and selected engine |
| **Android TV / D-pad navigation** | Full remote-control navigation — see below |

---

## Android TV Support

VACA runs on Android TV (Fire TV, NVIDIA Shield, Chromecast with Google TV, generic Android TV boxes, etc.) with a fully remote-friendly UI.

### What was done to make the app TV-friendly

#### 1. D-pad navigation in native Compose screens (`DPadFocusNavigation.kt`)
A Compose `Modifier` extension (`dpadFocusNavigation`) intercepts D-pad arrow keys and calls `FocusManager.moveFocus` in the correct direction.  
It is applied to every scrollable screen container and to the confirmation dialog.

#### 2. Initial focus on the Connection screen (`ConnectLayout.kt`)
On first composition a `FocusRequester` is used to place focus on the app-info block, so the very first D-pad press works without requiring the user to press a directional key to "wake up" focus.

#### 3. D-pad navigation inside the HA WebView (`CustomWebView.kt`)
A small JavaScript snippet is injected after each page load.  It:
- Builds a flat list of focusable elements from the entire DOM, **including Shadow DOM** subtrees used heavily by Home Assistant's Lit-based components.
- Maps Left/Up → previous element, Right/Down → next element.
- Runs in capture phase so it sees events before shadow-DOM components, but deliberately omits `stopPropagation` so HA's own interactive widgets (dropdowns, sliders, toggles) continue to receive and handle the same key events.
- Is idempotent (guarded by `window.__vaDpadNavigationInstalled`).

#### 4. Back-key dismiss for dialogs (`Dialog.kt`)
`DialogProperties.dismissOnBackPress = true` so the TV remote's Back button dismisses confirmation dialogs without requiring D-pad navigation to the dismiss button.

#### 5. Non-focusable display chips (`DiagnosticBar.kt`)
Status chips in the diagnostic overlay carry `Modifier.focusProperties { canFocus = false }` so the D-pad focus cycle skips display-only elements.

### Tips for Android TV

- **Set the app as the launcher** — on Android TV, setting VACA as the home launcher means it starts automatically on boot and returns to the dashboard when Back is pressed past the HA UI.
- **Swipe-to-refresh** — this feature is disabled automatically when the device has no touch screen (standard TV remotes cannot trigger a swipe gesture). You can also disable it in HA settings.
- **Wake word** — openWakeWord and microWakeWord both run on the CPU and work without a GPU. Model files are downloaded on first connection.
- **Microphone** — most Android TV boxes expose at least one audio input. VACA requests `RECORD_AUDIO` permission at runtime; grant it in Android Settings → Apps → VACA → Permissions if it is not prompted automatically.

---

## Requirements

- Android **8.0 (API 26)** or later
- Home Assistant with the **View Assist** integration installed
- Network connectivity between the device and HA (local LAN recommended)

---

## Installation

1. Download the latest `.apk` from the [Releases](../../releases) page.
2. On the device, enable **Install from unknown sources** (Settings → Security or Privacy).
3. Transfer the APK to the device (USB, ADB, or a file manager) and install it.
4. Launch VACA; on first run it will display a **UUID** and a QR code.
5. In Home Assistant, open the View Assist integration and enter the UUID to pair the device.
6. VACA will receive its configuration (HA URL, dashboard, wake-word settings) from HA automatically and navigate to the dashboard.

### ADB install (Android TV / headless)

```bash
adb connect <device-ip>
adb install vaca-<version>-release.apk
```

---

## Building from source

### Prerequisites

- Android Studio Meerkat (2025.1) or later **or** JDK 17 + Android command-line tools
- Android SDK with API 36 platform and build-tools

### Steps

```bash
git clone https://github.com/R00S/ViewAssistCompanionApp4tv.git
cd ViewAssistCompanionApp4tv

# Debug build (no signing required)
./gradlew assembleDebug

# Release build (requires signing)
export KEYSTORE_FILE=/path/to/release.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
./gradlew assembleRelease
```

The output APK is placed in `app/build/outputs/apk/` and named `vaca-<version>-<variant>.apk`.

---

## Architecture overview

```
app/
├── satellite/          Wyoming satellite — audio pipeline, wake-word, media manager
├── wyoming/            Wyoming TCP server + Zeroconf advertisement
├── wakeword/           Wake-word engine wrappers (openWakeWord / microWakeWord)
├── ui/
│   ├── layouts/        Compose screens (ConnectionScreen, WebViewScreen)
│   ├── components/     Reusable Compose components (DiagnosticBar, dialogs, …)
│   └── theme/          Material 3 colour scheme
├── utils/              CustomWebView (with D-pad JS), network helpers, updater
├── settings/           APPConfig — shared preferences + HA-pushed settings
└── players/            Media, alarm, and voice playback services
```

---

## Permissions

| Permission | Reason |
|---|---|
| `RECORD_AUDIO` | Microphone input for wake-word detection and voice streaming |
| `INTERNET` / `ACCESS_NETWORK_STATE` | HA WebView and Wyoming connection |
| `POST_NOTIFICATIONS` | Foreground service notification |
| `CAMERA` | Optional presence/camera integration |
| `WAKE_LOCK` / `TURN_SCREEN_ON` | Keep screen on while displaying the dashboard |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device reboot |
| `REQUEST_INSTALL_PACKAGES` | Self-update APK installation |

---

## Licence

See [LICENSE](LICENSE) and [NOTICE](NOTICE) for full licence text and third-party notices.
