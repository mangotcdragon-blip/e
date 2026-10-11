// Analytic test scene for the offline preview: axis aligned boxes + a water pool.
// World coordinates match Minecraft (sea level 63).

struct Box { vec3 mn; vec3 mx; vec3 albedo; int kind; };
// kind: 0 solid, 1 foliage, 2 metal, 3 emissive, 4 polished

const int BOX_COUNT = 16;
Box boxes[BOX_COUNT] = Box[BOX_COUNT](
    // ground around the pool (pool spans x 4..20, z -30..-8)
    Box(vec3(-400.0, 0.0, -400.0), vec3(4.0, 63.0, 400.0),     vec3(0.36, 0.55, 0.22), 0),
    Box(vec3(20.0, 0.0, -400.0),   vec3(400.0, 63.0, 400.0),   vec3(0.36, 0.55, 0.22), 0),
    Box(vec3(4.0, 0.0, -8.0),      vec3(20.0, 63.0, 400.0),    vec3(0.36, 0.55, 0.22), 0),
    Box(vec3(4.0, 0.0, -400.0),    vec3(20.0, 63.0, -30.0),    vec3(0.36, 0.55, 0.22), 0),
    Box(vec3(4.0, 0.0, -30.0),     vec3(20.0, 58.0, -8.0),     vec3(0.78, 0.72, 0.52), 0), // pool floor (sand)
    // stone house with a roof overhang
    Box(vec3(-10.0, 63.0, -24.0),  vec3(-2.0, 69.0, -16.0),    vec3(0.52, 0.52, 0.52), 0),
    Box(vec3(-11.0, 69.0, -25.0),  vec3(-1.0, 70.0, -15.0),    vec3(0.45, 0.30, 0.20), 0),
    // tree
    Box(vec3(8.0, 63.0, -4.0),     vec3(9.0, 68.0, -3.0),      vec3(0.40, 0.29, 0.18), 0),
    Box(vec3(5.5, 66.0, -6.5),     vec3(11.5, 70.0, -0.5),     vec3(0.25, 0.48, 0.14), 1),
    // iron block, polished block, glowstone
    Box(vec3(-1.0, 63.0, -7.0),    vec3(0.0, 64.0, -6.0),      vec3(0.85, 0.85, 0.85), 2),
    Box(vec3(1.0, 63.0, -7.0),     vec3(2.0, 64.0, -6.0),      vec3(0.80, 0.78, 0.74), 4),
    Box(vec3(-4.0, 63.0, -9.0),    vec3(-3.0, 64.0, -8.0),     vec3(0.95, 0.80, 0.45), 3),
    // pillars and distant hills
    Box(vec3(-20.0, 63.0, -40.0),  vec3(-18.0, 80.0, -38.0),   vec3(0.60, 0.58, 0.55), 0),
    Box(vec3(-120.0, 63.0, -260.0), vec3(40.0, 110.0, -200.0), vec3(0.40, 0.45, 0.35), 0),
    Box(vec3(60.0, 63.0, -220.0),  vec3(160.0, 95.0, -160.0),  vec3(0.42, 0.47, 0.33), 0),
    Box(vec3(-60.0, 63.0, -90.0),  vec3(-40.0, 72.0, -70.0),   vec3(0.55, 0.50, 0.42), 0)
);

const vec3 poolMin = vec3(4.0, 58.0, -30.0);
const vec3 poolMax = vec3(20.0, 62.9, -8.0);
const vec3 torchPos = vec3(-3.5, 63.5, -8.5);

bool rayBox(vec3 ro, vec3 rd, vec3 mn, vec3 mx, out float tNear, out vec3 n) {
    vec3 inv = 1.0 / rd;
    vec3 t0 = (mn - ro) * inv;
    vec3 t1 = (mx - ro) * inv;
    vec3 tmin = min(t0, t1);
    vec3 tmax = max(t0, t1);
    tNear = max(max(tmin.x, tmin.y), tmin.z);
    float tFar = min(min(tmax.x, tmax.y), tmax.z);
    if (tFar < max(tNear, 0.0)) return false;
    if (tNear == tmin.x) n = vec3(-sign(rd.x), 0.0, 0.0);
    else if (tNear == tmin.y) n = vec3(0.0, -sign(rd.y), 0.0);
    else n = vec3(0.0, 0.0, -sign(rd.z));
    return tNear > 0.0;
}

// Returns hit distance (or -1), normal, box index.
float traceScene(vec3 ro, vec3 rd, out vec3 n, out int idx) {
    float best = 1e9;
    idx = -1;
    for (int i = 0; i < BOX_COUNT; i++) {
        float t; vec3 nn;
        if (rayBox(ro, rd, boxes[i].mn, boxes[i].mx, t, nn) && t < best) { best = t; n = nn; idx = i; }
    }
    return idx < 0 ? -1.0 : best;
}

float traceWater(vec3 ro, vec3 rd) {
    if (abs(rd.y) < 1e-5) return -1.0;
    float t = (poolMax.y - ro.y) / rd.y;
    if (t <= 0.0) return -1.0;
    vec3 p = ro + rd * t;
    if (p.x < poolMin.x || p.x > poolMax.x || p.z < poolMin.z || p.z > poolMax.z) return -1.0;
    return t;
}
