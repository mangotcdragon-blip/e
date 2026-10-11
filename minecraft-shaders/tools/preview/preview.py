#!/usr/bin/env python3
"""
Offline preview of the Solstice deferred/composite pipeline.

Renders an analytic test scene (boxes + a water pool) into the same G-buffer layout
the gbuffers programs produce, ray traces a matching distorted shadow map, then runs
the pack's real deferred, deferred1, composite, composite1 (TAA + exposure),
composite2/3 (bloom) and final passes on a headless OpenGL context (Mesa llvmpipe).

usage: python3 tools/preview/preview.py OUT_DIR [preset ...] [--set=NAME=VALUE ...] [--world=nether|end]
presets: day, sunset, night, water, rain, underwater
"""
import math, os, re, sys
import numpy as np
import moderngl
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
SHADERS = os.path.join(TOOLS, "..", "Solstice", "shaders")
W, H = 960, 540

LOADER = """#define MC_VERSION 12101
#define MC_RENDER_STAGE_SUN 4
#define MC_RENDER_STAGE_STARS 6
"""


OVERRIDES = {}  # NAME -> value, applied to lib/settings.glsl (--set NAME=VALUE)
WORLD = {"name": "WORLD_OVERWORLD"}  # --world=nether|end


def expand(path, seen=None):
    out = []
    text = open(path).read()
    if os.path.basename(path) == "settings.glsl":
        for name, value in OVERRIDES.items():
            text = re.sub(r"(#define %s )\S+" % name, r"\g<1>" + value, text)
    for line in text.split("\n"):
        m = re.match(r'\s*#include\s+"([^"]+)"', line)
        if m:
            inc = m.group(1)
            base = os.path.join(TOOLS, "..") if inc.startswith("/tools/") else SHADERS
            out.append(expand(os.path.normpath(os.path.join(base, inc.lstrip("/")))))
        else:
            out.append(line)
    return "\n".join(out)


def settings_value(name):
    text = open(os.path.join(SHADERS, "lib", "settings.glsl")).read()
    m = re.search(r"const\s+\w+\s+%s\s*=\s*([-\d.]+)" % name, text)
    return float(m.group(1))


VS = """#version 330 core
in vec2 in_pos;
out vec2 texcoord;
void main() { texcoord = in_pos * 0.5 + 0.5; gl_Position = vec4(in_pos, 0.0, 1.0); }
"""


def frag(path):
    return "#version 330 core\n#define %s\n#define FRAGMENT_SHADER\n" % WORLD["name"] + LOADER + expand(path)


def perspective(fovy, aspect, n, f):
    t = 1.0 / math.tan(math.radians(fovy) / 2)
    return np.array([[t / aspect, 0, 0, 0], [0, t, 0, 0],
                     [0, 0, (f + n) / (n - f), 2 * f * n / (n - f)], [0, 0, -1, 0]], dtype=np.float64)


def ortho(l, r, b, t, n, f):
    return np.array([[2 / (r - l), 0, 0, -(r + l) / (r - l)], [0, 2 / (t - b), 0, -(t + b) / (t - b)],
                     [0, 0, -2 / (f - n), -(f + n) / (f - n)], [0, 0, 0, 1]], dtype=np.float64)


def look_rotation(forward):
    """Rotation matrix (world -> view) for a camera looking along `forward`."""
    f = forward / np.linalg.norm(forward)
    up0 = np.array([0, 1, 0.0]) if abs(f[1]) < 0.99 else np.array([0, 0, 1.0])
    r = np.cross(f, up0); r /= np.linalg.norm(r)
    u = np.cross(r, f)
    m = np.eye(4)
    m[0, :3], m[1, :3], m[2, :3] = r, u, -f
    return m


PRESETS = {
    #            camera pos         yaw   pitch  sun elev  sun azimuth  extra
    "day":       ((2.0, 68.5, 6.0),   12, -10,    38,      200,  {}),
    "sunset":    ((2.0, 68.5, 6.0),    0,  -4,     5,      185,  {}),
    "night":     ((2.0, 68.5, 6.0),   12, -6,    -30,      200,  {"moonPhase": 0}),
    "water":     ((12.0, 66.0, 0.0),  0, -32,    45,      150,  {}),
    "rain":      ((2.0, 68.5, 6.0),   12, -10,    38,      200,  {"rainStrength": 1.0, "wetness": 1.0}),
    "underwater": ((12.0, 60.0, -12.0), 0,  10,   50,      160,  {"isEyeInWater": 1}),
}


class Pipeline:
    def __init__(self):
        self.ctx = moderngl.create_standalone_context(backend="egl", require=330)
        self.quad = self.ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], dtype="f4"))
        self.progs = {}
        self.shadow_res = int(settings_value("shadowMapResolution"))
        self.shadow_distance = settings_value("shadowDistance")
        noise = np.array(Image.open(os.path.join(SHADERS, "tex", "noise.png")).convert("RGBA"))
        self.noise = self.ctx.texture((256, 256), 4, noise.tobytes())
        self.white = self.ctx.texture((1, 1), 4, bytes([255, 255, 255, 0]))

    def prog(self, path):
        if path not in self.progs:
            p = self.ctx.program(vertex_shader=VS, fragment_shader=frag(path))
            self.progs[path] = (p, self.ctx.vertex_array(p, [(self.quad, "2f", "in_pos")]))
        return self.progs[path]

    def tex(self, size, comps=4, dtype="f4"):
        t = self.ctx.texture(size, comps, dtype=dtype)
        t.filter = (moderngl.LINEAR, moderngl.LINEAR)
        t.repeat_x = t.repeat_y = False
        return t

    def run(self, path, uniforms, samplers, outputs, size=(W, H)):
        p, vao = self.prog(path)
        for name, value in uniforms.items():
            if name in p:
                p[name].value = value
        unit = 0
        for name, t in samplers.items():
            if name in p:
                t.use(unit)
                p[name].value = unit
                unit += 1
        fbo = self.ctx.framebuffer(color_attachments=outputs)
        fbo.use()
        self.ctx.viewport = (0, 0) + size
        vao.render(moderngl.TRIANGLE_STRIP)
        fbo.release()

    def uniforms(self, preset, frame):
        cam, yaw, pitch, sun_el, sun_az, extra = PRESETS[preset]
        cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
        cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
        forward = np.array([-sy * cp, sp, -cy * cp])
        mv = look_rotation(forward)
        proj = perspective(70.0, W / H, 0.05, 1024.0)
        el, az = math.radians(sun_el), math.radians(sun_az)
        sun = np.array([math.cos(el) * math.sin(az), math.sin(el), math.cos(el) * math.cos(az)])
        light = sun if sun[1] > 0 else -sun
        smv = np.eye(4); smv[2, 3] = -100.0
        smv = smv @ look_rotation(-light)
        sproj = ortho(-self.shadow_distance, self.shadow_distance, -self.shadow_distance, self.shadow_distance, 0.05, 256.0)

        def m(a): return tuple(np.asarray(a, dtype=np.float64).T.flatten())
        def v(world): return tuple((mv[:3, :3] @ world) * 100.0)
        u = {
            "gbufferModelView": m(mv), "gbufferModelViewInverse": m(np.linalg.inv(mv)),
            "gbufferProjection": m(proj), "gbufferProjectionInverse": m(np.linalg.inv(proj)),
            "gbufferPreviousModelView": m(mv), "gbufferPreviousProjection": m(proj),
            "shadowModelView": m(smv), "shadowModelViewInverse": m(np.linalg.inv(smv)),
            "shadowProjection": m(sproj), "shadowProjectionInverse": m(np.linalg.inv(sproj)),
            "cameraPosition": cam, "previousCameraPosition": cam,
            "sunPosition": v(sun), "moonPosition": v(-sun), "shadowLightPosition": v(light),
            "fogColor": (0.7, 0.8, 1.0), "skyColor": (0.5, 0.7, 1.0),
            "viewWidth": float(W), "viewHeight": float(H), "aspectRatio": W / H,
            "near": 0.05, "far": 256.0, "frameTime": 1.0, "frameTimeCounter": 100.0 + frame / 30.0,
            "rainStrength": 0.0, "wetness": 0.0, "nightVision": 0.0, "blindness": 0.0, "darknessFactor": 0.0,
            "worldTime": 1000, "frameCounter": frame, "isEyeInWater": 0, "moonPhase": 0,
            "heldBlockLightValue": 0, "heldBlockLightValue2": 0, "eyeBrightnessSmooth": (0, 240),
        }
        if WORLD["name"] == "WORLD_NETHER":
            u["fogColor"] = (0.33, 0.08, 0.05)
            u["eyeBrightnessSmooth"] = (0, 0)
        if extra.get("isEyeInWater") == 1:
            u["eyeBrightnessSmooth"] = (0, 200)
        u.update(extra)
        return u

    def render(self, preset, frames=10):
        sd = os.path.join(SHADERS, "program")
        pv = HERE
        shadow = self.tex((self.shadow_res, self.shadow_res), 1)
        shadow.filter = (moderngl.NEAREST, moderngl.NEAREST)
        history = self.tex((W, H), 4, "f2")
        out = None
        for frame in range(frames):
            u = self.uniforms(preset, frame)
            if frame == 0:
                self.run(os.path.join(pv, "shadow_scene.fsh"), u, {}, [shadow], (self.shadow_res, self.shadow_res))
            g = [self.tex((W, H), 4, "f4") for _ in range(5)] + [self.tex((W, H), 1), self.tex((W, H), 1)]
            self.run(os.path.join(pv, "gbuffer_scene.fsh"), u, {"noisetex": self.noise}, g)
            c0, c1, c2, c3, c4, d0, d1 = g
            common = {"depthtex0": d0, "depthtex1": d1, "noisetex": self.noise, "shadowtex0": shadow,
                      "shadowtex1": shadow, "shadowcolor0": self.white}

            c7 = self.tex((W, H), 4, "f2")
            self.run(os.path.join(sd, "deferred.fsh"), u, dict(common), [c7])
            lit = self.tex((W, H), 4, "f2")
            self.run(os.path.join(sd, "deferred1.fsh"), u, dict(common, colortex0=c0, colortex1=c1, colortex2=c2, colortex7=c7), [lit])
            comp = self.tex((W, H), 4, "f2")
            self.run(os.path.join(sd, "composite.fsh"), u, dict(common, colortex0=lit, colortex1=c1, colortex2=c2,
                                                                  colortex3=c3, colortex4=c4, colortex7=c7), [comp])
            comp.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
            comp.build_mipmaps()
            taa = self.tex((W, H), 4, "f2")
            new_history = self.tex((W, H), 4, "f2")
            self.run(os.path.join(sd, "composite1.fsh"), u, dict(common, colortex0=comp, colortex5=history), [taa, new_history])
            history = new_history
            taa.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
            taa.build_mipmaps()
            b1 = self.tex((W, H), 4, "f2")
            self.run(os.path.join(sd, "composite2.fsh"), u, dict(common, colortex0=taa), [b1])
            b2 = self.tex((W, H), 4, "f2")
            self.run(os.path.join(sd, "composite3.fsh"), u, dict(common, colortex6=b1), [b2])
            out = self.tex((W, H), 4, "f1")
            self.run(os.path.join(sd, "final.fsh"), u, dict(common, colortex0=taa, colortex5=history, colortex6=b2), [out])

            exposure = np.frombuffer(history.read(), dtype=np.float16).reshape(H, W, 4)[0, 0, 3]
            hdr = np.frombuffer(taa.read(), dtype=np.float16).reshape(H, W, 4)
        img = np.frombuffer(out.read(), dtype=np.uint8).reshape(H, W, 4)[::-1, :, :3]
        stats = {
            "exposure": float(exposure),
            "nan": int(np.isnan(hdr).sum()), "inf": int(np.isinf(hdr).sum()),
            "hdr_mean": float(np.nanmean(hdr[..., :3])), "hdr_max": float(np.nanmax(hdr[..., :3])),
            "ldr_mean": float(img.mean()),
        }
        return Image.fromarray(img), stats


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    for a in sys.argv[1:]:
        if a.startswith("--world="):
            WORLD["name"] = {"nether": "WORLD_NETHER", "end": "WORLD_END"}[a.split("=", 1)[1]]
        if a.startswith("--set="):
            name, value = a[len("--set="):].split("=", 1)
            OVERRIDES[name] = value
    out_dir = args[0]
    presets = args[1:] or list(PRESETS)
    os.makedirs(out_dir, exist_ok=True)
    pipe = Pipeline()
    for p in presets:
        img, stats = pipe.render(p)
        suffix = "" if WORLD["name"] == "WORLD_OVERWORLD" else "_" + WORLD["name"][6:].lower()
        path = os.path.join(out_dir, f"{p}{suffix}.png")
        img.save(path)
        print(p, path, {k: round(v, 3) if isinstance(v, float) else v for k, v in stats.items()})


if __name__ == "__main__":
    main()
