# Ages of Siege — Game Design (working title)

> Build a medieval stronghold, survive endless raider invasions, explore a living world, and race rival AI lords
> through the ages — from catapults to tanks and jets.

Status: **approved 2026-11-27**. Phase 0–1 done; Phase 2 (world + troops) and the first rival are playable.

## Pillars
- **Defend** — satisfying tower-defense battles (towers, walls, troops, commander abilities).
- **Grow** — base building; eras visually and mechanically transform the base.
- **Explore** — fog-of-war world with resources, ruins, raider camps and relics.
- **Compete** — AI rival lords evolve in parallel; race, raid and conquer them.
- **Easy to play** — touch-first, 3–5 min battles, constant small rewards.

## Core loops
- **Battle (3–5 min):** raiders attack → towers/troops fight, abilities, build mid-battle with coins from kills → XP, coins, loot.
- **Session (10–20 min):** collect → build/upgrade → train → expeditions → defend a raid → shop.
- **Long term (days/weeks):** advance eras, expand territory, meet rivals, win the age race, conquer or build a Wonder.

## Systems
- **Base:** grid fortress around the Keep. Economy (house, lumber mill, quarry, warehouse), military, Blacksmith,
  University, Market, walls & gates. Keep level + research → Age Up.
- **Defense (open-field TD):** raiders enter from map-edge gates and path to the Keep (flow-field pathfinding).
  Walls/towers reshape the path ("mazing"), but a path must always remain. Speed 1×/2×/3×, pause.
- **The Marauders:** NPC raider faction that scales with the world age. Waves on a visible countdown with a preview;
  call early for bonus gold; boss (Warlord) every 5 waves. Their camps on the world map feed raids — clearing nearby camps
  calms them.
- **Eras:** Medieval → Gunpowder (+Iron) → Industrial → Modern (+Oil) → Future (post-launch). Towers modernize and keep
  their veterancy (Archer Tower → Musket Tower → Gatling Nest → MG Bunker).
- **World map:** procedural hex world per save, fog of war, resource nodes (era-gated iron/oil), ruins, camps, villages,
  relics; scout, gather, outposts, attack. Marches take real time.
- **Rival AI lords (1–5, default 2):** same rules as the player, personalities (Warlord, Builder, Explorer, Merchant).
  They start far away and explore their own area first; first contact after ~2–4 real days at Standard pace.
  Raids on gatherers/outposts, auto-resolved field battles, capital assaults both ways; defeated rivals pay tribute.
- **XP & coins:** towers/squads earn XP from kills and gain ranks I–V (+damage, +range). Attack towers can also reach level 5 through paid upgrades and choose a specialization at level 3.
  Kills drop coins.
- **Shop (Market):** gear upgrades, consumables (repair/heal kits, speed-ups, raid shields, maps, mercenaries), boosts,
  daily deals, resource trade. Gems are earned only by playing.
- **Time:** the world runs while the app is open. **While the app is closed, time is paused** (decided 2026-11-27);
  offline progression/raids will come with multiplayer.
- **Engagement:** daily quests, achievements, boss events, era-race alerts, tutorial, juice (hit flashes, coin bursts,
  explosions, screen shake).

## Look & feel
Stylized low-poly 3D "living diorama": tilted camera, soft shadows, ambient occlusion, bloom, vignette. All models are
generated in code (consistent style, no asset licensing issues); CC0 packs (Kenney, Quaternius) or custom models can be
swapped in later. Landscape, one-finger pan, pinch zoom, tap to build/select.

## Tech
| Module | Contents |
|---|---|
| `:core` | Pure Kotlin/JVM simulation: content, rules, flow-field pathfinding, waves, veterancy, saves. Deterministic fixed step (30 Hz) + seeded RNG. Unit-tested. Reusable by a future server. |
| `:app` | Android: Jetpack Compose HUD, Filament 3D renderer (materials compiled at runtime with filamat), procedural meshes, particles, input, save/load. |
| `:server` (future) | Ktor server running the same `:core` authoritatively. |

Rules for multiplayer-readiness: every action is a `Command`; AIs will issue the same commands as players; all content
lives in data (`Content.kt`); the simulation never depends on Android.

## Roadmap
| Phase | Deliverable | Status |
|---|---|---|
| 0 Foundation | Kotlin modules, Compose + Filament, procedural diorama, CI | ✅ |
| 1 "Hold the Keep" | Medieval base building, towers + walls, raider waves + boss, XP/ranks, coins, Volley ability, save/load, main/pause/settings menus, restart | ✅ playable |
| 2 World | World map (2D parchment v1), fog of war, scouting, caches, ruins, marauder camps, marches, auto-resolved battles; Barracks + troops (swordsman, bowman, knight) defending the base. Still to do: outposts, Shop v1, 3D world view | 🔨 first slice playable |
| 3 Rivals + Gunpowder | First AI rival done (grows, makes contact, raids your base, can be conquered → vassal tribute). Still to do: more rivals, Gunpowder era, research tree, tower evolution | 🔨 started |
| 4 Ages + polish | Industrial + Modern, air units, victory conditions, quests, tutorial, audio, balancing | |
| 5 Friends | Private builds to share with friends (APK / internal testing) | |
| 6 Online | Accounts, cloud saves, shared online world | |
