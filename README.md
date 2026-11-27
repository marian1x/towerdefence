# Ages of Siege (working title)

A tower-defense / base-building strategy game for Android: defend your medieval Keep against Marauder raids, grow your
base, and (soon) explore the world and race rival AI lords through the ages.

![status](https://img.shields.io/badge/status-prototype-orange)

## Play the prototype
1. Open the project in Android Studio (AGP 9.5, JDK 21+).
2. Run the `app` configuration on a device or emulator (Android 8.0+, OpenGL ES 3.0).

**How to play:** pick a card at the bottom and tap tiles to build. Raiders walk from the gates on the left (and the top
from wave 6) to your Keep — use towers and walls to stop them, but always leave a path. Tap a tower to upgrade or sell it.
Towers gain veterancy ranks from kills. Use **Volley** for emergencies and **Call now** to start a wave early for bonus gold.
Drag to pan, pinch to zoom.

## Play with a friend

Use **Same Wi-Fi** for local matches. For internet matches, choose **Online**. The host enters the relay hostname
and host key, then copies the room invite. The friend selects **Online → Join a game** and pastes the invite.

The relay in `server/` runs on a Pi with Node.js and systemd. Run `sudo sh server/install-pi.sh` on the Pi to
install it and generate a host key in `/etc/ages-of-siege/relay.env`. It listens on loopback port 47822. On a Pi
signed into Tailscale, publish just that port using
`sudo tailscale funnel --bg --tls-terminated-tcp=443 tcp://127.0.0.1:47822`. Use the resulting public hostname
in the game. Keep the host key private and share the room invite with the other player.

## Project layout
- `core/` — pure Kotlin game simulation (rules, content, pathfinding, saves) with unit tests: `./gradlew :core:test`
- `app/` — Android app: Jetpack Compose HUD + Filament 3D renderer with procedurally generated low-poly models
- `docs/GAME_DESIGN.md` — concept, systems and roadmap
- `docs/ART_PIPELINE.md` — how the 3D models, icons and lighting are built with Blender + Filament tools
- `tools/blender/` — asset pipeline scripts

## Credits
Models: [Kenney](https://kenney.nl) (CC0). Sky lighting: [Poly Haven](https://polyhaven.com) HDRI (CC0).
Font: Lilita One (SIL OFL, see `licenses/`).
