package com.cozyhollow.riverside.gl

/**
 * GLSL ES 1.00 shaders. The interesting one is the world vertex shader: it bends
 * the whole valley around a horizontal cylinder centred on the camera, which is
 * what gives Animal Crossing its curved horizon, and keeps objects standing
 * upright on that curve instead of leaning with it.
 */
object Shaders {

    const val WORLD_VS = """
attribute vec3 aPos;
attribute vec3 aNor;
attribute vec2 aUv;

uniform mat4 uViewProj;
uniform mat4 uModel;
uniform float uCamX;
uniform float uCurve;
uniform float uBaseY;
uniform vec2 uUvScale;

uniform vec3 uSunDir;
uniform vec3 uSunCol;
uniform vec3 uAmbient;
uniform vec2 uFog;

varying vec2 vUv;
varying vec3 vLight;
varying float vFog;

void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);

    // --- cylindrical world bend ---
    float dx = wp.x - uCamX;
    float slope = -2.0 * uCurve * dx;
    float inv = inversesqrt(1.0 + slope * slope);
    float ca = inv;
    float sa = slope * inv;
    float h = wp.y - uBaseY;
    float baseY = uBaseY - uCurve * dx * dx;
    wp.x = wp.x - h * sa;
    wp.y = baseY + h * ca;

    mat3 nm = mat3(uModel[0].xyz, uModel[1].xyz, uModel[2].xyz);
    vec3 n = normalize(nm * aNor);
    // tip the normal with the curve so distant ground still catches the light
    n = normalize(vec3(n.x * ca - n.y * sa, n.x * sa + n.y * ca, n.z));

    float lambert = max(dot(n, uSunDir), 0.0);
    float wrap = max(dot(n, vec3(0.0, 1.0, 0.0)) * 0.5 + 0.5, 0.0);
    vLight = uAmbient + uSunCol * (lambert * 0.72 + wrap * 0.28);

    vec4 clip = uViewProj * wp;
    vFog = clamp((abs(dx) - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    vUv = aUv * uUvScale;
    gl_Position = clip;
}
"""

    const val WORLD_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform vec4 uColor;
uniform vec3 uFogCol;
varying vec2 vUv;
varying vec3 vLight;
varying float vFog;

void main() {
    vec4 t = texture2D(uTex, vUv);
    if (t.a < 0.4) discard;
    vec3 c = t.rgb * uColor.rgb * vLight;
    c = mix(c, uFogCol, vFog);
    gl_FragColor = vec4(c, t.a * uColor.a);
}
"""

    /** Sky gradient, stars and the sun/moon disc, all in one fullscreen pass. */
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

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    float t = vUv.y;
    vec3 col;
    if (t < 0.55) {
        col = mix(uHorizon, uMid, t / 0.55);
    } else {
        col = mix(uMid, uTop, (t - 0.55) / 0.45);
    }

    // stars on a coarse grid so they stay chunky under magnification
    if (uStars > 0.01) {
        vec2 cell = floor(vUv * vec2(160.0 * uAspect, 90.0));
        float r = hash21(cell);
        if (r > 0.992) {
            float tw = 0.55 + 0.45 * hash21(cell + 3.7);
            col += vec3(1.0, 0.98, 0.9) * uStars * tw * (1.0 - vUv.y * 0.4);
        }
    }

    // sun or moon
    vec2 d = vec2((vUv.x - uSun.x) * uAspect, vUv.y - uSun.y);
    float dist = length(d);
    float glow = exp(-dist * 7.0) * 0.55;
    col += uSunGlow * glow;
    if (dist < uSunSize) col = uSunCol;

    gl_FragColor = vec4(col, 1.0);
}
"""

    /** Nearest-neighbour blit of the low-res buffer up to the display. */
    const val BLIT_VS = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val BLIT_FS = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
void main() {
    gl_FragColor = texture2D(uTex, vUv);
}
"""

    /** The UI layer, rasterised on the CPU at the same low resolution. */
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
