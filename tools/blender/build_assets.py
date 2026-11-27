"""Builds every game model from the Kenney CC0 packs.

  blender --background --factory-startup --python tools/blender/build_assets.py -- <project-root> [only-name ...]

Outputs:
  app/src/main/assets/models/<name>.glb   game models (static ones merged into a single mesh)
  app/src/main/assets/models/models.json  metadata (weapon mount heights, animations)

The base layout below must match core BaseGame (26x16 grid, gates, keep position).
"""
import json
import math
import os
import random
import sys

import bpy
from mathutils import Matrix, Vector

sys.path.insert(0, os.path.dirname(__file__))
import common as c  # noqa: E402

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
ROOT = ARGS[0] if ARGS else os.getcwd()
ONLY = set(ARGS[1:])
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")
META_PATH = os.path.join(OUT, "models.json")

# Must match com.marian1x.agesofsiege.core.BaseGame
GRID_W, GRID_H = 26, 16
GATES = [(0, 3), (0, 12), (12, 0)]
KEEP = (21, 7, 2, 2)
BORDER = 3

# Height of a crenellated "top" piece's floor above its bottom (weapons stand on it).
TOP_FLOOR = 0.16

meta = {}
if os.path.exists(META_PATH):
    with open(META_PATH) as f:
        meta = json.load(f)


# ------------------------------------------------------------------ helpers

def place(pack, name, x=0.0, y=0.0, z=0.0, rot=0.0, scale=1.0):
    objs = c.imp(pack, name, at=(x, y, z), rot_y=rot, scale=scale)
    lo, hi = c.bounds(objs)
    return objs, hi.z - lo.z


def stack(pack, pieces, scale=1.0, x=0.0, z=0.0):
    """Stacks pieces vertically. Returns the height where the last piece starts and the total height."""
    y = 0.0
    last_start = 0.0
    for p in pieces:
        last_start = y
        _, h = place(pack, p, x=x, y=y, z=z, scale=scale)
        y += h
    return last_start, y


def build_static(name, fn):
    if ONLY and name not in ONLY:
        return
    c.reset()
    info = fn() or {}
    c.join_static(name)
    lo, hi = c.bounds()
    info["height"] = round(hi.z - lo.z, 3)
    meta[name] = {**meta.get(name, {}), **info}
    c.export_glb(os.path.join(OUT, name + ".glb"), animations=False)
    print("BUILT", name, info)


# Prototype cache for building the big ground mesh quickly (linked duplicates, joined at the end).
_protos = {}


def proto(pack, name):
    key = pack + ":" + name
    if key not in _protos:
        objs = c.imp(pack, name)
        ms = c.meshes(objs)
        bpy.ops.object.select_all(action='DESELECT')
        for o in ms:
            o.select_set(True)
        bpy.context.view_layer.objects.active = ms[0]
        bpy.ops.object.parent_clear(type='CLEAR_KEEP_TRANSFORM')
        if len(ms) > 1:
            bpy.ops.object.join()
        p = bpy.context.view_layer.objects.active
        p.hide_render = True
        p.hide_set(True)
        for o in objs:
            if o.type == 'EMPTY':
                bpy.data.objects.remove(o, do_unlink=True)
        _protos[key] = p
    return _protos[key]


def instance(pack, name, x, y, z, rot=0.0, scale=1.0):
    p = proto(pack, name)
    o = p.copy()
    o.data = p.data.copy()  # single-user mesh so transforms can be applied before joining
    o.hide_render = False
    bpy.context.scene.collection.objects.link(o)
    o.hide_set(False)
    o.matrix_world = Matrix.Translation(c.gl(x, y, z)) @ Matrix.Rotation(math.radians(rot), 4, 'Z') @ Matrix.Scale(scale, 4)
    return o


def join_instances(name):
    for p in _protos.values():
        bpy.data.objects.remove(p, do_unlink=True)
    _protos.clear()
    return c.join_static(name)


# ------------------------------------------------------------------ ground

def ground():
    rnd = random.Random(7)
    roads = {}
    for gx, gy in GATES:
        if gx == 0:
            for x in range(-BORDER, 1):
                roads[(x, gy)] = ('ew', x == -BORDER)
        else:
            for y in range(-BORDER, 1):
                roads[(gx, y)] = ('ns', y == -BORDER)
    decor = [("tile", 62), ("tile-tree", 12), ("tile-tree-double", 8), ("tile-tree-quad", 5), ("tile-rock", 6), ("tile-hill", 4), ("tile-bump", 3)]
    total = sum(w for _, w in decor)

    def pick():
        r = rnd.uniform(0, total)
        for n, w in decor:
            r -= w
            if r <= 0:
                return n
        return "tile"

    for gx in range(-BORDER, GRID_W + BORDER):
        for gy in range(-BORDER, GRID_H + BORDER):
            inside = 0 <= gx < GRID_W and 0 <= gy < GRID_H
            ring = min(gx + BORDER, gy + BORDER, GRID_W + BORDER - 1 - gx, GRID_H + BORDER - 1 - gy)
            road = roads.get((gx, gy))
            if road is None and not inside:
                # Organic island outline: thin out the outer rings.
                if ring == 0 and rnd.random() < 0.45:
                    continue
                if ring == 1 and rnd.random() < 0.08:
                    continue
            cx, cz = gx + 0.5, gy + 0.5
            if road:
                axis, is_end = road
                rot = 0.0 if axis == 'ns' else 90.0
                if is_end:
                    instance("td", "tile-end-round", cx, -0.2, cz, rot=(180.0 if axis == 'ns' else 90.0))
                else:
                    instance("td", "tile-straight", cx, -0.2, cz, rot=rot)
            elif inside:
                instance("td", "tile", cx, -0.2, cz)
            else:
                instance("td", pick(), cx, -0.2, cz, rot=rnd.choice([0, 90, 180, 270]))
    # A few extra trees and rocks on the border for richness.
    for _ in range(40):
        side = rnd.randrange(4)
        t = rnd.random()
        d = rnd.uniform(1.2, BORDER - 0.3)
        if side == 0:
            x, z = -d, t * GRID_H
        elif side == 1:
            x, z = GRID_W + d, t * GRID_H
        elif side == 2:
            x, z = t * GRID_W, -d
        else:
            x, z = t * GRID_W, GRID_H + d
        if any((gx == 0 and abs(z - gy - 0.5) < 1.2 and x < 0) or (gy == 0 and abs(x - gx - 0.5) < 1.2 and z < 0) for gx, gy in GATES):
            continue
        name = rnd.choice(["detail-tree", "detail-tree-large", "detail-tree", "detail-rocks"])
        instance("td", name, x, 0.0, z, rot=rnd.uniform(0, 360), scale=rnd.uniform(0.9, 1.3))
    join_instances("ground")
    return {"top": 0.0}


# ------------------------------------------------------------------ obstacles inside the base

def grove():
    place("td", "detail-tree-large", x=-0.12, z=-0.08)
    place("td", "detail-tree", x=0.22, z=0.18, rot=40)
    place("td", "detail-tree", x=-0.22, z=0.26, rot=110, scale=0.8)


def pines():
    place("town", "tree-high", x=0.0, z=0.0, scale=0.32)
    place("td", "detail-tree", x=0.27, z=0.22, rot=40, scale=0.9)


def boulders():
    place("td", "detail-rocks-large", x=-0.05, z=0.0)
    place("td", "detail-rocks", x=0.25, z=0.22, rot=60)


# ------------------------------------------------------------------ keep

def keep():
    # 2x2 footprint centred on the origin.
    s = 0.62
    for x in (-0.7, 0.7):
        for z in (-0.7, 0.7):
            _, h = place("castle", "tower-hexagon-base", x=x, z=z, scale=s)
            place("castle", "tower-hexagon-roof", x=x, y=h, z=z, scale=s)
    # Curtain walls.
    for t in (-0.25, 0.25):
        for (x, z) in ((t, 0.72), (t, -0.72), (0.72, t), (-0.72, t)):
            place("castle", "wall", x=x, z=z, scale=0.5)
    # Central donjon.
    _, h1 = place("castle", "tower-square-base", scale=0.82)
    _, h2 = place("castle", "tower-square-mid-windows", y=h1, scale=0.82)
    place("castle", "tower-square-top-roof-high-windows", y=h1 + h2, scale=0.82)
    # Gate and banners.
    place("castle", "gate", x=0.0, z=0.95, rot=90, scale=0.75)
    place("castle", "flag-banner-long", x=0.42, y=0.35, z=0.2, rot=90, scale=0.5)
    place("castle", "flag-banner-long", x=-0.42, y=0.35, z=0.2, rot=90, scale=0.5)
    place("castle", "flag", x=0.0, y=h1 + h2 + 0.85, z=0.0, rot=-90, scale=0.6)


# ------------------------------------------------------------------ towers (base + head)

ARCHER_STACKS = {1: ["tower-round-bottom-a", "tower-round-top-a"],
                 2: ["tower-round-bottom-a", "tower-round-middle-a", "tower-round-top-a"],
                 3: ["tower-round-bottom-b", "tower-round-middle-a", "tower-round-middle-b", "tower-round-top-b"]}
BALLISTA_STACKS = {1: ["tower-round-bottom-c", "tower-round-top-c"],
                   2: ["tower-round-bottom-c", "tower-round-middle-c", "tower-round-top-c"],
                   3: ["tower-round-bottom-b", "tower-round-middle-b", "tower-round-middle-c", "tower-round-top-b"]}
CATAPULT_STACKS = {1: ["tower-round-base"],
                   2: ["tower-round-bottom-a", "tower-round-top-b"],
                   3: ["tower-round-bottom-b", "tower-round-middle-b", "tower-round-top-a"]}


def tower_builder(pieces, floor_offset):
    def fn():
        last_start, total = stack("td", pieces)
        head_y = last_start + floor_offset if len(pieces) > 1 else total
        return {"headY": round(head_y, 3)}
    return fn


def weapon(name, scale=1.0):
    def fn():
        place("td", name, scale=scale)
    return fn


# ------------------------------------------------------------------ walls & buildings

def wall_block():
    objs = c.imp("castle", "wall")
    for o in objs:
        if o.parent is None:
            o.scale = (1.0, 1.0, 0.5)  # half height so walls stay below tower tops


def wall_post():
    place("castle", "tower-square")


def house(level):
    def fn():
        s = 0.72
        y = 0.0
        floors = 1 if level == 1 else 2
        for f in range(floors):
            stone = level >= 2 and f == 0
            front = ("wall-door" if stone else "wall-wood-door") if f == 0 else ("wall-window-shutters" if stone else "wall-wood-window-shutters")
            side = "wall-window-small" if stone else "wall-wood-window-shutters"
            back = "wall" if stone else "wall-wood"
            place("town", front, y=y, rot=-90, scale=s)
            place("town", side, y=y, rot=0, scale=s)
            place("town", side, y=y, rot=180, scale=s)
            place("town", back, y=y, rot=90, scale=s)
            y += 1.0 * s
        place("town", "roof-high-point" if level >= 2 else "roof-point", y=y, scale=s)
        if level >= 3:
            place("town", "chimney", y=y - 0.1, rot=0, scale=s)
            place("town", "banner-red", x=0.0, y=0.25, z=0.4, rot=-90, scale=s)
    return fn


def lumber(level):
    def fn():
        s = 0.62
        place("town", "wall-wood-door", x=-0.12, rot=-90, scale=s)
        place("town", "wall-wood", x=-0.12, rot=0, scale=s)
        place("town", "wall-wood-window-shutters", x=-0.12, rot=180, scale=s)
        place("town", "wall-wood", x=-0.12, rot=90, scale=s)
        place("town", "roof-gable", x=-0.12, y=0.62, scale=s)
        for i, (lx, ly) in enumerate([(0.0, 0.0), (0.16, 0.0), (0.08, 0.13)]):
            place("castle", "tree-log", x=0.33, y=0.1 + ly, z=-0.2 + lx * 2.2, rot=0, scale=0.55)
        if level >= 2:
            place("town", "cart", x=0.33, z=0.3, rot=0, scale=0.3)
        if level >= 3:
            place("town", "wheel", x=-0.12, y=0.3, z=0.36, rot=0, scale=0.25)
    return fn


def quarry(level):
    def fn():
        place("town", "rock-large", x=-0.12, z=-0.1, scale=0.36 + 0.03 * level)
        place("town", "rock-wide", x=0.25, z=-0.18, rot=40, scale=0.28)
        place("castle", "rocks-small", x=0.22, z=0.3, scale=0.7)
        if level >= 2:
            place("town", "cart", x=-0.25, z=0.3, rot=90, scale=0.3)
        if level >= 3:
            place("town", "poles", x=0.32, z=0.05, scale=0.45)
    return fn


def barracks(level):
    def fn():
        s = 0.8
        place("town", "wall-door", rot=-90, scale=s)
        place("town", "wall-window-stone", rot=0, scale=s)
        place("town", "wall-window-stone", rot=180, scale=s)
        place("town", "wall", rot=90, scale=s)
        y = 1.0 * s
        if level >= 2:
            place("town", "wall-wood-window-shutters", y=y, rot=-90, scale=s)
            place("town", "wall-wood", y=y, rot=0, scale=s)
            place("town", "wall-wood", y=y, rot=180, scale=s)
            place("town", "wall-wood", y=y, rot=90, scale=s)
            y += 1.0 * s
        place("town", "roof-gable", y=y, scale=s)
        place("castle", "flag-banner-long", x=0.43, y=0.0, z=0.43, rot=90, scale=0.42)
        place("castle", "flag-banner-long", x=-0.43, y=0.0, z=0.43, rot=90, scale=0.42)
        if level >= 3:
            place("castle", "flag", y=y + 0.45, rot=-90, scale=0.5)
            place("town", "stall-bench", x=0.0, z=0.62, scale=0.4)
    return fn


# ------------------------------------------------------------------ realm (3D world map) landmarks, ~1x1 footprint

def realm_camp():
    place("nature", "tent_detailedOpen", x=-0.2, z=-0.12, rot=20, scale=0.5)
    place("nature", "tent_smallClosed", x=0.28, z=-0.25, rot=-35, scale=0.45)
    place("nature", "tent_smallOpen", x=-0.3, z=0.3, rot=120, scale=0.4)
    place("nature", "campfire_logs", x=0.12, z=0.18, scale=0.55)
    place("nature", "log_stack", x=0.35, z=0.32, rot=70, scale=0.4)
    place("td", "weapon-ammo-arrow", x=0.0, z=0.38, rot=30, scale=0.5)


def realm_cache():
    place("town", "cart-high", x=-0.08, z=0.02, rot=30, scale=0.42)
    place("nature", "pot_large", x=0.3, z=-0.22, scale=0.55)
    place("nature", "pot_small", x=0.38, z=0.08, scale=0.55)
    place("nature", "log_stack", x=-0.32, z=-0.3, scale=0.38)


def realm_ruins():
    place("nature", "statue_columnDamaged", x=-0.28, z=-0.22, scale=0.55)
    place("nature", "statue_column", x=0.26, z=-0.28, scale=0.55)
    place("nature", "statue_columnDamaged", x=0.3, z=0.26, rot=90, scale=0.55)
    place("nature", "statue_block", x=-0.22, z=0.28, rot=30, scale=0.55)
    place("nature", "statue_obelisk", x=0.0, z=0.0, scale=0.5)
    place("castle", "rocks-small", x=0.05, z=0.4, scale=0.5)


def realm_rival_castle():
    # A darker, square-towered fortress so the rival never looks like your own keep.
    s = 0.6
    for x in (-0.7, 0.7):
        for z in (-0.7, 0.7):
            _, h = place("castle", "tower-square-base-color", x=x, z=z, scale=s)
            place("castle", "tower-square-top-color", x=x, y=h, z=z, scale=s)
    for t in (-0.25, 0.25):
        for (x, z) in ((t, 0.72), (t, -0.72), (0.72, t), (-0.72, t)):
            place("castle", "wall", x=x, z=z, scale=0.5)
    _, h1 = place("castle", "tower-hexagon-base", scale=0.85)
    _, h2 = place("castle", "tower-hexagon-mid", y=h1, scale=0.85)
    place("castle", "tower-hexagon-roof", y=h1 + h2, scale=0.85)
    place("castle", "gate", x=0.0, z=0.95, rot=90, scale=0.75)
    place("town", "banner-red", x=0.3, y=0.1, z=0.78, rot=-90, scale=0.6)
    place("town", "banner-red", x=-0.3, y=0.1, z=0.78, rot=-90, scale=0.6)


# ------------------------------------------------------------------ projectiles & props

def arrow():
    place("td", "weapon-ammo-arrow", rot=0)


def boulder():
    place("td", "weapon-ammo-boulder")


def selection():
    place("td", "selection-a")


# ------------------------------------------------------------------ characters (animated)

KEEP_ACTIONS = {"idle", "walk", "sprint", "die", "attack-melee-right", "holding-right", "holding-right-shoot", "holding-both-shoot"}


def character(name, base, right=None, left=None, right_rot=(0, 0, 0), left_rot=(0, 0, 0), right_off=(0, 0, 0), left_off=(0, 0, 0)):
    if ONLY and name not in ONLY:
        return
    c.reset()
    objs = c.imp("chars", base)
    arm = next(o for o in objs if o.type == 'ARMATURE')

    def attach(item, bone, rot, off):
        wobjs = c.imp("chars", item)
        root = next(o for o in wobjs if o.parent is None)
        root.parent = arm
        root.parent_type = 'BONE'
        root.parent_bone = bone
        root.matrix_parent_inverse = Matrix.Identity(4)
        root.location = Vector(off)
        root.rotation_euler = tuple(math.radians(a) for a in rot)
        root.scale = (1, 1, 1)

    if right:
        attach(right, "arm-right", right_rot, right_off)
    if left:
        attach(left, "arm-left", left_rot, left_off)
    for a in list(bpy.data.actions):
        if a.name not in KEEP_ACTIONS:
            bpy.data.actions.remove(a)
    c.export_glb(os.path.join(OUT, name + ".glb"), animations=True)
    meta[name] = {**meta.get(name, {}), "animations": sorted(a.name for a in bpy.data.actions), "height": 1.0}
    print("BUILT", name)


# ------------------------------------------------------------------ main

def main():
    build_static("ground", ground)
    build_static("grove", grove)
    build_static("pines", pines)
    build_static("boulders", boulders)
    build_static("keep", keep)
    for lvl in (1, 2, 3):
        build_static("archer_%d" % lvl, tower_builder(ARCHER_STACKS[lvl], TOP_FLOOR))
        build_static("ballista_%d" % lvl, tower_builder(BALLISTA_STACKS[lvl], TOP_FLOOR))
        build_static("catapult_%d" % lvl, tower_builder(CATAPULT_STACKS[lvl], TOP_FLOOR))
        build_static("house_%d" % lvl, house(lvl))
        build_static("lumber_%d" % lvl, lumber(lvl))
        build_static("quarry_%d" % lvl, quarry(lvl))
        build_static("barracks_%d" % lvl, barracks(lvl))
    build_static("ballista_head", weapon("weapon-ballista"))
    build_static("catapult_head", weapon("weapon-catapult"))
    build_static("wall_block", wall_block)
    build_static("wall_post", wall_post)
    build_static("arrow", arrow)
    build_static("boulder", boulder)
    build_static("selection", selection)
    build_static("realm_camp", realm_camp)
    build_static("realm_cache", realm_cache)
    build_static("realm_ruins", realm_ruins)
    build_static("realm_rival_castle", realm_rival_castle)
    character("raider", "character-raider", right="weapon-sword")
    character("brute", "character-male-b", right="weapon-sword", left="shield-round")
    character("outrider", "character-male-g", right="weapon-spear")
    character("warlord", "character-knight", right="weapon-sword", left="shield-rectangle")
    character("archer_crew", "character-soldier", left="weapon-bow")
    character("swordsman", "character-soldier", right="weapon-sword", left="shield-round")
    character("rival", "character-male-f", right="weapon-sword", left="shield-rectangle")
    character("villager", "character-worker")
    character("villager_f", "character-female-b")
    with open(META_PATH, "w") as f:
        json.dump(meta, f, indent=1, sort_keys=True)
    print("META", META_PATH)


main()
