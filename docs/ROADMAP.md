# Roadmap & status

Living plan for Ages of Siege. Update the status column whenever something ships so work can continue on another day.
Approved by the user on 2026-11-28 (see "Decisions" below). Next big mode after this list: **Evolution mode**.

## Decisions (2026-11-28)
- **Campaign stars:** ★★★ = no raider reached the Keep, ★★ = up to the level's leak limit, ★ = won with more leaks.
  The next level unlocks after a win with at least one star; chapter 2 needs 12★, chapter 3 needs 27★.
- **Economy in levels:** gold only, from kills, wave rewards and calling waves early. There are no production buildings.
  Tower prices in levels are the realm price converted to gold (gold + wood + stone).
- **Maps:** a mix of road levels (raiders stay on the road) and open-field levels (mazing with walls).
- **No hero unit.**
- **Scope:** all five steps below.
- **Server:** the friends' Raspberry Pi 5 (reached via Tailscale, `server/`) also stores the leaderboards.

## Step status
| Step | Content | Status |
|---|---|---|
| 1 Campaign core | `LevelDef`/`MapSpec` maps (`core/Levels.kt`), win/lose, stars, gold-only economy, 18 levels in 3 chapters, `PlayerProfile` progress (`core/Profile.kt`), level-select map, briefing, results | ✅ |
| 2 Juice + audio | Kenney CC0 SFX + synthesized sounds, procedural music with battle drums, vibration, tower rise animation, upgrade rings, projectile trails, coins flying to the HUD, hit-stop and slow motion, burning Keep, opening camera flight, per-chapter lighting/fog/weather, star-stamp results | ✅ |
| 3 Depth | Targeting priority (First/Strongest/Fastest/Closest), new raiders (Shieldbearer, War Shaman, Sky Raider, Battering Ram), War Banner support tower, Shatter combo (fire on chilled raiders), Tar Pit + Militia abilities, Warlord boss (enrage, summons, smashes walls), illustrated campaign map on the 3D realm | ✅ |
| 4 Meta | Glory from stars/achievements/quests, War Council perks, Heroic/Iron modes, Endless mode, 14 achievements, 3 daily quests, gilded trims for veteran tower types | ✅ |
| 5 Competitive | Daily Challenge (same seeded level and modifiers for everyone), friends leaderboards on the Pi (`server/scores.mjs`, `AOS1 SCORES <lobby key>`), Race mode (same level on two phones, kills earn raider squads sent to the opponent) | ✅ Deployed to the Pi on 2026-11-28 and checked from the app. Race still needs a two-phone test |

### Deploying the server (Pi 5, `pi5@192.168.50.101`, SSH key login, passwordless sudo)
There is no git checkout on the Pi: the files are copied over.
1. `scp server/*.mjs server/install-pi.sh server/ages-of-siege-relay.service pi5@192.168.50.101:ages-of-siege-server/`
2. On the Pi: `cd ~/ages-of-siege-server && sed -i 's/\r$//' *.sh *.service *.mjs && node --test relay.test.mjs && sudo sh install-pi.sh`

`install-pi.sh` installs the files into `/opt/ages-of-siege`, keeps the keys in `/etc/ages-of-siege/relay.env`, and stores
scores in `/var/lib/ages-of-siege/scores.json`. For a live check, run
`node selftest.mjs <lobby key>` on the Pi. It writes a `selftest-0` board, so delete `scores.json` afterwards if needed.
The backup of the pre-leaderboard install is in `~/ages-of-siege-backup-20261128` on the Pi.
Public endpoint: Tailscale Funnel `ages-of-siege-pi5.tail20b2a.ts.net:443` → `127.0.0.1:47822`.

### Known gaps / next polish
- The Kenney ground model has the realm's dirt entrances baked in. Campaign maps with other gates show leftover dirt
  patches. Rebuild `ground.glb` without them (tools/blender/build_assets.py) and draw entrances procedurally instead.
- Balance: `LevelsTest.aSimpleBotCanBeatTheFirstLevels` prints a report. A non-mazing bot wins 11/18 levels and loses
  the boss and multi-gate open fields. Tune with real play feedback.
- Leaderboards accept scores as reported (friends only). Later: send the command log and re-simulate on the server
  (the simulation is deterministic) to verify scores.
- Race mode: test on two phones; consider sending mixed squads and a "first to finish" bonus.
- Snow/marsh ground tint (today only lighting, fog and weather change per chapter).
- More levels and chapters (Gunpowder era levels once eras exist).

## Next: Evolution mode (not started)
The user's next request after the five steps: a long-term **city-building mode like Travian**, evolving the city through the
eras **from medieval to modern times**. Notes for planning (present a plan and get approval first, see
`docs/GAME_DESIGN.md` → Eras):
- **Shape:** persistent city and villages on the realm map. Resource fields (wood, clay/stone, iron, food) are upgraded over
  real time. Building queues with timers, warehouse limits, troop training queues, and marches that take real time.
  Travian-like, but with this game's 3D realm and tower defense when raids hit.
- **Eras:** Medieval → Gunpowder → Industrial → Modern (later Future). Each era unlocks buildings, towers (Archer → Musket →
  Gatling → MG Bunker, keeping veterancy) and units, and changes the look of the city.
- **Time:** design for offline progression (timers keep running while the app is closed). This needs a server-side
  simulation or careful catch-up math in `:core` (deterministic, command-based), which fits the multiplayer plan.
- **Multiplayer:** a shared world on the Pi with friends: trade, alliances, raids. The relay would grow into an
  authoritative `:core`-based server (Kotlin/JVM on the Pi), or the Node relay stores the state while phones simulate.
- **Reuse:** `World`/`RealmView` (map), `BaseGame` (defending raids as tower defense), `Campaign` rival AI, the
  `PlayerProfile` save pattern, and the friends lobby for identities.
- **Open questions for the user:** separate save or replaces the realm sandbox? How fast are timers (minutes vs hours)?
  Shared persistent server world with friends from day one, or single-player first?
