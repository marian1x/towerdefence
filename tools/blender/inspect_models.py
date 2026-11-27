"""Prints size, mesh/material counts and animation names for glTF/GLB files.

Usage:
  blender --background --factory-startup --python tools/blender/inspect_models.py -- <file.glb> [<file.glb> ...]
"""
import json
import sys

import bpy
from mathutils import Vector


def clear_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def world_bounds(objects):
    lo = Vector((1e9, 1e9, 1e9))
    hi = Vector((-1e9, -1e9, -1e9))
    found = False
    for ob in objects:
        if ob.type != 'MESH':
            continue
        found = True
        for corner in ob.bound_box:
            w = ob.matrix_world @ Vector(corner)
            lo = Vector(map(min, lo, w))
            hi = Vector(map(max, hi, w))
    return (lo, hi) if found else (Vector(), Vector())


def main(paths):
    for path in paths:
        clear_scene()
        bpy.ops.import_scene.gltf(filepath=path)
        objs = list(bpy.context.scene.objects)
        lo, hi = world_bounds(objs)
        size = hi - lo
        info = {
            "file": path.replace("\\", "/").split("/")[-1],
            # Blender is Z-up; glTF Y-up. Report in glTF terms: w (x), h (y-up), d (z).
            "w": round(size.x, 3), "h": round(size.z, 3), "d": round(size.y, 3),
            "minUp": round(lo.z, 3),
            "meshes": sum(1 for o in objs if o.type == 'MESH'),
            "tris": sum(len(o.data.polygons) for o in objs if o.type == 'MESH'),
            "materials": sorted({s.material.name for o in objs if o.type == 'MESH' for s in o.material_slots if s.material}),
            "images": sorted({i.name for i in bpy.data.images}),
            "armatures": sum(1 for o in objs if o.type == 'ARMATURE'),
            "actions": [a.name for a in bpy.data.actions],
        }
        print("MODEL " + json.dumps(info))


if __name__ == "__main__":
    argv = sys.argv
    main(argv[argv.index("--") + 1:] if "--" in argv else [])
