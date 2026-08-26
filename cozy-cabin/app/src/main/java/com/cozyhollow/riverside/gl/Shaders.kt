package com.cozyhollow.riverside.gl

/**
 * GLSL ES 1.00 shaders.
 *
 * The world shader does four things worth knowing about.
 *
 * It bends the ground away from you in *view* space, so the hollow curves off
 * in every direction like a small planet rather than only sideways.
 *
 * It lights with a low winter sun plus a sky/snow hemisphere fill. In snow the
 * bounce off the ground is enormous — far stronger than off grass — so the
 * shaded side of anything standing in a field is bright blue rather than dark,
 * which is most of why snow reads as snow.
 *
 * It then adds up to [MAX_LIGHTS] warm point lights per fragment. Every window,
 * lantern, brazier and fire in the game is one of these, and they are the only
 * orange in a frame that is otherwise entirely blue. Doing them per fragment
 * rather than per vertex is the difference between a soft pool of light on the
 * snow and a blocky stain across four triangles.
 *
 * And it glitters: a sparse, view-dependent twinkle on up-facing bright
 * surfaces, which is what a snowfield does in low sun.
 */
object Shaders {

    /** How many warm lights the world shader handles at once. */
    const val MAX_LIGHTS = 4

    const val WORLD_VS = """
attribute vec3 aPos;
attribute vec3 aNor;
attribute vec2 aUv;
attribute vec4 aCol;

uniform mat4 uProj;
uniform mat4 uView;
uniform mat4 uModel;
uniform float uCurve;
uniform float uTime;
uniform float uWind;
uniform vec2 uUvScale;

uniform vec3 uSunDir;
uniform vec3 uSunCol;
uniform vec3 uSkyFill;
uniform vec3 uGroundFill;
uniform vec2 uFog;

varying vec2 vUv;
varying vec3 vLight;
varying vec3 vTint;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec2 vFogDist;

void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);

    // the wind weight rides in the alpha of the vertex colour: 0 for a roof
    // beam, up near 1 for the tip of a dead reed poking through the crust
    float sway = aCol.a * uWind;
    if (sway > 0.001) {
        float w = sin(uTime * 1.9 + wp.x * 0.75 + wp.z * 0.5)
                + sin(uTime * 3.1 + wp.x * 1.7) * 0.45;
        wp.x += w * sway * 0.6;
        wp.z += w * sway * 0.22;
        wp.y -= abs(w) * sway * 0.10;
    }

    mat3 nm = mat3(uModel[0].xyz, uModel[1].xyz, uModel[2].xyz);
    vec3 n = normalize(nm * aNor);

    float lam = max(dot(n, uSunDir), 0.0);
    // a soft wrap on the sun term: snow scatters light round an edge instead
    // of ending it in a hard terminator
    float wrap = max((dot(n, uSunDir) + 0.35) / 1.35, 0.0);
    float up = n.y * 0.5 + 0.5;
    vec3 fill = mix(uGroundFill, uSkyFill, up);
    vLight = uSunCol * (lam * 0.62 + wrap * 0.30 + 0.08) + fill;

    vec4 vp = uView * wp;
    float dist = length(vp.xyz);
    // the horizon drops away with distance: a small, cold planet
    vp.y -= uCurve * vp.z * vp.z;

    vFogDist = vec2(clamp((dist - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0), dist);
    vUv = aUv * uUvScale;
    vTint = aCol.rgb;
    vWorld = wp.xyz;
    vNormal = n;
    gl_Position = uProj * vp;
}
"""

    const val WORLD_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform vec4 uColor;
uniform vec3 uFogCol;
uniform float uEmissive;
uniform float uCut;
uniform float uNear;
uniform float uSparkle;
uniform vec3 uEye;

// Packed to stay inside the ES 2.0 floor of 16 fragment uniform vectors:
// xyz is the position / colour, w is the reach in metres / how hard it burns.
uniform vec4 uLightPos[4];
uniform vec4 uLightCol[4];

varying vec2 vUv;
varying vec3 vLight;
varying vec3 vTint;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec2 vFogDist;

float hash13(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

void main() {
    // Anything closer than uNear stipples away, so a pine the camera has
    // walked into cannot fill the screen. The threshold is interleaved
    // gradient noise rather than white noise: it spreads evenly instead of
    // clumping, so the edge reads as a fine screen rather than as dirt on the
    // lens.
    float dist = vFogDist.y;
    if (uNear > 0.0 && dist < uNear) {
        float f = clamp((dist - uNear * 0.85) / (uNear * 0.15), 0.0, 1.0);
        float th = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        if (f <= th) discard;
    }
    vec4 t = texture2D(uTex, vUv);
    if (t.a < uCut) discard;
    vec3 base = t.rgb * uColor.rgb * vTint;

    vec3 n = normalize(vNormal);
    vec3 lit = vLight;

    // ---- the warm lights ----
    for (int i = 0; i < 4; i++) {
        vec3 d = uLightPos[i].xyz - vWorld;
        float dd = length(d);
        float radius = uLightPos[i].w;
        if (radius > 0.01) {
            float f = clamp(1.0 - dd / radius, 0.0, 1.0);
            // smoothed falloff, so the pool has a soft edge instead of a
            // visible circle drawn on the snow
            f = f * f * (3.0 - 2.0 * f);
            float facing = max(dot(n, d / max(dd, 0.001)), 0.0) * 0.72 + 0.28;
            lit += uLightCol[i].rgb * f * facing * uLightCol[i].w;
        }
    }

    vec3 c = mix(base * lit, base * 1.25, uEmissive);

    // ---- the glitter on the crust ----
    if (uSparkle > 0.001 && n.y > 0.55) {
        vec3 cell = floor(vWorld * 26.0);
        float g = hash13(cell);
        vec3 v = normalize(uEye - vWorld);
        float ang = hash13(cell + 3.7) * 6.2831;
        // each grain only flares when you happen to catch it square on
        float aim = max(dot(v, normalize(vec3(cos(ang) * 0.55, 0.83, sin(ang) * 0.55))), 0.0);
        float flash = pow(aim, 60.0) * step(0.982, g);
        c += vec3(0.85, 0.92, 1.0) * flash * uSparkle * 2.4;
    }

    c = mix(c, uFogCol, vFogDist.x);
    gl_FragColor = vec4(c, t.a * uColor.a);
}
"""

    /**
     * The ice.
     *
     * Frozen water is not water with the waves turned off. It is a hard sheet
     * with the dark of the pond underneath it, a pale bloom of trapped air and
     * old snow drifted across the top, and a very strong reflection at grazing
     * angles — which is why a frozen pond at dusk is a mirror of the sky in one
     * direction and nearly black in the other.
     */
    const val ICE_VS = """
attribute vec3 aPos;
attribute vec3 aNor;
attribute vec2 aUv;
attribute vec4 aCol;

uniform mat4 uProj;
uniform mat4 uView;
uniform float uCurve;
uniform highp float uTime;
uniform vec3 uCamPos;
uniform vec2 uFog;

varying vec2 vUv;
varying vec3 vView;
varying vec3 vWorld;
varying float vDepth;
varying float vFog;

void main() {
    vec3 p = aPos;
    vView = normalize(uCamPos - p);
    vWorld = p;

    vec4 vp = uView * vec4(p, 1.0);
    float dist = length(vp.xyz);
    vp.y -= uCurve * vp.z * vp.z;
    vFog = clamp((dist - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    vUv = aUv;
    vDepth = aCol.r;
    gl_Position = uProj * vp;
}
"""

    const val ICE_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform vec3 uShallow;
uniform vec3 uDeep;
uniform vec3 uSkyCol;
uniform vec3 uSunCol;
uniform vec3 uSunDir;
uniform vec3 uFogCol;
uniform vec3 uSnowCol;
uniform highp float uTime;

// Packed to stay inside the ES 2.0 floor of 16 fragment uniform vectors:
// xyz is the position / colour, w is the reach in metres / how hard it burns.
uniform vec4 uLightPos[4];
uniform vec4 uLightCol[4];

varying vec2 vUv;
varying vec3 vView;
varying vec3 vWorld;
varying float vDepth;
varying float vFog;

void main() {
    vec3 n = vec3(0.0, 1.0, 0.0);
    vec3 v = normalize(vView);

    float d = clamp(vDepth, 0.0, 1.0);
    vec3 col = mix(uShallow, uDeep, d);

    // trapped air and old cracks, from the painted ice tile - static, because
    // ice does not move
    float cr = texture2D(uTex, vUv * 0.42).r;
    float cr2 = texture2D(uTex, vUv * 0.17 + vec2(3.1, 7.7)).r;
    col += (cr - 0.5) * 0.20;
    col = mix(col, vec3(0.86, 0.93, 0.98), clamp((cr2 - 0.62) * 2.2, 0.0, 1.0) * 0.35);

    // drifted snow lying on top, thickest where the ice meets the bank
    float snow = clamp((cr2 * 0.7 + cr * 0.3 - 0.34) * 1.9, 0.0, 1.0);
    snow = max(snow, smoothstep(0.30, 0.02, d));
    col = mix(col, uSnowCol, snow * 0.88);

    // the sky, laid flat across the surface where you look along it
    float fres = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 3.2);
    col = mix(col, uSkyCol, clamp(fres, 0.0, 1.0) * 0.62 * (1.0 - snow * 0.8));

    // a low hard glare where the sun is
    vec3 h = normalize(uSunDir + v);
    float spec = pow(max(dot(n, h), 0.0), 96.0);
    col += uSunCol * spec * 0.9 * (1.0 - snow * 0.6);

    // and the warm lights, which the ice throws back much harder than snow does
    for (int i = 0; i < 4; i++) {
        vec3 dl = uLightPos[i].xyz - vWorld;
        float dd = length(dl);
        float radius = uLightPos[i].w;
        if (radius > 0.01) {
            float f = clamp(1.0 - dd / radius, 0.0, 1.0);
            f = f * f * (3.0 - 2.0 * f);
            float gloss = 1.0 + (1.0 - snow) * 1.4;
            col += uLightCol[i].rgb * f * uLightCol[i].w * gloss;
        }
    }

    col = mix(col, uFogCol, vFog);
    gl_FragColor = vec4(col, 1.0);
}
"""

    /** Sky gradient, snow haze, stars, aurora, and the sun or moon. */
    const val SKY_VS = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val SKY_FS = """
precision mediump float;
varying vec2 vUv;
uniform vec3 uTop;
uniform vec3 uMid;
uniform vec3 uHorizon;
uniform float uStars;
uniform vec2 uSun;
uniform vec3 uSunCol;
uniform vec3 uSunGlow;
uniform float uSunSize;
uniform float uAspect;
uniform float uTime;
uniform float uHaze;
uniform float uAurora;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void main() {
    float t = vUv.y;
    vec3 col;
    if (t < 0.52) {
        col = mix(uHorizon, uMid, t / 0.52);
    } else {
        col = mix(uMid, uTop, (t - 0.52) / 0.48);
    }

    if (uStars > 0.01) {
        vec2 cell = floor(vUv * vec2(210.0 * uAspect, 120.0));
        float r = hash21(cell);
        if (r > 0.989) {
            float tw = 0.55 + 0.45 * hash21(cell + 3.7);
            float pulse = 0.7 + 0.3 * sin(uTime * 1.7 + r * 30.0);
            col += vec3(1.0, 0.98, 0.94) * uStars * tw * pulse * (1.0 - vUv.y * 0.3);
        }
    }

    // The northern lights, on clear nights. Two slow ribbons of banded noise,
    // green low and violet high, only ever in the upper half of the sky.
    if (uAurora > 0.01) {
        float band = smoothstep(0.46, 0.72, vUv.y) * (1.0 - smoothstep(0.86, 1.0, vUv.y));
        float a1 = vnoise(vec2(vUv.x * 3.2 + uTime * 0.021, vUv.y * 7.0 - uTime * 0.012));
        float a2 = vnoise(vec2(vUv.x * 6.1 - uTime * 0.014, vUv.y * 11.0 + 3.3));
        float curtain = pow(clamp(a1 * 0.68 + a2 * 0.42 - 0.30, 0.0, 1.0), 1.7);
        // vertical streaking, so it hangs rather than floats
        curtain *= 0.55 + 0.45 * vnoise(vec2(vUv.x * 26.0, 1.7));
        vec3 aur = mix(vec3(0.30, 0.95, 0.62), vec3(0.55, 0.42, 0.95), smoothstep(0.55, 0.9, vUv.y));
        col += aur * curtain * band * uAurora * 0.85;
    }

    vec2 d = vec2((vUv.x - uSun.x) * uAspect, vUv.y - uSun.y);
    float dist = length(d);
    col += uSunGlow * exp(-dist * 5.2) * 0.7;
    if (dist < uSunSize) col = uSunCol;

    // the snow deck: a heavy flat band of cloud sitting low over the ridge
    vec2 cp = vec2(vUv.x * 2.4 + uTime * 0.005, vUv.y * 4.4);
    float n = vnoise(cp) * 0.6 + vnoise(cp * 2.3 + 4.1) * 0.3;
    float band = smoothstep(0.24, 0.50, vUv.y) * (1.0 - smoothstep(0.60, 0.98, vUv.y));
    float cloud = smoothstep(0.46, 0.76, n) * band * uHaze;
    col = mix(col, mix(vec3(0.92, 0.95, 1.0), uSunGlow, 0.28), cloud * 0.8);

    gl_FragColor = vec4(col, 1.0);
}
"""

    const val BLIT_VS = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /**
     * Presenting the frame.
     *
     * A split grade: the shadows go blue, the highlights stay warm, which is
     * the whole colour story of the game in two lines of arithmetic. Then a
     * cheap four-tap bloom so every lit window and every flame bleeds a little
     * into the dark around it, and a vignette to close the frame in.
     */
    const val BLIT_FS = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec3 uGrade;
uniform vec3 uShadowTint;
uniform float uVignette;
uniform float uBloom;
uniform vec2 uTexel;

void main() {
    vec3 c = texture2D(uTex, vUv).rgb;

    if (uBloom > 0.001) {
        vec3 b = vec3(0.0);
        vec2 o = uTexel * 3.0;
        b += texture2D(uTex, vUv + vec2( o.x,  o.y)).rgb;
        b += texture2D(uTex, vUv + vec2(-o.x,  o.y)).rgb;
        b += texture2D(uTex, vUv + vec2( o.x, -o.y)).rgb;
        b += texture2D(uTex, vUv + vec2(-o.x, -o.y)).rgb;
        o = uTexel * 7.0;
        b += texture2D(uTex, vUv + vec2( o.x,  0.0)).rgb;
        b += texture2D(uTex, vUv + vec2(-o.x,  0.0)).rgb;
        b += texture2D(uTex, vUv + vec2( 0.0,  o.y)).rgb;
        b += texture2D(uTex, vUv + vec2( 0.0, -o.y)).rgb;
        b *= 0.125;
        // only what is already bright blooms, and warm things bloom hardest
        vec3 over = max(b - 0.62, 0.0) * vec3(1.0, 0.82, 0.58);
        c += over * uBloom;
    }

    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(lum), c, 1.10);
    // split tone: push the dark end toward the sky, leave the light end alone
    c = mix(c * uShadowTint, c, smoothstep(0.05, 0.55, lum));
    c *= uGrade;
    c = clamp(c, 0.0, 1.0);
    // gentle S-curve: deepen the shadows without crushing them
    c = c * c * (3.0 - 2.0 * c) * 0.34 + c * 0.66;
    vec2 d = vUv - 0.5;
    float vig = 1.0 - uVignette * dot(d, d) * 1.7;
    gl_FragColor = vec4(c * vig, 1.0);
}
"""

    /** The UI layer, rasterised on the CPU at its own resolution. */
    const val UI_FS = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
void main() {
    vec4 c = texture2D(uTex, vec2(vUv.x, 1.0 - vUv.y));
    if (c.a < 0.004) discard;
    gl_FragColor = c;
}
"""
}
