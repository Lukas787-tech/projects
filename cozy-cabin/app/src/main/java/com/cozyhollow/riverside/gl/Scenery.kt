package com.cozyhollow.riverside.gl

import com.cozyhollow.riverside.Terrain
import com.cozyhollow.riverside.U
import com.cozyhollow.riverside.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The hollow in winter, baked once into static geometry.
 *
 * The map is cut into square chunks. Each one owns its patch of snow plus
 * everything rooted in it — trunks, boughs, boulders, dead bracken, the snow
 * sitting on all of it — batched by material, so a frame is a couple of dozen
 * draws rather than one per tree.
 *
 * The snow load is its own mesh. Every pine tier, every boulder and every
 * fallen log gets a second, slightly larger shell of white geometry laid over
 * its top half. That is the trick the whole look rests on: it costs one extra
 * draw call per chunk and it is the difference between "a green forest with a
 * white floor" and an actual snowy wood.
 */
class SceneChunk(val ix: Int, val iz: Int) {
    val x0 = -Terrain.HALF + ix * Scenery.CHUNK
    val z0 = -Terrain.HALF + iz * Scenery.CHUNK
    val cx = x0 + Scenery.CHUNK * 0.5f
    val cz = z0 + Scenery.CHUNK * 0.5f

    /** Where the trees in this chunk stand, decided before the ground is baked. */
    val plants = ArrayList<PlantSpot>(48)

    var ground: Mesh? = null
    var bark: Mesh? = null
    var leaf: Mesh? = null
    var rock: Mesh? = null
    var detail: Mesh? = null
    var flower: Mesh? = null
    /** Everything white that is sitting on top of something else. */
    var snow: Mesh? = null

    fun release() {
        ground?.release(); bark?.release(); leaf?.release()
        rock?.release(); detail?.release(); flower?.release(); snow?.release()
    }
}

/** One tree, chosen in the first pass so the second can shade the ground under it. */
class PlantSpot(
    val x: Float,
    val z: Float,
    val y: Float,
    val kind: Int,
    val scale: Float,
    val seed: Int
) {
    /** Roughly how far the crown reaches. */
    val shade: Float get() = when (kind) {
        0 -> 1.8f * scale
        1 -> 2.0f * scale
        2 -> 1.3f * scale
        else -> 1.5f * scale
    }
}

class Scenery {

    companion object {
        const val CHUNK = 11.5f
        val GRID = (Terrain.HALF * 2f / CHUNK).toInt()
        /** Ground vertices per chunk edge. */
        const val DIV = 18

        // ---- ground tints, multiplied into the neutral snow texture ----
        private const val SNOW_R = 1.00f; private const val SNOW_G = 1.02f; private const val SNOW_B = 1.08f
        private const val SHADE_R = 0.76f; private const val SHADE_G = 0.84f; private const val SHADE_B = 1.02f
        private const val PACK_R = 0.80f; private const val PACK_G = 0.82f; private const val PACK_B = 0.92f
        private const val BANK_R = 0.66f; private const val BANK_G = 0.70f; private const val BANK_B = 0.80f
        private const val ROCK_R = 0.52f; private const val ROCK_G = 0.55f; private const val ROCK_B = 0.64f
        private const val MUD_R = 0.44f; private const val MUD_G = 0.40f; private const val MUD_B = 0.38f
        private const val LITTER_R = 0.86f; private const val LITTER_G = 0.86f; private const val LITTER_B = 0.90f
    }

    val chunks = ArrayList<SceneChunk>(GRID * GRID)
    var ice: Mesh? = null
        private set

    fun build() {
        release()
        for (iz in 0 until GRID) for (ix in 0 until GRID) chunks.add(SceneChunk(ix, iz))
        for (ch in chunks) scatterTrees(ch)
        for (ch in chunks) {
            buildGround(ch)
            buildPlants(ch)
        }
        buildIce()
    }

    private fun chunkAt(ix: Int, iz: Int): SceneChunk? =
        if (ix < 0 || iz < 0 || ix >= GRID || iz >= GRID) null else chunks[iz * GRID + ix]

    /** How much crown hangs over this spot, 0..1. */
    private fun canopyShade(x: Float, z: Float): Float {
        val ix = ((x + Terrain.HALF) / CHUNK).toInt()
        val iz = ((z + Terrain.HALF) / CHUNK).toInt()
        var sum = 0f
        for (dz in -1..1) for (dx in -1..1) {
            val ch = chunkAt(ix + dx, iz + dz) ?: continue
            for (t in ch.plants) {
                val r = t.shade
                val ddx = x - t.x
                val ddz = z - t.z
                val d2 = ddx * ddx + ddz * ddz
                if (d2 > r * r) continue
                val d = sqrt(d2) / r
                sum += (1f - d) * (1f - d) * 0.75f
            }
        }
        return U.clamp01(sum)
    }

    private fun scatterTrees(ch: SceneChunk) {
        var tries = 0
        while (tries < 260) {
            tries++
            val s = ((ch.ix * 73856093) xor (ch.iz * 19349663)) + tries * 131
            val x = ch.x0 + U.hash(s * 7 + 1) * CHUNK
            val z = ch.z0 + U.hash(s * 13 + 5) * CHUNK
            val r = sqrt(x * x + z * z)
            // dense on the rim, thinning toward the yard you live in
            val want = U.smoothRange(r, 15f, 33f) * 0.88f + 0.04f
            if (U.hash(s * 29 + 3) > want) continue
            if (!plantable(x, z, 1.7f)) continue
            if (Terrain.steepness(x, z) > 0.62f) continue
            var tooClose = false
            for (t in ch.plants) {
                if (abs(t.x - x) < 1.5f && abs(t.z - z) < 1.5f) { tooClose = true; break }
            }
            if (tooClose) continue
            val scale = 0.85f + U.hash(s * 41 + 9) * 0.55f
            ch.plants.add(PlantSpot(x, z, Terrain.height(x, z), pickSpecies(x, z, s), scale, s))
            if (ch.plants.size >= 44) break
        }
    }

    fun release() {
        for (c in chunks) c.release()
        chunks.clear()
        ice?.release()
        ice = null
    }

    // ------------------------------------------------------------- ground

    /**
     * Colour of the ground at a point.
     *
     * Snow is not white. It is white in the sun and lavender-blue everywhere
     * else, and the whole scene depends on the difference between those two
     * being large. So the tint reads the aspect of the slope: ground tilted
     * toward the low southern sun gets the bright end, ground tilted away gets
     * the cold end, and every hollow between drifts fills with blue.
     */
    private fun groundTint(x: Float, z: Float, h: Float, steepness: Float, aspect: Float, out: FloatArray) {
        val patch = U.noise(x * 0.11f + 3f, 21) * 0.5f + U.noise(z * 0.13f + 7f, 33) * 0.5f
        // aspect: 1 facing the sun, 0 facing away
        val sun = U.clamp01(aspect * 0.7f + 0.35f + (patch - 0.5f) * 0.18f)
        var r = U.lerp(SHADE_R, SNOW_R, sun)
        var g = U.lerp(SHADE_G, SNOW_G, sun)
        var b = U.lerp(SHADE_B, SNOW_B, sun)

        // needle litter and bare ground under the thick of the wood
        val under = canopyShade(x, z)
        r = U.lerp(r, LITTER_R, under * 0.5f)
        g = U.lerp(g, LITTER_G, under * 0.5f)
        b = U.lerp(b, LITTER_B, under * 0.5f)

        // bare rock wherever the hill is too steep to hold snow
        val steep = U.smoothRange(steepness, 0.40f, 0.80f)
        r = U.lerp(r, ROCK_R, steep)
        g = U.lerp(g, ROCK_G, steep)
        b = U.lerp(b, ROCK_B, steep)

        // the trodden track
        val dp = World.distToPath(x, z)
        val onPath = 1f - U.smoothRange(dp, 0.6f, 1.8f)
        r = U.lerp(r, PACK_R, onPath)
        g = U.lerp(g, PACK_G, onPath)
        b = U.lerp(b, PACK_B, onPath)

        // scoured shingle right at the edge of the ice
        val bank = Terrain.shoreline(x, z)
        r = U.lerp(r, BANK_R, bank * 0.85f)
        g = U.lerp(g, BANK_G, bank * 0.85f)
        b = U.lerp(b, BANK_B, bank * 0.85f)

        // nothing holds snow round the steam vent
        val warm = Terrain.springWarmth(x, z)
        r = U.lerp(r, MUD_R, warm)
        g = U.lerp(g, MUD_G, warm)
        b = U.lerp(b, MUD_B, warm)

        out[0] = r; out[1] = g; out[2] = b
    }

    private val tintTmp = FloatArray(3)

    private fun buildGround(ch: SceneChunk) {
        val b = MeshBuilder()
        val step = CHUNK / DIV
        // Sample the height field once, with a one-cell border, and read the
        // normals and the slope back out of it. Asking Terrain for every
        // neighbour again would multiply the cost of loading by five.
        val n = DIV + 3
        val hs = FloatArray(n * n)
        for (j in 0 until n) {
            for (i in 0 until n) {
                hs[j * n + i] = Terrain.height(ch.x0 + (i - 1) * step, ch.z0 + (j - 1) * step)
            }
        }
        for (iz in 0..DIV) {
            for (ix in 0..DIV) {
                val x = ch.x0 + ix * step
                val z = ch.z0 + iz * step
                val i = ix + 1
                val j = iz + 1
                val y = hs[j * n + i]
                val dx = hs[j * n + i + 1] - hs[j * n + i - 1]
                val dz = hs[(j + 1) * n + i] - hs[(j - 1) * n + i]
                var nx = -dx
                var ny = 2f * step
                var nz = -dz
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
                nx /= len; ny /= len; nz /= len
                val slope = U.clamp01(sqrt(dx * dx + dz * dz) / (2f * step) * 0.9f)
                // the winter sun sits low in the south-west, so that is the
                // direction a drift catches the light from
                val aspect = U.clamp01((nx * -0.62f + nz * 0.42f) * 1.6f + 0.5f)
                groundTint(x, z, y, slope, aspect, tintTmp)
                b.color(tintTmp[0], tintTmp[1], tintTmp[2], 0f)
                b.vertex(x, y, z, nx, ny, nz, x * 0.35f, z * 0.35f)
            }
        }
        val rowLen = DIV + 1
        for (iz in 0 until DIV) {
            for (ix in 0 until DIV) {
                val a = iz * rowLen + ix
                val c = a + rowLen
                b.tri(a, c, a + 1); b.tri(a + 1, c, c + 1)
            }
        }
        ch.ground = b.build()
    }

    // ---------------------------------------------------------------- ice

    /**
     * One sheet for the creek and the pond together: a quad wherever the
     * ground is cut below the water table, with the depth written into the
     * vertex colour so the shader can darken the middle and drift snow onto
     * the edges.
     */
    private fun buildIce() {
        val b = MeshBuilder()
        val step = 1.15f
        val n = (Terrain.HALF * 2f / step).toInt()
        for (iz in 0 until n) {
            for (ix in 0 until n) {
                val x = -Terrain.HALF + ix * step
                val z = -Terrain.HALF + iz * step
                val x1 = x + step
                val z1 = z + step
                val d00 = Terrain.ICE_Y - Terrain.height(x, z)
                val d10 = Terrain.ICE_Y - Terrain.height(x1, z)
                val d01 = Terrain.ICE_Y - Terrain.height(x, z1)
                val d11 = Terrain.ICE_Y - Terrain.height(x1, z1)
                if (d00 < -0.30f && d10 < -0.30f && d01 < -0.30f && d11 < -0.30f) continue
                val y = Terrain.ICE_Y
                fun depth(d: Float) = U.clamp01(d / 1.15f)
                val base = b.vertexCount
                b.color(depth(d00), 0f, 0f, 0f)
                b.vertex(x, y, z1 - step, 0f, 1f, 0f, x * 0.25f, z * 0.25f)
                b.color(depth(d10), 0f, 0f, 0f)
                b.vertex(x1, y, z1 - step, 0f, 1f, 0f, x1 * 0.25f, z * 0.25f)
                b.color(depth(d11), 0f, 0f, 0f)
                b.vertex(x1, y, z1, 0f, 1f, 0f, x1 * 0.25f, z1 * 0.25f)
                b.color(depth(d01), 0f, 0f, 0f)
                b.vertex(x, y, z1, 0f, 1f, 0f, x * 0.25f, z1 * 0.25f)
                b.tri(base, base + 3, base + 2)
                b.tri(base, base + 2, base + 1)
                if (b.vertexCount > 60000) break
            }
            if (b.vertexCount > 60000) break
        }
        ice = if (b.isEmpty) null else b.build()
    }

    // ------------------------------------------------------------- plants

    private fun buildPlants(ch: SceneChunk) {
        val bark = MeshBuilder()
        val leaf = MeshBuilder()
        val rock = MeshBuilder()
        val detail = MeshBuilder()
        val flower = MeshBuilder()
        val snow = MeshBuilder()

        val baseSeed = (ch.ix * 73856093) xor (ch.iz * 19349663)
        var tries: Int

        // ---- trees ----
        for (t in ch.plants) {
            when (t.kind) {
                0 -> snowPine(bark, leaf, snow, t.x, t.y, t.z, t.scale, t.seed)
                1 -> bareOak(bark, snow, t.x, t.y, t.z, t.scale, t.seed)
                2 -> bareBirch(bark, snow, t.x, t.y, t.z, t.scale, t.seed)
                else -> snag(bark, snow, t.x, t.y, t.z, t.scale, t.seed)
            }
            if (bark.vertexCount > 24000 || leaf.vertexCount > 40000 || snow.vertexCount > 34000) break
        }

        // ---- boulders, buried bushes and fallen logs ----
        tries = 0
        while (tries < 150) {
            tries++
            val s = baseSeed + 7777 + tries * 97
            val x = ch.x0 + U.hash(s * 11 + 2) * CHUNK
            val z = ch.z0 + U.hash(s * 17 + 6) * CHUNK
            if (!plantable(x, z, 1.0f)) continue
            val y = Terrain.height(x, z)
            val roll = U.hash(s * 23 + 4)
            when {
                roll < 0.30f -> {
                    // a bush under its own weight of snow: the green barely shows
                    val rr = 0.34f + U.hash(s * 31) * 0.34f
                    val tone = 0.82f + U.hash(s * 37) * 0.3f
                    leaf.color(0.34f * tone, 0.52f * tone, 0.44f * tone, 0.02f)
                    leaf.blob(x, y + rr * 0.62f, z, rr, 4, 7, 1.1f)
                    snow.color(1.02f, 1.04f, 1.10f, 0f)
                    snow.blob(x, y + rr * 0.86f, z, rr * 0.86f, 3, 7, 1f)
                }
                roll < 0.60f -> {
                    val rr = 0.28f + U.hash(s * 53) * 0.55f
                    val tone = 0.78f + U.hash(s * 59) * 0.24f
                    rock.color(tone, tone, tone * 1.06f, 0f)
                    rock.blob(x, y + rr * 0.30f, z, rr, 3, 6, 0.8f)
                    // a cap of snow on the top half
                    snow.color(1.0f, 1.03f, 1.10f, 0f)
                    snow.blob(x, y + rr * 0.48f, z, rr * 0.90f, 2, 6, 0.8f)
                    if (rr > 0.55f) {
                        rock.color(tone, tone, tone * 1.06f, 0f)
                        rock.blob(x + rr * 0.7f, y + rr * 0.18f, z - rr * 0.4f, rr * 0.5f, 3, 6, 0.8f)
                    }
                }
                roll < 0.72f && Terrain.steepness(x, z) < 0.3f -> {
                    val len = 1.4f + U.hash(s * 61) * 1.6f
                    val ang = U.hash(s * 67) * 3.1416f
                    bark.color(0.86f, 0.82f, 0.78f, 0f)
                    logAt(bark, x, y + 0.18f, z, len, 0.19f, ang)
                    snow.color(1.0f, 1.03f, 1.10f, 0f)
                    logAt(snow, x, y + 0.26f, z, len * 0.96f, 0.17f, ang)
                }
            }
            if (rock.vertexCount > 20000 || snow.vertexCount > 34000) break
        }

        // ---- ground detail: dead tufts, reeds through the ice edge, twigs ----
        tries = 0
        while (tries < 430) {
            tries++
            val s = baseSeed + 33331 + tries * 61
            val x = ch.x0 + U.hash(s * 3 + 1) * CHUNK
            val z = ch.z0 + U.hash(s * 5 + 3) * CHUNK
            val h = Terrain.height(x, z)
            val ang = U.hash(s * 71) * 3.1416f
            if (h < Terrain.ICE_Y - 0.55f) continue
            if (h < Terrain.ICE_Y + 0.30f) {
                // frozen into the edge of the sheet: dry reeds, snapped short
                if (U.hash(s * 89) < 0.42f) {
                    detail.color(0.94f, 0.92f, 0.86f, 0.10f)
                    detail.cross(
                        x, Terrain.ICE_Y, z, 0.24f, 0.42f + U.hash(s * 97) * 0.34f, ang,
                        0.10f, 0.5f, 0f, 0.5f, 0.5f
                    )
                }
                continue
            }
            if (World.distToPath(x, z) < 0.8f) continue
            if (World.inGlasshouse(x, z, 0.6f)) continue
            if (Terrain.steepness(x, z) > 0.72f) continue
            val shade = 0.86f + U.hash(s * 101) * 0.3f
            val roll = U.hash(s * 103)
            // most of the ground cover is buried; only the tallest stems show
            if (roll < 0.10f) {
                detail.color(0.90f * shade, 0.88f * shade, 0.84f * shade, 0.06f)
                detail.cross(x, h, z, 0.28f, 0.34f + U.hash(s * 107) * 0.2f, ang, 0.06f, 0f, 0.5f, 0.5f, 0.5f)
            } else if (roll < 0.17f) {
                // winterberry, in patches rather than sprinkled evenly
                val patch = U.noise(x * 0.22f, 5) * 0.5f + U.noise(z * 0.19f + 2f, 9) * 0.5f
                if (patch > 0.55f) {
                    val q = (U.hash(s * 109) * 4f).toInt().coerceIn(0, 3)
                    flower.color(1f, 1f, 1f, 0.08f)
                    flower.cross(
                        x, h, z, 0.18f, 0.34f + U.hash(s * 113) * 0.12f, ang, 0.08f,
                        (q % 2) * 0.5f, (q / 2) * 0.5f, 0.5f, 0.5f
                    )
                }
            } else if (roll < 0.34f) {
                val tall = if (U.hash(s * 127) < 0.3f) 1.4f else 1f
                detail.color(0.92f * shade, 0.90f * shade, 0.86f * shade, 0.11f)
                detail.cross(
                    x, h - 0.03f, z, 0.20f * tall, (0.24f + U.hash(s * 131) * 0.20f) * tall, ang,
                    0.11f, 0f, 0f, 0.5f, 0.5f
                )
            }
            if (detail.vertexCount > 30000 || flower.vertexCount > 18000) break
        }

        ch.bark = if (bark.isEmpty) null else bark.build()
        ch.leaf = if (leaf.isEmpty) null else leaf.build()
        ch.rock = if (rock.isEmpty) null else rock.build()
        ch.detail = if (detail.isEmpty) null else detail.build()
        ch.flower = if (flower.isEmpty) null else flower.build()
        ch.snow = if (snow.isEmpty) null else snow.build()
    }

    /** True where a plant may take root: off the ice, off the track, clear of home. */
    private fun plantable(x: Float, z: Float, clearance: Float): Boolean {
        val h = Terrain.height(x, z)
        if (h < Terrain.ICE_Y + 0.35f) return false
        if (World.distToPath(x, z) < 1.5f + clearance * 0.4f) return false
        if (World.inGlasshouse(x, z, 2.4f)) return false
        if (Terrain.onBridge(x, z)) return false
        if (Terrain.springWarmth(x, z) > 0.04f) return false
        for (s in World.solids) {
            if (x > s.x0 - 2.6f && x < s.x1 + 2.6f && z > s.z0 - 2.6f && z < s.z1 + 2.6f) return false
        }
        for (p in World.props) {
            if (abs(p.x - x) < 2.2f && abs(p.z - z) < 2.2f) return false
        }
        for (t in World.trees) {
            if (abs(t.x - x) < 2.2f && abs(t.z - z) < 2.2f) return false
        }
        for (f in World.forage) {
            if (abs(f.x - x) < 1.2f && abs(f.z - z) < 1.2f) return false
        }
        // leave the fire circle and the front yard open
        if (abs(x - World.FIRE_X) < 3.2f && abs(z - World.FIRE_Z) < 3.2f) return false
        return true
    }

    /** Which tree grows here: pines up high and everywhere, bare hardwood low down. */
    private fun pickSpecies(x: Float, z: Float, seed: Int): Int {
        val h = Terrain.height(x, z)
        val roll = U.hash(seed * 149 + 11)
        if (h > 3.5f) return if (roll < 0.86f) 0 else 3
        return when {
            roll < 0.56f -> 0
            roll < 0.78f -> 1
            roll < 0.93f -> 2
            else -> 3
        }
    }

    // ------------------------------------------------------------- shapes

    private fun logAt(b: MeshBuilder, x: Float, y: Float, z: Float, len: Float, r: Float, ang: Float) {
        val dx = cos(ang) * len * 0.5f
        val dz = sin(ang) * len * 0.5f
        val seg = 6
        val step = (Math.PI * 2.0 / seg).toFloat()
        for (i in 0 until seg) {
            val a0 = i * step
            val a1 = (i + 1) * step
            val y0 = cos(a0) * r; val o0 = sin(a0) * r
            val y1 = cos(a1) * r; val o1 = sin(a1) * r
            val px = -sin(ang); val pz = cos(ang)
            val v = b.vertexCount
            b.vertex(x - dx + px * o0, y + y0, z - dz + pz * o0, px * o0, y0, pz * o0, 0f, 0f)
            b.vertex(x + dx + px * o0, y + y0, z + dz + pz * o0, px * o0, y0, pz * o0, len, 0f)
            b.vertex(x + dx + px * o1, y + y1, z + dz + pz * o1, px * o1, y1, pz * o1, len, r * 2f)
            b.vertex(x - dx + px * o1, y + y1, z - dz + pz * o1, px * o1, y1, pz * o1, 0f, r * 2f)
            b.tri(v, v + 1, v + 2); b.tri(v, v + 2, v + 3)
        }
    }

    /** One tapering limb, aimed by two angles. Used to build a bare crown. */
    private fun limb(
        b: MeshBuilder, x: Float, y: Float, z: Float,
        len: Float, r0: Float, r1: Float, yaw: Float, lean: Float
    ): FloatArray {
        val up = cos(lean)
        val out = sin(lean)
        val dx = cos(yaw) * out
        val dz = sin(yaw) * out
        val seg = 4
        val step = (Math.PI * 2.0 / seg).toFloat()
        // a frame perpendicular to the limb, near enough for a four-sided stick
        val px = -sin(yaw); val pz = cos(yaw)
        val qx = -cos(yaw) * up; val qy = out; val qz = -sin(yaw) * up
        for (i in 0 until seg) {
            val a0 = i * step
            val a1 = (i + 1) * step
            val c0 = cos(a0); val s0 = sin(a0)
            val c1 = cos(a1); val s1 = sin(a1)
            val v = b.vertexCount
            fun put(t: Float, c: Float, s: Float, rr: Float, u: Float) {
                val ox = (px * c + qx * s) * rr
                val oy = (qy * s) * rr
                val oz = (pz * c + qz * s) * rr
                b.vertex(
                    x + dx * len * t + ox, y + up * len * t + oy, z + dz * len * t + oz,
                    ox, oy + 0.2f, oz, u, t * len
                )
            }
            put(0f, c0, s0, r0, 0f)
            put(0f, c1, s1, r0, r0 * 3f)
            put(1f, c1, s1, r1, r0 * 3f)
            put(1f, c0, s0, r1, 0f)
            b.tri(v, v + 1, v + 2); b.tri(v, v + 2, v + 3)
        }
        return floatArrayOf(x + dx * len, y + up * len, z + dz * len)
    }

    // ------------------------------------------------------------- species

    /**
     * A snow-laden pine. Four tiers of dark needles, each with a wider, flatter
     * white cone sitting a hand's width above it, so the branch reads as bowed
     * under the weight.
     */
    private fun snowPine(
        bark: MeshBuilder, leaf: MeshBuilder, snow: MeshBuilder,
        x: Float, y: Float, z: Float, s: Float, seed: Int
    ) {
        val tone = 0.86f + U.hash(seed * 151) * 0.28f
        bark.color(0.72f, 0.66f, 0.62f, 0f)
        bark.cylinder(x, y - 0.1f, z, 0.20f * s, 0.13f * s, 1.5f * s, 6, 1f)
        val tiers = 4
        for (k in 0 until tiers) {
            val cy = y + (0.8f + k * 0.95f) * s
            val cr = (1.5f - k * 0.28f) * s
            val chh = (1.7f - k * 0.16f) * s
            leaf.color(0.34f * tone, 0.56f * tone, 0.48f * tone, 0.025f)
            leaf.cone(x, cy, z, cr, chh, 8, 0.9f)
            // the load: a little wider at the base, much shallower
            snow.color(1.0f, 1.03f, 1.10f, 0.01f)
            snow.cone(x, cy + chh * 0.30f, z, cr * 0.94f, chh * 0.62f, 8, 0.8f)
        }
        // and a cap right on the leader
        snow.color(1.02f, 1.05f, 1.12f, 0f)
        snow.cone(x, y + (0.8f + tiers * 0.95f) * s - 0.2f * s, z, 0.42f * s, 0.7f * s, 6, 0.8f)
    }

    /** A bare hardwood: a short trunk and a fan of limbs, snow along the top. */
    private fun bareOak(
        bark: MeshBuilder, snow: MeshBuilder,
        x: Float, y: Float, z: Float, s: Float, seed: Int
    ) {
        bark.color(0.62f, 0.56f, 0.52f, 0f)
        bark.cylinder(x, y - 0.1f, z, 0.26f * s, 0.18f * s, 1.7f * s, 6, 1f)
        val top = y + 1.6f * s
        val n = 4 + (U.hash(seed * 157) * 2f).toInt()
        for (i in 0 until n) {
            val yaw = (i.toFloat() / n) * 6.2832f + U.hash(seed * 163 + i) * 0.8f
            val lean = 0.42f + U.hash(seed * 167 + i) * 0.36f
            bark.color(0.60f, 0.55f, 0.52f, 0.012f)
            val tip = limb(bark, x, top, z, 1.5f * s, 0.14f * s, 0.07f * s, yaw, lean)
            // the same limb again, a little thinner and a little higher: the
            // snow lying along the top of the branch
            snow.color(1.0f, 1.03f, 1.10f, 0.012f)
            limb(snow, x, top + 0.06f * s, z, 1.42f * s, 0.10f * s, 0.05f * s, yaw, lean)
            // a couple of forks off each limb
            for (k in 0 until 2) {
                val yaw2 = yaw + (if (k == 0) 0.55f else -0.5f) + U.hash(seed * 173 + i * 3 + k) * 0.3f
                val lean2 = lean * 0.6f + 0.12f
                bark.color(0.58f, 0.54f, 0.51f, 0.03f)
                limb(bark, tip[0], tip[1], tip[2], 1.0f * s, 0.06f * s, 0.02f * s, yaw2, lean2)
            }
        }
    }

    /** A birch: pale, thin, upright, almost nothing left on it. */
    private fun bareBirch(
        bark: MeshBuilder, snow: MeshBuilder,
        x: Float, y: Float, z: Float, s: Float, seed: Int
    ) {
        // pale tint on the shared bark tile: birch without a second draw call
        bark.color(1.42f, 1.40f, 1.36f, 0f)
        bark.cylinder(x, y - 0.1f, z, 0.14f * s, 0.09f * s, 2.6f * s, 6, 1f)
        val top = y + 2.4f * s
        val n = 3 + (U.hash(seed * 179) * 2f).toInt()
        for (i in 0 until n) {
            val yaw = (i.toFloat() / n) * 6.2832f + U.hash(seed * 181 + i) * 1.1f
            val lean = 0.30f + U.hash(seed * 191 + i) * 0.30f
            bark.color(1.30f, 1.28f, 1.26f, 0.03f)
            val tip = limb(bark, x, top - U.hash(seed * 193 + i) * 0.8f * s, z, 1.2f * s, 0.06f * s, 0.02f * s, yaw, lean)
            snow.color(1.0f, 1.03f, 1.10f, 0.02f)
            snow.blob(tip[0], tip[1], tip[2], 0.09f * s, 2, 5, 1f)
        }
    }

    /** A dead snag: a broken trunk with two stubs and a hat of snow. */
    private fun snag(
        bark: MeshBuilder, snow: MeshBuilder,
        x: Float, y: Float, z: Float, s: Float, seed: Int
    ) {
        val h = 1.6f + U.hash(seed * 197) * 1.4f
        bark.color(0.54f, 0.50f, 0.48f, 0f)
        bark.cylinder(x, y - 0.1f, z, 0.28f * s, 0.20f * s, h * s, 6, 1f)
        for (k in 0 until 2) {
            val yaw = U.hash(seed * 199 + k) * 6.2832f
            bark.color(0.52f, 0.49f, 0.47f, 0.015f)
            limb(bark, x, y + h * s * 0.62f, z, 0.8f * s, 0.09f * s, 0.04f * s, yaw, 0.9f)
        }
        snow.color(1.0f, 1.03f, 1.10f, 0f)
        snow.blob(x, y + h * s, z, 0.24f * s, 2, 6, 1f)
    }
}
