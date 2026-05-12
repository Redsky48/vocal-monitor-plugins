# Vocal Monitor Plugins

A community registry of JavaScript audio plugins for the [Vocal Monitor](https://github.com/) Android app. Every plugin in this repo is pure ES5 JavaScript that runs inside the app's Mozilla Rhino interpreter — no compilation, no Wasm, no native code.

## Use this registry in the app

1. Open Vocal Monitor → Voice Effects → 📦 Plugin library.
2. Paste this URL into the **Registry URL** field:

   ```
   https://raw.githubusercontent.com/REPLACE-OWNER/vocal-monitor-plugins/main/manifest.json
   ```

3. Tap the refresh button. Every plugin listed here appears in the catalogue, marked **Online**. Tap **Install** to download and add it to your chain.

> Replace `REPLACE-OWNER` with this repo's actual owner once forked / published.

## What's inside

```
plugins/
├── distortion/     — saturator, ring-mod, octaver
├── delay/          — tape-delay, reverse-delay
├── filter/         — auto-wah
├── lofi/           — bitcrusher, stutter, vinyl, tape-stop
├── modulation/     — tremolo, chorus, phaser, flanger, vibrato
└── voice-fx/       — telephone
```

The top-level [`manifest.json`](manifest.json) is auto-generated from `plugins/<category>/<id>/plugin.json` files — never edit it by hand.

## Contribute a plugin

1. Fork on GitHub, clone your fork.
2. `npm run new -- <category> <id> "<Display Name>"` — scaffolds the folder, `plugin.json`, and a starter `.js` from [`template/`](template).
3. Edit the generated `.js` to implement your DSP. See [PLUGIN_API.md](PLUGIN_API.md) for the exact `registerProcessor()` / `process()` contract and [CONTRIBUTING.md](CONTRIBUTING.md) for the constraints the Rhino interpreter places on plugin code. For DSP-heavy plugins (FFT EQs, modular synth components, anything that benefits from Faust), see [NATIVE_PLUGIN_API.md](NATIVE_PLUGIN_API.md) — these compile to DEX bytecode and run at JVM-native speed. **Step-by-step native plugin build recipe** (javac + d8 commands, common mistakes, AI-agent notes) lives in [BUILDING_NATIVE_PLUGINS.md](BUILDING_NATIVE_PLUGINS.md). For a one-page overview of all three plugin runtimes (plain JS, JS + native primitives, native DEX), read [ARCHITECTURE.md](ARCHITECTURE.md).
4. `npm run validate` locally, then commit, push, open a PR.
5. CI auto-runs the validator. Merge triggers an automatic manifest rebuild — your plugin appears in everyone's app catalogue on next refresh.

Bug reports, ideas, and questions are very welcome:

- 🐞 [Plugin doesn't work](https://github.com/Redsky48/vocal-monitor-plugins/issues/new?template=plugin-bug.yml)
- 💡 [Suggest a new plugin](https://github.com/Redsky48/vocal-monitor-plugins/issues/new?template=plugin-idea.yml)
- 📱 [App-side feedback](https://github.com/Redsky48/vocal-monitor-plugins/issues/new?template=app-feedback.yml)
- 💬 [Discussions](https://github.com/Redsky48/vocal-monitor-plugins/discussions) for open-ended chat / show-and-tell.

## First-time publish

After cloning / scaffolding this repo locally, run:

```sh
./publish.sh <your-github-username>
```

The script writes `repo.config.json` with your username, rebuilds `manifest.json` so all source URLs point at your fork, initialises git, creates the GitHub repo (via `gh` CLI if installed — otherwise prints clear manual steps), and pushes the initial commit. Safe to re-run after editing plugins; it'll just publish the diff.

## Local development

```sh
node scripts/validate-plugins.mjs   # run the PR checks locally
node scripts/build-manifest.mjs     # regenerate manifest.json
```

`build-manifest.mjs` derives source URLs from `repo.config.json` (for local runs) or `$GITHUB_REPOSITORY` (in CI). `publish.sh` writes `repo.config.json` for you so local builds bake the right owner into the URLs automatically.

## Privacy

The Vocal Monitor Android app's privacy policy lives at
[redsky48.github.io/vocal-monitor-plugins/PRIVACY_POLICY.html](https://redsky48.github.io/vocal-monitor-plugins/PRIVACY_POLICY.html)
— short version: no data ever leaves the device. The only network traffic
the app makes is the public HTTPS fetch to this registry.

## License

[MIT](LICENSE) — use, fork, modify freely. Per-plugin authorship lives in each `plugin.json`.
