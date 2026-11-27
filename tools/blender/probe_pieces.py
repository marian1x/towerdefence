"""Lays out pieces in a grid (row-major, 6 per row) and renders a 3/4 view; prints each piece's size.

blender --background --factory-startup --python tools/blender/probe_pieces.py -- <out.png> pack:name [pack:name ...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import common as c  # noqa: E402

args = sys.argv[sys.argv.index("--") + 1:]
out, items = args[0], args[1:]
c.reset()
for i, item in enumerate(items):
    pack, name = item.split(":")
    objs = c.imp(pack, name, at=((i % 6) * 1.7, 0, (i // 6) * 1.7))
    lo, hi = c.bounds(objs)
    s = hi - lo
    # Blender (x, y, z) -> report as glTF-style w (x), h (up), d (z)
    print("PIECE %2d %-28s w=%.2f h=%.2f d=%.2f  x[%.2f,%.2f] up[%.2f,%.2f] z[%.2f,%.2f]" % (
        i, name, s.x, s.z, s.y, lo.x - (i % 6) * 1.7, hi.x - (i % 6) * 1.7, lo.z, hi.z,
        -hi.y - (i // 6) * 1.7, -lo.y - (i // 6) * 1.7))
c.setup_render(res=1000)
c.frame_camera(margin=0.95, yaw=25.0, pitch=38.0)
c.render(out)
print("RENDERED " + out)
