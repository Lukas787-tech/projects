package com.cozyhollow.riverside.gl

/**
 * GLSL ES 1.00 shaders.
 *
 * The world shader does three things worth knowing about. It bends the ground
 * away from you in *view* space, so the hollow curves off in every direction
 * like a small planet instead of only sideways. It lights with a sun plus a
 * sky/ground hemisphere fill, which is what stops the shaded sides going flat
 * grey. And it reads a wind weight off the vertex colour, so grass, leaves and
 * washing on the line move while trunks and roof beams stay put.
 */
object Shaders {

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
varying float vFog;

void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);

    float sway = aCol.a * uWind;
    if (sway > 0.001) {
        float w = sin(uTime * 1.6 + wp.x * 0.75 + wp.z * 0.5)
                + sin(uTime * 2.7 + wp.x * 1.7) * 0.45;
        wp.x += w * sway * 0.6;
        wp.z += w * sway * 0.22;
        wp.y -= abs(w) * sway * 0.10;
    }

    mat3 nm = mat3(uModel[0].xyz, uModel[1].xyz, uModel[2].xyz);
    vec3 n = normalize(nm * aNor);

    float lam = max(dot(n, uSunDir), 0.0);
    float up = n.y * 0.5 + 0.5;
    vec3 fill = mix(uGroundFill, uSkyFill, up);
    vLight = uSunCol * (lam * 0.82 + 0.18) + fill;

    vec4 vp = uView * wp;
    float dist = length(vp.xyz);
    // the horizon drops away with distance: a small, cosy planet
    vp.y -= uCurve * vp.z * vp.z;

    vFog = clamp((dist - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    vUv = aUv * uUvScale;
    vTint = aCol.rgb;
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
varying vec2 vUv;
varying vec3 vLight;
varying vec3 vTint;
varying float vFog;

void main() {
    vec4 t = texture2D(uTex, vUv);
    if (t.a < uCut) discard;
    vec3 base = t.rgb * uColor.rgb * vTint;
    vec3 c = mix(base * vLight, base * 1.15, uEmissive);
    c = mix(c, uFogCol, vFog);
    gl_FragColor = vec4(c, t.a * uColor.a);
}
"""

    /** Water: swell, depth colour, a sky sheen at grazing angles, shoreline foam. */
    const val WATER_VS = """
attribute vec3 aPos;
attribute vec3 aNor;
attribute vec2 aUv;
attribute vec4 aCol;

uniform mat4 uProj;
uniform mat4 uView;
uniform float uCurve;
// highp on both sides: the vertex stage defaults to highp and the fragment
// stage to mediump, and GLSL ES refuses to link a uniform whose precision
// differs between them.
uniform highp float uTime;
uniform vec3 uCamPos;
uniform vec2 uFog;

varying vec2 vUv;
varying vec3 vNormal;
varying vec3 vView;
varying float vDepth;
varying float vFog;

void main() {
    vec3 p = aPos;
    float a = p.x * 1.05 + uTime * 1.25;
    float b = p.z * 0.85 - uTime * 0.95;
    float amp = 0.035 * (0.35 + aCol.r);
    p.y += (sin(a) + sin(b * 1.3 + 0.7) * 0.6) * amp;

    vec3 n = normalize(vec3(-cos(a) * amp * 1.05, 1.0, -cos(b * 1.3 + 0.7) * amp * 0.66));
    vNormal = n;
    vView = normalize(uCamPos - p);

    vec4 vp = uView * vec4(p, 1.0);
    float dist = length(vp.xyz);
    vp.y -= uCurve * vp.z * vp.z;
    vFog = clamp((dist - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    vUv = aUv;
    vDepth = aCol.r;
    gl_Position = uProj * vp;
}
"""

    const val WATER_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform vec3 uShallow;
uniform vec3 uDeep;
uniform vec3 uSkyCol;
uniform vec3 uSunCol;
uniform vec3 uSunDir;
uniform vec3 uFogCol;
// highp on both sides: the vertex stage defaults to highp and the fragment
// stage to mediump, and GLSL ES refuses to link a uniform whose precision
// differs between them.
uniform highp float uTime;
varying vec2 vUv;
varying vec3 vNormal;
varying vec3 vView;
varying float vDepth;
varying float vFog;

void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(vView);

    float d = clamp(vDepth, 0.0, 1.0);
    vec3 col = mix(uShallow, uDeep, d);

    // two crossed ripple sheets, sampled from the painted water tile
    float r1 = texture2D(uTex, vUv * 0.5 + vec2(uTime * 0.012, uTime * 0.007)).r;
    float r2 = texture2D(uTex, vUv * 0.31 - vec2(uTime * 0.009, uTime * 0.014)).r;
    col += (r1 + r2 - 1.0) * 0.09;

    // sky sheen, strongest where you look across the surface
    float fres = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 3.0);
    col = mix(col, uSkyCol, clamp(fres, 0.0, 1.0) * 0.55);

    // sun glitter
    vec3 h = normalize(uSunDir + v);
    float spec = pow(max(dot(n, h), 0.0), 42.0);
    col += uSunCol * spec * 0.75;

    // foam where the water meets the bank
    float foam = smoothstep(0.20, 0.0, d) * (0.55 + 0.45 * sin(vUv.x * 7.0 + vUv.y * 5.0 + uTime * 1.6));
    col = mix(col, vec3(0.94, 0.98, 1.0), clamp(foam, 0.0, 1.0) * 0.4);

    col = mix(col, uFogCol, vFog);
    gl_FragColor = vec4(col, mix(0.80, 0.95, d));
}
"""

    /** Sky gradient, drifting cloud band, stars and the sun or moon. */
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
        vec2 cell = floor(vUv * vec2(190.0 * uAspect, 108.0));
        float r = hash21(cell);
        if (r > 0.991) {
            float tw = 0.55 + 0.45 * hash21(cell + 3.7);
            float pulse = 0.75 + 0.25 * sin(uTime * 1.7 + r * 30.0);
            col += vec3(1.0, 0.98, 0.9) * uStars * tw * pulse * (1.0 - vUv.y * 0.35);
        }
    }

    vec2 d = vec2((vUv.x - uSun.x) * uAspect, vUv.y - uSun.y);
    float dist = length(d);
    col += uSunGlow * exp(-dist * 6.0) * 0.6;
    if (dist < uSunSize) col = uSunCol;

    // a soft band of cloud drifting along above the treeline
    vec2 cp = vec2(vUv.x * 2.6 + uTime * 0.006, vUv.y * 5.0);
    float n = vnoise(cp) * 0.6 + vnoise(cp * 2.3 + 4.1) * 0.3;
    float band = smoothstep(0.30, 0.55, vUv.y) * (1.0 - smoothstep(0.62, 0.95, vUv.y));
    float cloud = smoothstep(0.52, 0.78, n) * band * uHaze;
    col = mix(col, mix(vec3(1.0), uSunGlow, 0.35), cloud * 0.75);

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
     * Presenting the frame: a warm grade, a touch more colour, and a vignette
     * that pulls the eye into the middle of the valley.
     */
    const val BLIT_FS = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec3 uGrade;
uniform float uVignette;
void main() {
    vec3 c = texture2D(uTex, vUv).rgb;
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(lum), c, 1.12);
    c *= uGrade;
    c = clamp(c, 0.0, 1.0);
    // gentle S-curve: deepen the shadows without crushing them
    c = c * c * (3.0 - 2.0 * c) * 0.28 + c * 0.72;
    vec2 d = vUv - 0.5;
    float vig = 1.0 - uVignette * dot(d, d) * 1.6;
    gl_FragColor = vec4(c * vig, 1.0);
}
"""

    /** The UI layer, rasterised on the CPU at the same resolution. */
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
