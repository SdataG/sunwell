# Changelog

Notable changes to **Sunwell**. Format based on [Keep a Changelog](https://keepachangelog.com/).

## [2.2.0] - 2026-07-18 - Real lightning, real shader support

The strike is a strike now: it arcs from the orb, forks toward open space, floods the room with real light for an instant, and closes from the ground up as it dies. Shaderpacks (Iris/Oculus) and Sodium/Embeddium now see the sunwell the way vanilla always did.

### Added
- **World-aware lightning branches.** Forks sample nearby directions and keep whichever has the most open room ahead, then clip their own length to stop right at the wall/floor/ceiling they actually reach — no more branches clipping through solid blocks. Growth speed scales per branch: a close target grows slowly, a far one lashes out fast.
- **Real in-world strike lighting.** A strike now flares genuine block light around its impact point — room-wide for the strike tick itself, contracting to a local glow through the leader/fade. Not just decorative geometry anymore.
- **Bottom-up closing fade.** The bolt visibly closes from the strike end upward as it dies, instead of dimming everywhere at once. Branches fade in step with the same sweep.
- **Lightning favors height.** A free strike (no rod) leans toward the tallest thing in the cone — a tree, an exposed mob or player, a raised ledge — while staying random. Never lands within a block of the lamp anymore.
- **Sodium / Embeddium light fix.** Both clone raw light data and mesh from the copy, bypassing the vanilla light engine entirely — sunwell rooms rendered pitch black under either. Reapplies the boost at their own read point instead.
- **Shaderpack support (Iris / Oculus).** The room now fills with real block light under a shaderpack (which doesn't render virtual sky light as brightness), the orb blooms at full brightness instead of reading as a flat disc, and the additive glow render type is registered under vanilla's own `"lightning"` name so Iris routes it through a known-working program instead of silently dropping the dimmest parts of the fade.
- **Snow accumulation.** Falling snow now actually piles up as snow layers on the floor while it's snowing, instead of just falling through. `snowAccumulation`, `snowAccumulateOdds`, `snowMaxLayers`.
- **`/sunwell lightning <odds>` and `/sunwell rodboost <mult>`.** Tune strike frequency live without editing the config; persists to disk.
- **`debugLightning`.** Logs where each strike lands and why, for diagnosing strikes hitting empty air or empty rooms.

### Changed
- **Lantern block light 8 → 15.** A proper lantern glow on top of the sky-cone projection, not just the projected light alone.
- **Rain covers the whole lit cone**, not just the centre, and scales with weather (light drizzle vs. a real downpour in a storm).
- **A wall- or low-mounted lantern now glows around itself immediately**, instead of needing several blocks of drop before the cone opened wide enough to light anything.
- **The lightning core is brighter**, with an extra hyper-bright inner filament and wider soft bloom/halo layers, so the bolt reads as glowing in plain vanilla, not just under a shaderpack's own bloom pass.
- **The return-stroke pulse actually climbs.** Brighter, and given enough frames to visibly travel from strike to lamp instead of snapping through in about one tick.
- **Held-lantern light shafts removed.** They read as jank in hand; the orb's halo now correctly tells true first-person apart from third-person/other entities (including the Amendments hand-render path), so only a world-visible lantern shows sun shafts.

### Fixed
- **Dynamic Trees stopped growing** — a since-reverted `skyLevel` experiment briefly dropped effective light below the growth threshold.
- **`Cannot get config value before config is loaded`** crash on world/server shutdown.
- Lightning god-ray light-shaft beams (added, then removed): didn't hold up well enough to ship, on or off a shaderpack.

### Config
New / changed in `config/sunwell-server.toml`:
- `lightningThroughOdds` default `80000` → `40000`; `lightningRodBoost` default `40` → `20`
- `skyLevel` unchanged at `14` (kept growth-safe; see Fixed)
- `snowAccumulation`, `snowAccumulateOdds`, `snowMaxLayers`, `debugLightning`

---

## [2.1.0] - One lamp, four skies

The lantern is a window now. The orb shows the sky it stands for (sun, moon, rain cloud, or thunderhead) and tracks the real sky on the real clock.

### Added
- **Sky-mirroring orb.** Four looks with their own art and glow. Clear day, night, rain, thunderstorm.
- **Sky-driven transitions.** Dusk/dawn follow Minecraft's twilight ramp (~90s). Weather follows vanilla's ~5s ramp. No private fade timer.
- **Orb → cloud morph.** The orb flattens and spreads into cloud cover. Sun → moon is a cross-fade.
- **Lightning flashes the orb.** Storm bolts snap the orb and halo white in sync with the real flash.
- **Rain and snow through the shaft.** Falls across the lit cone with Sunwell's own rain particle.
- **Sprinkler for floor lamps.** Ground lamps throw weather outward and up, then arc back across the lit radius.
- **Cone lighting.** Ceiling lamps widen with depth (`coneSpread`). Higher lamp, wider floor pool. `0` = straight column.
- **Ceiling lamps light the floor.** Virtual sky under a lamp is visible near it, not only during rain.
- **Lightning through the lantern.** Rare visual bolt down a full-profile column in thunderstorms. Much more often with a lightning rod within 4 blocks (`lightningRodBoost`). Visual-only by default.
- **NeoForge 1.21.1** alongside 1.20.1 / Forge. (1.21.1 does not ship Amendments wall/hand integration.)

### Changed
- **One universal lamp.** Sunwell Lantern covers every sky. See Removed.
- **Light shafts are sunlight only.** Full in clear daylight. Gone at night and under cloud.
- **Lantern block light 15 → 8.** The room is lit by the sunwell, not by a torch-bright cage.
- **Storm crackle flicker removed.** Storm reads through art, tint, and dimming instead.
- Default reach up: `maxDepth` **24 → 40**, with a larger flood budget for the wider cone.
- Glow draws without depth-write, so it no longer tears against the cage.

### Fixed
- **Amendments wall placement never registered.** Lookup asked for the wrong method signature (`WallLanternPlacement` vs `AdditionalItemPlacement`). Wall placement works again. Fail-open path now logs the real cause.
- **Optional Amendments compat could fail the whole mod.** Amendments types are isolated behind a mod-present guard so missing Amendments cannot take Sunwell down at load.
- **Moon never appeared.** Time of day was read through a gamerule path that could disagree with `/time set`. It now uses the sun angle.
- **Rain showed storm artwork.** Rain and thunder are separate signals now.
- **Orb ~4× too dark at night.** Sky tint was applied twice.
- **Crash when changing weather** near active lamps (`ArrayIndexOutOfBoundsException` during model tesselation). Light snapshots are deep copies so meshing threads do not read maps the flood is still writing.
- **Removing a lamp clears its light** immediately.
- **Rain-through open-sky checks** (collectors, cauldrons, snow) work again through tagged columns during weather.

### Removed
- **Soulwell Lantern.** Breaking. See Migration.

### Migration
- Placed Soulwell Lanterns will not survive the update. Recipes, tags, and scripts using `sunwell:soulwell_lantern` must be updated or removed.
- Existing configs pick up new options on first launch.

### Config
New / changed in `config/sunwell-server.toml`:
- `coneSpread` (default `1`)
- `lightningRodBoost` (default `40`)
- `maxDepth` default **24 → 40**; `nodeBudgetPerTick` raised for the wider flood
- `debugSkyState`, `debugWeatherParticles` (default `false`)

---

## [2.0.63]

### Fixed
- Crash (`ArrayIndexOutOfBoundsException` during block model tesselation) when changing weather near active sunwell lamps.

## [2.0.62]

### Added
- Radiant orb glow + rotating light shafts; storm-reactive orb.
- Rare lightning through full-profile columns during thunderstorms, boosted near a lightning rod (`lightningRodBoost`).

### Fixed
- Removing a lone lamp clears its virtual-sky region.
- Weather sky-access (rain collectors, cauldrons, snow) restored through rain-through columns.
- Glow no longer tears against the cage.

## [2.0.61]

### Fixed
- Rain-through open-sky checks (`canSeeSky`) apply again during weather.

### Removed
- Stale packaging leftovers cleaned out of the jar.
