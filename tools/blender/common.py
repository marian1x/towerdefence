"""Shared helpers for the Blender asset pipeline (run inside Blender's Python).

Coordinates: helpers take game/glTF-style positions (x right, y up, z towards the camera)
and convert to Blender's Z-up space: (x, y, z)_gltf -> (x, -z, y)_blender.
"""
import math
import os

import bpy
from mathutils import Vector

SRC = os.environ.get("AOS_ASSETS_SRC", r"C:\Users\maria\Tools\assets-src")

PACKS = {
    "td": os.path.join(SRC, "tower-defense-kit", "Models", "GLB format"),
    "castle": os.path.join(SRC, "castle-kit", "Models", "GLB format"),
    "town": os.path.join(SRC, "fantasy-town-kit", "Models", "GLB format"),
    "chars": os.path.join(SRC, "mini-characters", "Models", "GLB format"),
    "nature": os.path.join(SRC, "nature-kit", "Models", "GLTF format"),
}


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def gl(x, y, z):
    """glTF/game coordinates -> Blender coordinates."""
    return Vector((x, -z, y))


def imp(pack, name, at=(0.0, 0.0, 0.0), rot_y=0.0, scale=1.0):
    """Imports a model and returns its top-level objects, placed at a glTF-space position.

    rot_y is a rotation (degrees) around the vertical axis.
    """
    path = os.path.join(PACKS[pack], name + ".glb")
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=path)
    new = [o for o in bpy.data.objects if o not in before]
    roots = [o for o in new if o.parent is None or o.parent not in new]
    for r in roots:
        r.location = gl(*at)
        r.rotation_mode = 'XYZ'
        r.rotation_euler = (0.0, 0.0, math.radians(rot_y))
        r.scale = (scale, scale, scale)
    bpy.context.view_layer.update()
    return new


def meshes(objs=None):
    objs = objs if objs is not None else bpy.data.objects
    return [o for o in objs if o.type == 'MESH']


def join_static(name):
    """Applies transforms and joins every mesh in the scene into one object (one draw call)."""
    ms = meshes()
    bpy.ops.object.select_all(action='DESELECT')
    for o in ms:
        o.select_set(True)
    bpy.context.view_layer.objects.active = ms[0]
    # Unparent while keeping world transforms, then apply them.
    bpy.ops.object.parent_clear(type='CLEAR_KEEP_TRANSFORM')
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    bpy.ops.object.join()
    obj = bpy.context.view_layer.objects.active
    obj.name = name
    # Remove leftover empties.
    for o in list(bpy.data.objects):
        if o.type == 'EMPTY':
            bpy.data.objects.remove(o, do_unlink=True)
    return obj


def bounds(objs=None):
    bpy.context.view_layer.update()
    dg = bpy.context.evaluated_depsgraph_get()
    lo = Vector((1e9, 1e9, 1e9))
    hi = Vector((-1e9, -1e9, -1e9))
    for o in meshes(objs):
        ev = o.evaluated_get(dg)
        mesh = ev.to_mesh()
        for v in mesh.vertices:
            w = ev.matrix_world @ v.co
            lo = Vector(map(min, lo, w))
            hi = Vector(map(max, hi, w))
        ev.to_mesh_clear()
    return lo, hi


def export_glb(path, animations=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=path,
        export_format='GLB',
        export_yup=True,
        export_apply=True,
        export_texcoords=True,
        export_normals=True,
        export_materials='EXPORT',
        export_cameras=False,
        export_lights=False,
        export_skins=animations,
        export_animations=animations,
        export_morph=False,
    )


def setup_render(res=512, transparent=True):
    scene = bpy.context.scene
    engines = [e.identifier for e in bpy.types.RenderSettings.bl_rna.properties['engine'].enum_items]
    scene.render.engine = 'BLENDER_EEVEE' if 'BLENDER_EEVEE' in engines else engines[0]
    scene.render.resolution_x = res
    scene.render.resolution_y = res
    scene.render.film_transparent = transparent
    scene.render.image_settings.file_format = 'PNG'
    scene.render.image_settings.color_mode = 'RGBA'
    scene.view_settings.view_transform = 'Standard'
    world = bpy.data.worlds.new("World") if not bpy.data.worlds else bpy.data.worlds[0]
    scene.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    if bg:
        bg.inputs[0].default_value = (0.9, 0.93, 1.0, 1.0)
        bg.inputs[1].default_value = 0.9
    sun_data = bpy.data.lights.new("Sun", 'SUN')
    sun_data.energy = 3.2
    sun = bpy.data.objects.new("Sun", sun_data)
    sun.rotation_euler = (math.radians(50), math.radians(10), math.radians(35))
    scene.collection.objects.link(sun)


def frame_camera(objs=None, margin=1.15, yaw=45.0, pitch=35.0, box=None):
    """Orthographic 3/4 camera framing the given objects (or an explicit (center, size) box)."""
    if box is not None:
        center, size = Vector(box[0]), box[1]
    else:
        lo, hi = bounds(objs)
        center = (lo + hi) / 2
        size = max((hi - lo).length, 0.01)
    cam_data = bpy.data.cameras.new("Cam")
    cam_data.type = 'ORTHO'
    cam_data.ortho_scale = size * margin
    cam = bpy.data.objects.new("Cam", cam_data)
    bpy.context.scene.collection.objects.link(cam)
    yaw_r = math.radians(yaw)
    pitch_r = math.radians(pitch)
    d = size * 3
    cam.location = center + Vector((math.sin(yaw_r) * math.cos(pitch_r) * d, -math.cos(yaw_r) * math.cos(pitch_r) * d, math.sin(pitch_r) * d))
    direction = center - cam.location
    cam.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()
    bpy.context.scene.camera = cam
    return cam


def render(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
