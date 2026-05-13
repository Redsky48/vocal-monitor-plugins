# Contributing a plugin

Thanks for considering it! The workflow is intentionally simple: fork, add one folder, open a PR. CI handles the rest.

## TL;DR

```sh
# 1. Fork on GitHub, then clone your fork.
git clone https://github.com/<you>/vocal-monitor-plugins.git
cd vocal-monitor-plugins

# 2. Scaffold a new plugin from the template. This creates the folder,
#    plugin.json with the right id, and a starter .js file.
npm run new -- distortion my-fuzz "My Fuzz"
# (or: node scripts/new-plugin.mjs distortion my-fuzz "My Fuzz")

# 3. Open the generated plugins/distortion/my-fuzz/my-fuzz.js and write
#    your DSP. The template ships with a no-op pass-through to start from.

# 4. Validate locally before opening the PR.
npm run validate
# (or: node scripts/validate-plugins.mjs)

# 5. Push and open a PR. CI runs the same validator + tries a manifest build.
```

The [`template/`](template) folder is the source of truth for what a new
plugin looks like — `npm run new` is just a convenience wrapper around
copying that folder and substituting your id in the right places.

## Repo layout rules

- One folder per plugin, under one of the existing categories:
  `distortion`, `delay`, `filter`, `lofi`, `modulation`, `voice-fx`.
- New categories: create the folder and add a label in `scripts/build-manifest.mjs` (`CATEGORY_LABELS`). The app then shows it as a chip.
- The folder name = the plugin **id**. Use kebab-case (`a-z`, `0-9`, `-`).
- Every folder has exactly two files: `plugin.json` and `<id>.js`. No subfolders, no assets.

## `plugin.json` schema

```jsonc
{
  "id":          "my-fuzz",           // kebab-case, matches folder name
  "name":        "My Fuzz",           // shown in the app's library card
  "author":      "Your Name",         // free text — username, alias, real name
  "version":     "1.0.0",             // semver, bumped on every breaking change
  "description": "What it sounds like and what it's good for.",
  "tags":        ["distortion", "fuzz"],  // optional, used for searching

  // Optional: factory presets — named bundles of param overrides shown
  // as chips in the app's edit-node card. See "Presets" section below.
  "presets": [
    {
      "name":        "Subtle",
      "description": "gentle, sits in a mix",      // optional
      "params":      { "drive": 0.3, "tone": 0.5 } // only the params you want to change
    },
    { "name": "Wall", "params": { "drive": 0.95, "tone": 0.7 } }
  ]
}
```

`category` is **not** in `plugin.json` — it's the folder it lives under.

### Presets

Ship 2–5 factory presets per plugin to give users a fast tour of what your effect can do. Best practices:

- **Use short names** — they render as chips in a horizontally-scrollable row. "Subtle Width", not "Subtle Width Mode Used For Tight Background Vocals".
- **Only list params the preset changes.** Un-listed params keep their current value, so a "Wider" preset that only changes `width` won't reset the user's carefully-dialled `feedback`.
- **Name conventions help discovery:** "Off", "Subtle", "Default", "Strong", "Extreme" map onto an intuitive intensity axis. Or evoke a use case: "Doubler", "80s Pad", "Watery".
- **Param ids must match what your plugin actually declares.** The app silently ignores unknown ids, so a typo just makes the preset partially work instead of throwing — verify against your `parameterDescriptors`.
- **Values are clamped to each param's `min`/`max`.** No range validation in the manifest itself — keep them in-range.
- **Optional `description`** is shown as a tiny subtitle under the chip name. Keep under ~30 chars.

Presets are a static catalogue contract — they ship in `plugin.json` and update only when the user pulls a fresh catalogue from the registry (the app refreshes on launch). Plugins can't add presets at runtime.

## Constraints the Rhino interpreter imposes

The app runs plugins via [Mozilla Rhino](https://github.com/mozilla/rhino) in interpreter mode (no JVM bytecode generation, because Android's Dalvik / ART can't execute it). This means:

- **No ES6 `class`.** Write prototype style: `function Foo() {}` + `Foo.prototype.method = function () {}`.
- **No `import` / `export`.** The plugin file is the whole plugin.
- **No `require()`.** No CommonJS either.
- **No WebAssembly.** Pure JS DSP only.
- **No DOM.** `document`, `window`, `localStorage`, `fetch`, `XMLHttpRequest` are all unavailable.
- Numeric arrays are plain `Array`. `Float32Array` works but is no faster — the host bridges to JS through plain arrays.
- `sampleRate` is a global the host sets before instantiation (just like browser AudioWorklet).

The CI validator rejects PRs that use any of these — see [`scripts/validate-plugins.mjs`](scripts/validate-plugins.mjs).

## What good plugins look like

- **DSP-only**, no GUI. The host generates a slider for every `parameterDescriptors` entry automatically.
- **Stateless across `process()` calls** except for what you put on `this` in the constructor (delay buffers, LFO phase, filter state).
- **Block-safe.** The host passes ~1024 sample blocks; persist phase / pointers across blocks so the effect is continuous.
- **Mono-aware.** The app is currently mono-only. Don't assume `inputs[0]` has multiple channels.
- **Param-rate aware.** Parameters arrive as `params.name[0]` (single value per block — "k-rate").
- **Sane defaults.** `defaultValue` should sound musical the moment the user adds the plugin.

See `plugins/modulation/tremolo/tremolo.js` for a minimal example, `plugins/delay/tape-delay/tape-delay.js` for a buffer-based one, `plugins/modulation/phaser/phaser.js` for a multi-stage filter.

## PR review

A maintainer will listen to the plugin on a real take before merging. Things that get pushback:

- Crashes on extreme parameter values (try the slider endpoints).
- DC offset or runaway feedback.
- Buffer allocations inside `process()` — allocate in the constructor and reuse.
- Unclear `description` — describe the *sound*, not the algorithm.

Once merged, the manifest rebuild Action publishes your plugin to every app instance pointed at this registry, within seconds.
