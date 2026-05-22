# youtube-source

YouTube as a music source for the vocal-monitor app. The user opens
Player Mode, sees a `YouTube` tab next to Library / Folders /
Favourites / Playlists, types a query, and downloads audio (opus / m4a)
into the local library.

Wraps **NewPipeExtractor** so the YouTube-protocol churn is upstream
maintained — when YT changes its internal signature algorithm and
breaks every extractor in the world, the NewPipe team's fix lands here
within 24 hours via the auto-rebuild workflow at
`.github/workflows/youtube-source-build.yml`.

## Anatomy

```
plugins/source/youtube-source/
├── plugin.json            registry metadata + url_allowlist
├── YouTubeSource.java     the only source file (compiled into the .dex)
├── youtube-source.dex     fat-dex bundling NPE + transitive deps (~3-5 MB)
├── youtube-source.dex.sig ed25519 signature of the .dex
├── plugin.json.sig        ed25519 signature of plugin.json
├── LICENSE                GPL-3.0 (inherited from NewPipeExtractor)
└── README.md              (you are here)
```

## How it loads on a phone

1. The vocal-monitor app ships the bundled `youtube-source.{dex,json,sig}`
   in `app/src/main/assets/bundled-plugins/youtube-source/` — present on
   first launch with no user action.
2. App's `SourcePluginUpdateWorker` runs daily, fetches the latest
   `manifest.json` + `.sig` from this registry, verifies against
   `assets/plugin-trust.pub`, downloads any newer `<plugin>.dex` +
   `.sig`, verifies, atomic-rename into `filesDir/source-plugins/<id>/`.
3. On next plugin bind, `SourcePluginEngine.load` opens the verified
   .dex over a `ParcelFileDescriptor` to the `:source_sandbox`
   isolated-process service, which `InMemoryDexClassLoader`s the bytes
   and instantiates `com.vocalmonitor.plugin.source.youtube.YouTubeSource`.

## Sandbox guarantees

Inside the isolated process the plugin has **zero** filesystem access
and **zero** network access. Every observable side-effect is through
the `SourceHost` capability surface the host injects at `init`:

- `host.fetch(url, headers, timeoutMs)` — validated against the
  `url_allowlist` declared in `plugin.json` (currently `*.youtube.com`,
  `*.googlevideo.com`, `*.ytimg.com`, `*.youtube-nocookie.com`,
  `*.ggpht.com`). Any other URL throws `SecurityException`.
- `host.writeChunk(bytes, last)` — appends to a per-download staging
  file in `Music/VocalMonitor/youtube/`. Plugin cannot pick a different
  path.
- `host.log(level, message)` — bridges into the host's `DebugStore`
  (visible on-device via Vol-Up + Vol-Down) and the
  `/api/debug?key=event` channel.
- `host.requestUpdateCheck()` — plugin hint to the host that "I think
  I'm out of date" (typically after a `ReCaptchaException`). Host
  schedules an out-of-band update fetch.

Even if this repo were compromised and a malicious `.dex` published,
**the missing private signing key prevents the app from loading it** —
the bundled `plugin-trust.pub` rejects unsigned or wrong-key artefacts.

## Building locally

Requires:
- `javac` (Java 17+) on PATH
- `d8` from Android SDK build-tools — set `DEX_TOOL=/path/to/d8` or
  `ANDROID_HOME=/path/to/sdk`
- `gradle` on PATH (resolves NewPipeExtractor + transitive deps)
- `node` (>= 18) for `scripts/build-source.mjs`

```bash
node scripts/build-source.mjs youtube-source
```

Produces `plugins/source/youtube-source/youtube-source.dex`. To sign:

```bash
../../../tools/plugin-signer/sign.sh youtube-source.dex
../../../tools/plugin-signer/sign.sh plugin.json
```

(`tools/plugin-signer/` lives in the vocal-monitor-slim app repo —
see its README for setup of the offline ed25519 keypair.)

## Auto-update flow

```
TeamNewPipe/NewPipeExtractor publishes v0.24.7
        │
        │ 07:00 UTC daily cron
        ▼
youtube-source-build.yml workflow
  ├─ resolve latest NPE tag via GitHub API
  ├─ bump plugin.json (upstream.version + plugin patch)
  ├─ build-source.mjs → rebuild .dex with new NPE
  ├─ sign .dex + plugin.json with PLUGIN_SIGNING_KEY
  └─ commit + push
        │
        ▼
build-manifest.yml workflow (existing)
  └─ rebuild + sign manifest.json
        │
        ▼ within 24h
Phone polls registry, SourcePluginUpdateWorker:
  ├─ fetch manifest.json + .sig → verify against plugin-trust.pub
  ├─ see new youtube-source version → fetch .dex + .sig → verify
  ├─ atomic-rename into filesDir/source-plugins/youtube-source/
  └─ next bind loads new code
```

End-to-end: NPE fix → phones have it ≈ 24 hours later, with zero user
action.

## License

GPL-3.0. The plugin's source is published in this repo; the bundled
.dex includes NewPipeExtractor (GPL-3.0). The vocal-monitor-slim
application that loads this plugin at runtime treats every .dex as a
separate work loaded dynamically through its own classloader; the GPL
terms apply to this plugin's source and the bundled artefact, not to
the host application itself.
