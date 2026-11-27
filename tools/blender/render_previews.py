"""Renders exported game models: a QA contact sheet and/or transparent UI icons.

  blender --background --factory-startup --python tools/blender/render_previews.py -- <project-root> sheet|icons
"""
import json
import math
import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(__file__))
import common as c  # noqa: E402

ROOT, MODE = sys.argv[sys.argv.index("--") + 1:][:2]
MODELS = os.path.join(ROOT, "app", "src", "main", "assets", "models")
ICONS = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
OUTDIR = os.path.join(ROOT, "tools", "blender", "out")
with open(os.path.join(MODELS, "models.json")) as f:
    META = json.load(f)


def load(name, x=0.0, y=0.0, z=0.0, rot=0.0, scale=1.0, pose=None, frame=6):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=os.path.join(MODELS, name + ".glb"))
    new = [o for o in bpy.data.objects if o not in before]
    for r in [o for o in new if o.parent is None]:
        r.location = c.gl(x, y, z)
        r.rotation_euler = (0, 0, math.radians(rot))
        r.scale = (scale,) * 3
    if pose:
        for o in new:
            if o.type == 'ARMATURE':
                o.animation_data_create()
                o.animation_data.action = bpy.data.actions[pose]
                bpy.context.scene.frame_set(frame)
    return new


def tower(kind, level, x=0.0, z=0.0):
    objs = load("%s_%d" % (kind, level), x=x, z=z)
    head_y = META["%s_%d" % (kind, level)]["headY"]
    if kind == "archer":
        objs += load("archer_crew", x=x, y=head_y, z=z, scale=0.5, pose="holding-both-shoot")
    else:
        objs += load("%s_head" % kind, x=x, y=head_y, z=z, rot=-30)
    return objs


def sheet():
    c.reset()
    rows = [
        [("keep", None)],
        [("archer", 1), ("archer", 2), ("archer", 3), ("ballista", 1), ("ballista", 2), ("ballista", 3)],
        [("catapult", 1), ("catapult", 2), ("catapult", 3), ("house_1", None), ("house_2", None), ("house_3", None)],
        [("lumber_1", None), ("lumber_2", None), ("lumber_3", None), ("quarry_1", None), ("quarry_2", None), ("quarry_3", None)],
        [("wall_block", None), ("wall_post", None), ("grove", None), ("pines", None), ("boulders", None), ("selection", None)],
        [("raider", "walk"), ("brute", "walk"), ("outrider", "sprint"), ("warlord", "walk"), ("arrow", None), ("boulder", None)],
    ]
    for r, row in enumerate(rows):
        for i, (name, arg) in enumerate(row):
            x, z = i * 1.6, r * 1.9
            if name in ("archer", "ballista", "catapult"):
                tower(name, arg, x=x, z=z)
            elif arg in ("walk", "sprint"):
                load(name, x=x, z=z, scale=0.5, pose=arg, frame=8)
            else:
                load(name, x=x + (0.8 if name == "keep" else 0.0), z=z)
    c.setup_render(res=1400, transparent=False)
    c.frame_camera(margin=0.9, yaw=20.0, pitch=40.0)
    c.render(os.path.join(OUTDIR, "sheet.png"))


ICON_SET = {
    "archer": lambda: tower("archer", 1),
    "ballista": lambda: tower("ballista", 1),
    "catapult": lambda: tower("catapult", 1),
    "wall": lambda: load("wall_block"),
    "house": lambda: load("house_1"),
    "lumber": lambda: load("lumber_1"),
    "quarry": lambda: load("quarry_1"),
}


def icons():
    for key, fn in ICON_SET.items():
        c.reset()
        objs = fn()
        c.setup_render(res=256, transparent=True)
        c.frame_camera(objs, margin=1.0, yaw=35.0, pitch=28.0)
        c.render(os.path.join(ICONS, "ic_build_%s.png" % key))


def chars():
    c.reset()
    for i, name in enumerate(["raider", "brute", "outrider", "warlord", "archer_crew"]):
        load(name, x=i * 1.3, z=0.0, pose="walk", frame=8)
        load(name, x=i * 1.3, z=1.6, pose="holding-right", frame=2)
    c.setup_render(res=1200, transparent=False)
    c.frame_camera(margin=0.75, yaw=25.0, pitch=18.0)
    c.render(os.path.join(OUTDIR, "chars.png"))


if MODE == "sheet":
    sheet()
elif MODE == "chars":
    chars()
else:
    icons()
print("DONE", MODE)
