<!--
Thanks for contributing a plugin! Fill out the sections below — review goes
faster when you've already answered the questions a maintainer would ask.
-->

## What does this plugin do?

<!-- One sentence: the sound it produces and what it's good for. -->

## Sound sample

<!-- Strongly encouraged: a short before/after clip on YouTube, SoundCloud,
     or any other public host. A 10-second sample tells a maintainer more
     than a paragraph of description ever will. -->

## Checklist

- [ ] Folder lives under `plugins/<category>/<id>/` (where `<id>` is kebab-case)
- [ ] `plugin.json` `id` matches the folder name exactly
- [ ] Filename is `<id>.js` (matches the id, no other JS files in the folder)
- [ ] Calls `registerProcessor('<id>', MyProcessor)` at the bottom
- [ ] No ES6 `class`, `import`, `export`, `require`, `WebAssembly`, or DOM APIs (see [PLUGIN_API.md](../blob/main/PLUGIN_API.md))
- [ ] State buffers allocated in the constructor — no per-block allocations in `process()`
- [ ] Defaults sound musical the moment the plugin is added — no need to crank knobs
- [ ] `description` in `plugin.json` describes the *sound*, not the algorithm
- [ ] `node scripts/validate-plugins.mjs` passes locally

## Notes for the reviewer

<!-- Anything you want the maintainer to listen for, edge cases you weren't sure about,
     parameters that need a specific scaling curve, etc. -->
