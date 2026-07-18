# Changelog

Notable changes to **Sunwell**. Format based on [Keep a Changelog](https://keepachangelog.com/).

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
