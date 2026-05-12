# Vocal Monitor — Privacy Policy

_Last updated: 2026-05-12_

This privacy policy describes how the **Vocal Monitor** Android application
(`com.vocalmonitor.professional`) handles your data.

---

## Short version

- Vocal Monitor **does not collect, transmit, or share any of your audio,
  voice recordings, or personal data**.
- Microphone access is used **only** to detect and display your pitch in
  real time and to record audio that **stays on your device**.
- The only network traffic the app makes is a public HTTPS fetch to the
  open-source plugin registry on GitHub — no account, no identifier, no
  analytics is sent.
- No advertising. No third-party SDKs. No tracking.

---

## What data the app processes

### Microphone audio (`android.permission.RECORD_AUDIO`)

The app accesses the microphone to:

- Detect the **pitch** of your voice in real time and draw it on screen.
- Record audio clips that **you** save, edit, and play back inside the app.

All audio processing happens **on your device**. Recordings are written to
local storage (the folder you pick via Android's storage picker, or the
public `Music/VocalMonitor` directory by default). The app never uploads,
streams, or transmits any audio captured from the microphone.

### Recordings storage

Recordings remain on your device's storage until **you** delete them or
share them yourself using Android's share sheet. The app has no
functionality to transmit recordings on its own.

### Internet (`android.permission.INTERNET`)

The app makes one and only one kind of network request: an HTTPS GET to the
public, open-source plugin registry hosted on GitHub
([github.com/Redsky48/vocal-monitor-plugins](https://github.com/Redsky48/vocal-monitor-plugins)):

- The registry's `manifest.json` file (a plain JSON list of available DSP
  plugins).
- Individual plugin source files (`.js` or `.dex`) that you tap **Install**
  on inside the app.

These requests carry **no user identifier, no account information, and no
audio data**. They look exactly like a web browser fetching a public file.
GitHub's own privacy policy applies to the IP-level log records GitHub
keeps for any public traffic.

### Notifications (`android.permission.POST_NOTIFICATIONS`)

Used to display the playback / recording notification while the app is in
the background. Nothing is sent off the device.

### Foreground service (`android.permission.FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`)

Used to keep audio playback running when the screen is off. No data
transmission.

---

## What the app does **not** do

- ❌ No analytics SDK (Firebase, Crashlytics, Google Analytics, etc.).
- ❌ No advertising SDK.
- ❌ No third-party tracking libraries.
- ❌ No user account, no sign-in, no email collection.
- ❌ No upload of microphone audio to any server.
- ❌ No upload of recordings to any server.
- ❌ No background data collection.

The full app source is open and inspectable — every network call the app
makes is documented in this policy.

---

## Children's privacy

The app does not knowingly collect any personal information from anyone,
including children under 13. Because the app does not collect personal
information at all, this section is essentially trivially satisfied.

---

## Changes to this policy

Any future changes to this policy will be published at the same URL, with
an updated "Last updated" date at the top.

---

## Contact

If you have questions about this policy or the app's data handling, contact:

**Eduards Silins** — eduards.silins20@gmail.com
