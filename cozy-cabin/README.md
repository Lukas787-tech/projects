# Riverside Hollow

A cosy, side-view farming game for Android. You inherit a leaning cabin between
the woods and the river: till the field, plant and water crops, chop wood,
forage, fish, sell your haul to Pip at the market, and slowly upgrade your home
from a little cabin into a manor.

## Download

Grab `RiversideHollow.apk` from the
[latest release](../../releases/tag/riverside-hollow-latest), open it on your
phone, and allow installs from your browser when Android asks. Requires Android
7.0 or newer.

## What's in it

- **Side-view valley** — forest, cabin, field, market and river along one clean
  scrolling map.
- **Farming** — six crops, some of which regrow. Water them, or let the rain do
  it for you.
- **Fishing** — cast, wait for the bob to dip, hook it, then keep the fish in
  the basket while you reel. Seven species, gated by time of day and weather.
- **Foraging & wood** — mushrooms, flowers, honey and acorns respawn daily;
  trees regrow a few days after felling.
- **A home you upgrade** — four visually distinct tiers, each unlocking more
  field plots, energy and bag space.
- **Day/night, weather, seasons of mood** — a continuous sky that moves through
  dawn, noon, golden hour and starlight, plus clear, cloudy and rainy days.
- **Full menus** — title, backpack, market (seeds/tools/sell/home), journal with
  a species collection and run stats, settings and a day-summary screen.
- **Settings** — music and sound volume, three graphics presets, frame counter,
  vibration, and a left-handed control layout.

## How it's built

Kotlin on a plain `SurfaceView` with a dedicated render thread. **Every visual
and every sound is generated procedurally at runtime** — there is not a single
image or audio file in the project. Trees, clouds, water, the cabin, item icons
and the character are all drawn with `Canvas` primitives, and the music and
effects come from a small built-in software synth writing PCM to an
`AudioTrack`. That is why the APK is a couple of megabytes and scales cleanly to
any screen density.

```
cozy-cabin/
  app/src/main/java/com/cozyhollow/riverside/
    MainActivity.kt   Activity, fullscreen, vibration
    GameView.kt       SurfaceView + render thread
    Game.kt           state machine, world sim, contextual actions, HUD
    Screens.kt        title, pause, bag, market, journal, settings, sleep
    Scene.kt          sky, parallax, terrain, river, cabin tiers, market
    Farm.kt           plots, crops, choppable trees, forageables
    Player.kt         procedurally animated character
    Fishing.kt        casting and the reeling mini-game
    Particles.kt      pooled particle system
    Audio.kt          software synth: generative music + SFX
    Items.kt          item/crop/fish catalogue + procedural icons
    Save.kt           save state, cabin tiers, tool upgrades, settings
    World.kt          static layout of the valley
    Ui.kt / Palette.kt / Util.kt
```

## Building it yourself

```
cd cozy-cabin
./gradlew assembleRelease
```

The release APK is signed with the checked-in `keystore/riverside.jks` so that
every build installs over the last one. That key is for personal sideloading
only — generate your own before publishing anywhere.
