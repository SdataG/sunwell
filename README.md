<div align="center">

# ☀️ Sunwell

### A portable hole in the ceiling. Real sky light underground — on your terms.

[![CurseForge Downloads](https://cf.way2muchnoise.eu/full_sunwell_downloads.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/sunwell)
[![Supported Versions](https://cf.way2muchnoise.eu/versions/sunwell.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/sunwell)
[![Loader](https://img.shields.io/badge/Forge%20%7C%20NeoForge-orange?style=for-the-badge)](https://www.curseforge.com/minecraft/mc-mods/sunwell)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

_AI used for the description and icon (not in the mod, beyond a normal assistant)._

</div>

---

Sunwell adds craftable **Sunwell Lanterns** that project true **Minecraft sky light** into enclosed spaces. Crops grow, Dynamic Trees survive, daylight sensors respond, rain and snow fall through, and lightning cracks down the column in a storm.

Like open sky — except you hang it from a ceiling. Pack makers get a **tag-driven engine**: register any block as a simple grow lamp or a full weather-aware sunwell, no Java required.

---

## 📥 Install

1. Install **Forge 47.4+** (Minecraft **1.20.1**) or **NeoForge 21.1+** (Minecraft **1.21.1**).
2. Download the matching jar from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/sunwell).
3. Drop it in your `mods` folder and launch.

Optional: [Amendments](https://www.curseforge.com/minecraft/mc-mods/amendments) + Moonlight Lib add wall placement and a proper held pose on **both** builds. Without them, Sunwell still works.

---

## ✨ Why not just a torch?

| | 💡 Sunwell Lantern | 🔥 Torch / Glowstone |
|:--|:--|:--|
| **Light type** | **Sky light** — what plants actually check | Block light only |
| **Grows Dynamic Trees** | ✅ Yes (needs ≥ 12 sky light) | ❌ No |
| **Daylight sensors** | ✅ Yes, when exposed to "day" | ❌ No |
| **Reaches the floor** | ✅ A widening cone — higher lamp, wider pool | ❌ Falls off with distance |
| **Weather & time** | ⚙️ Optional (tag + config) | Always on |
| **Mob spawns** | 🚫 Suppressed in the lit region | Normal rules |

---

## 🏮 The Sunwell Lantern

- 🪝 **Vanilla lantern placement** — stands on the floor or hangs from a ceiling.
- 🔦 **Projects sky light in a downward-widening cone** — hang it higher to light a wider pool of floor. Like a small sun.
- 🌗 **The orb mirrors the sky** it stands in for — sun, moon, cloud or thunderhead _(see below)_.
- 🧪 **Recipe:** Quartz ×3 · Iron Bars ×4 · Glowstone · Glass.

> [!IMPORTANT]
> **Upgrading from 2.0.x** — the **Soulwell Lantern has been removed** (one lamp now presents every sky). Placed Soulwell Lanterns will not survive the update, and anything referencing `sunwell:soulwell_lantern` must be updated.

---

## 🌤️ The orb is a window

Look into the lantern and you see the sky it stands in for. It changes **when** the sky changes and **as fast as** the sky changes.

| Sky | The orb becomes |
|:--|:--|
| ☀️ **Clear day** | A warm sun with slowly rotating **radiant shafts** |
| 🌙 **Night** | A pale, cool moon — dimmer, no shafts |
| 🌧️ **Rain** | A flat, grey overcast cloud |
| ⛈️ **Thunderstorm** | A dark thunderhead that **flashes white** with every bolt |

---

## ⛅ Weather in the cone

Tag a lantern for full weather (or use the default Sunwell Lantern) and the sky comes down with it:

- 🌧️ **Rain** falls through the **whole lit pool** and splashes where it lands — a light drizzle while it rains, a heavy downpour while it storms.
- ❄️ **Snow** in cold biomes drifts down and **gradually piles up into snow layers on the floor**, whitening the lit area over time.
- ⚡ **Lightning** strikes down the column in thunderstorms. It lands somewhere in the pool and **leans toward the tallest target** — a tree, a mob, a player — while staying random. A **lightning rod** anywhere in the pool **catches it harmlessly** and powers its redstone. Far more likely to strike near a rod.

---

## 🏷️ Tag-driven behavior

Every sunwell block needs **`#sunwell:sunwell_source`**. That alone is a **static grow light** — always-on virtual sky, no weather, no rain. Opt into more with extra tags:

| Tag | Effect |
|:--|:--|
| `sunwell:sunwell_source` | **Required.** Flood-fills virtual sky light for crops & trees |
| `sunwell:dynamic_exposure` | Light follows **day/night**, **surface sky** and **weather** |
| `sunwell:undead_burning` | Undead **sun-burn** in the lit region during the day |
| `sunwell:rain_through` | **Rain, snow and lightning** come through — and snow settles into layers |

The **Sunwell Lantern** ships with all four. **Full weather presentation** (sky-mirroring orb, rain/snow, snow buildup, lightning) needs **both** `dynamic_exposure` **and** `rain_through`.

<details>
<summary><b>📦 Register your own block (datapack & KubeJS)</b></summary>

**Datapack** — `data/sunwell/tags/blocks/sunwell_source.json` (folder is singular `tags/block` on 1.21+):

```json
{
  "replace": false,
  "values": [
    "yourmod:ceiling_lamp"
  ]
}
```

**KubeJS** — static grow lamp, or the full weather-aware treatment:

```js
ServerEvents.tags('block', event => {
  // static grow lamp
  event.add('sunwell:sunwell_source', 'yourmod:ceiling_lamp')

  // full treatment
  const block = 'yourmod:solar_panel'
  event.add('sunwell:sunwell_source',   block)
  event.add('sunwell:dynamic_exposure', block)
  event.add('sunwell:undead_burning',   block)
  event.add('sunwell:rain_through',     block)
})
```

Tags grant capability; the config toggles below act as pack-wide master switches over them.

</details>

---

## 🎛️ Configuration

All options live in `config/sunwell-server.toml`.

<details>
<summary><b>⚙️ Full config reference</b></summary>

**Region & light**

| Option | Default | Description |
|:--|:--:|:--|
| `maxRadius` | `12` | Horizontal spread from each source (blocks) |
| `maxDepth` | `40` | Maximum depth below a source (blocks) |
| `coneSpread` | `1` | Extra radius gained **per block of descent**. `0` = a straight column |
| `skyLevel` | `14` | Virtual sky brightness. 14 grows everything; **15** triggers vanilla `canSeeSky` |
| `attenuateByDepth` | `false` | Light falls off with distance from the source |

**Behavior — weather, burning, spawns, lightning, snow**

| Option | Default | Description |
|:--|:--:|:--|
| `followDayNightCycle` | `true` | Dim sunwell light at night for `dynamic_exposure` sources |
| `respondToWeather` | `true` | Rain and storms dim `dynamic_exposure` sources |
| `respondToSurfaceLight` | `true` | Tie strength to the real outdoor sky above the column |
| `lanternFlux` | `true` | Subtle flicker on `dynamic_exposure` sources |
| `allowRainThrough` | `true` | Master switch for rain/snow through `rain_through` tags |
| `weatherShaftParticles` | `true` | Client rain/snow shafts (full lantern profile only) |
| `snowAccumulation` | `true` | Build up snow layers on the floor while it snows |
| `snowAccumulateOdds` | `12` | 1-in-N tick chance to add snow in a snowing cone. **Higher = slower** |
| `snowMaxLayers` | `8` | How deep snow piles (1–8 layers; `8` = a full block) |
| `lightningThroughOdds` | `80000` | 1-in-N tick chance of lightning through a full column. `0` = off |
| `lightningRodBoost` | `40` | Lightning this many times likelier with a rod within 4 blocks. `1` = no bonus |
| `lightningVisualOnly` | `false` | `true` = flash + sound only. Default `false`: a real strike does fire/damage, but a rod always catches it harmlessly |
| `enableUndeadBurning` | `true` | Master switch for undead burn on `undead_burning` tags |
| `blockHostileSpawns` | `true` | Block monster spawns inside any lit region |

**Performance** — `nodeBudgetPerTick` (`80000`, flood-fill cells/tick) and `chunkBudgetPerTick` (`24`, chunks rebuilt/tick). Raise both if you run many high-ceiling lamps with a large `coneSpread`.

</details>

---

## 📦 Supported versions

| Minecraft | Loader | Java |
|:--|:--|:--:|
| **1.20.1** | Forge 47.4+ | 17 |
| **1.21.1** | NeoForge 21.1+ | 21 |

Both builds are feature-complete — lantern, VFX, tag system and Amendments wall/hand-lantern compat. On 1.21.1 the datapack folders are singular (`tags/block`, `recipe`, `loot_table`).

---

## 📜 Credits & license

**Sunwell** is an original mod by **SdataG** — all code, models, textures and VFX authored for this project; no assets ported. The ceiling-skylight idea is familiar in modding (e.g. [Ferreus Veritas's Skylight Lanterns](https://gitlab.com/ferreusveritas/skylightlanterns)); Sunwell is an independent reimplementation.

<div align="center">

**License:** MIT · [**CurseForge**](https://www.curseforge.com/minecraft/mc-mods/sunwell) · [**Source**](https://github.com/SdataG/Sunwell) · [**Changelog**](CHANGELOG.md)

</div>
