"""Renders a labeled lineup of all Mini Characters and prints armature bone names.

blender --background --factory-startup --python tools/blender/probe_characters.py -- <out.png>
"""
import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(__file__))
import common as c  # noqa: E402

NAMES = [
    "character-raider", "character-knight", "character-soldier", "character-worker", "character-zombie",
    "character-male-a", "character-male-b", "character-male-c", "character-male-d", "character-male-e",
    "character-male-f", "character-male-g", "character-female-a", "character-female-b", "character-robot",
]

out = sys.argv[sys.argv.index("--") + 1]
c.reset()
label_mat = bpy.data.materials.new("Label")
label_mat.diffuse_color = (0.05, 0.05, 0.08, 1.0)
for i, n in enumerate(NAMES):
    x = (i % 5) * 1.4
    z = (i // 5) * 1.8
    objs = c.imp("chars", n, at=(x, 0, z))
    if i == 0:
        arm = next(o for o in objs if o.type == 'ARMATURE')
        print("BONES " + ", ".join(b.name for b in arm.data.bones))
        lo, hi = c.bounds(objs)
        print("HEIGHT %.3f (min z %.3f)" % (hi.z - lo.z, lo.z))
    bpy.ops.object.text_add(location=c.gl(x, -0.32, z + 0.3))
    t = bpy.context.object
    t.data.body = n.replace("character-", "")
    t.data.size = 0.2
    t.data.align_x = 'CENTER'
    t.rotation_euler = (1.5708, 0, 0)
    t.data.materials.append(label_mat)

c.setup_render(res=1100)
c.frame_camera(margin=0.8, yaw=0.0, pitch=12.0)
c.render(out)
print("RENDERED " + out)
