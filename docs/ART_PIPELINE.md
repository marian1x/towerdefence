# Art pipeline

All 3D art is built from free CC0 asset packs with **Blender (command line)**, so it can be regenerated and tweaked
with one command. Nothing here needs the Blender UI.

## Tools (installed portable, outside the repo)
| Tool | Location | Used for |
|---|---|---|
| Blender 5.2.5 | `C:\Users\maria\Tools\blender-5.2.5\blender-5.2.5-windows-x64\blender.exe` | composing, merging and exporting models, rendering UI icons |
| Filament 1.81.0 tools | `C:\Users\maria\Tools\filament-1.81.0\bin` | `cmgen` (sky lighting from HDRI), `gltf_viewer` (preview GLBs), `matc` |
| Source packs | `C:\Users\maria\Tools\assets-src` | Kenney packs, HDRI, fonts (set `AOS_ASSETS_SRC` to override) |

## Sources (all free for commercial use)
- Kenney (CC0): Tower Defense Kit, Castle Kit, Fantasy Town Kit, Nature Kit, Mini Characters — https://kenney.nl
- Poly Haven (CC0): HDRI `kloofendal_48d_partly_cloudy_puresky` — https://polyhaven.com
- Lilita One font (SIL Open Font License) — see `licenses/`

## Commands (run from the project root)
```powershell
$B = 'C:\Users\maria\Tools\blender-5.2.5\blender-5.2.5-windows-x64\blender.exe'
# Build all game models -> app/src/main/assets/models (+ models.json metadata)
& $B --background --factory-startup --python tools\blender\build_assets.py -- "$PWD"
# Rebuild only some models
& $B --background --factory-startup --python tools\blender\build_assets.py -- "$PWD" keep archer_1
# Build-menu icons -> app/src/main/res/drawable-nodpi/ic_build_*.png
& $B --background --factory-startup --python tools\blender\render_previews.py -- "$PWD" icons
# QA contact sheet / character close-ups -> tools/blender/out/
& $B --background --factory-startup --python tools\blender\render_previews.py -- "$PWD" sheet
& $B --background --factory-startup --python tools\blender\render_previews.py -- "$PWD" chars
# Sky lighting from an HDRI
& C:\Users\maria\Tools\filament-1.81.0\bin\cmgen.exe --format=ktx --size=256 --deploy=<out> <file.hdr>
```

## Conventions
- 1 unit = 1 tile; models face +Z; origin at the base centre. Static models are merged into one mesh (one draw call).
- Towers store `headY` (weapon/crew mount height) in `models.json`; the game reads it at runtime.
- Characters keep only the animations the game uses (walk, sprint, idle, die, attack, shoot poses).
- To restyle: edit the composition functions in `tools/blender/build_assets.py`, rebuild, check `sheet.png`.
