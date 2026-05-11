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

1. Read [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow and the constraints the Rhino interpreter places on plugin code.
2. Read [PLUGIN_API.md](PLUGIN_API.md) for the exact `registerProcessor()` / `process()` contract.
3. Fork → add `plugins/<category>/<your-id>/{plugin.json, your-id.js}` → PR.
4. CI validates the plugin (syntax, manifest schema, forbidden constructs). Merge triggers an automatic manifest rebuild.

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

## License

[MIT](LICENSE) — use, fork, modify freely. Per-plugin authorship lives in each `plugin.json`.
