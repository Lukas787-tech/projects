package com.cozyhollow.riverside.gl

import com.cozyhollow.riverside.Terrain
import com.cozyhollow.riverside.U
import com.cozyhollow.riverside.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The hollow, baked once into static geometry.
 *
 * The map is cut into square chunks. Each one owns its patch of ground plus
 * everything rooted in it — trunks, canopies, boulders, tufts, wildflowers —
 * batched by material, so a frame is a couple of dozen draws rather than one
 * per tree. Nothing here moves except in the wind, and the wind lives in the
 * vertex shader, which is why all of this can be uploaded once and forgotten.
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

    fun release() {
        ground?.release(); bark?.release(); leaf?.release()
        rock?.release(); detail?.release(); flower?.release()
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
    /** Roughly how far the canopy throws its shade. */
    val shade: Float get() = when (kind) {
        0 -> 1.7f * scale
        1 -> 2.2f * scale
        2 -> 1.4f * scale
        else -> 1.8f * scale
    }
}

class Scenery {

    companion object {
        const val CHUNK = 11.5f
        val GRID = (Terrain.HALF * 2f / CHUNK).toInt()
        /** Ground vertices per chunk edge. */
        const val DIV = 16

        // ---- ground tints, multiplied into the neutral ground texture ----
        private const val MEADOW_R = 0.60f; private const val MEADOW_G = 0.86f; private const val MEADOW_B = 0.44f
        private const val DARK_R = 0.44f; private const val DARK_G = 0.68f; private const val DARK_B = 0.38f
        private const val DRY_R = 0.86f; private const val DRY_G = 0.84f; private const val DRY_B = 0.48f
        private const val SAND_R = 1.05f; private const val SAND_G = 0.94f; private const val SAND_B = 0.68f
        private const val PATH_R = 0.82f; private const val PATH_G = 0.64f; private const val PATH_B = 0.44f
        private const val ROCK_R = 0.72f; private const val ROCK_G = 0.70f; private const val ROCK_B = 0.66f
    }

    val chunks = ArrayList<SceneChunk>(GRID * GRID)
    var water: Mesh? = null
        private set

    fun build() {
        release()
        // First decide where every tree stands, then bake the ground — that way
        // the woods can lay their own shade into the grass instead of the frame
        // paying for a shadow decal under each of a thousand trunks.
        for (iz in 0 until GRID) for (ix in 0 until GRID) chunks.add(SceneChunk(ix, iz))
        for (ch in chunks) scatterTrees(ch)
        for (ch in chunks) {
            buildGround(ch)
            buildPlants(ch)
        }
        buildWater()
    }

    private fun chunkAt(ix: Int, iz: Int): SceneChunk? =
        if (ix < 0 || iz < 0 || ix >= GRID || iz >= GRID) null else chunks[iz * GRID + ix]

    /** How much canopy hangs over this spot, 0..1. */
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
            // dense on the rim, thinning toward the meadow you live in
            val want = U.smoothRange(r, 16f, 34f) * 0.85f + 0.04f
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
        water?.release()
        water = null
    }

    // ------------------------------------------------------------- ground

    /** Colour of the soil at a point: meadow, sand, track, dry ridge or rock. */
    private fun groundTint(x: Float, z: Float, h: Float, steepness: Float, out: FloatArray) {
        val patch = U.noise(x * 0.11f + 3f, 21) * 0.5f + U.noise(z * 0.13f + 7f, 33) * 0.5f
        var r = U.lerp(DARK_R, MEADOW_R, patch)
        var g = U.lerp(DARK_G, MEADOW_G, patch)
        var b = U.lerp(DARK_B, MEADOW_B, patch)

        // sun-bleached grass up on the ridges
        val dry = U.smoothRange(h, 1.6f, 5.5f)
        r = U.lerp(r, DRY_R, dry * 0.7f)
        g = U.lerp(g, DRY_G, dry * 0.7f)
        b = U.lerp(b, DRY_B, dry * 0.7f)

        // bare rock wherever the hill is too steep to hold soil
        val steep = U.smoothRange(steepness, 0.45f, 0.85f)
        r = U.lerp(r, ROCK_R, steep)
        g = U.lerp(g, ROCK_G, steep)
        b = U.lerp(b, ROCK_B, steep)

        // the worn track
        val dp = World.distToPath(x, z)
        val onPath = 1f - U.smoothRange(dp, 0.7f, 1.9f)
        r = U.lerp(r, PATH_R, onPath)
        g = U.lerp(g, PATH_G, onPath)
        b = U.lerp(b, PATH_B, onPath)

        // sand at the waterline
        val sand = Terrain.sandiness(x, z)
        r = U.lerp(r, SAND_R, sand)
        g = U.lerp(g, SAND_G, sand)
        b = U.lerp(b, SAND_B, sand)

        // and a little shade in the hollows, which reads as damp ground
        val damp = 1f - U.smoothRange(h - Terrain.WATER_Y, 0.4f, 2.0f)
        val f = U.lerp(1f, 0.84f, damp * 0.6f)
        out[0] = r * f; out[1] = g * f; out[2] = b * f
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
                groundTint(x, z, y, slope, tintTmp)
                val shade = 1f - canopyShade(x, z) * 0.42f
                b.color(tintTmp[0] * shade, tintTmp[1] * shade, tintTmp[2] * shade, 0f)
                b.vertex(x, y, z, nx, ny, nz, x * 0.5f, z * 0.5f)
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

    // -------------------------------------------------------------- water

    /**
     * One surface for the river and the pond together: a quad wherever the
     * ground is cut below the water table, with the depth written into the
     * vertex colour so the shader can shade the shallows and foam the edges.
     */
    private fun buildWater() {
        val b = MeshBuilder()
        val step = 1.15f
        val n = (Terrain.HALF * 2f / step).toInt()
        for (iz in 0 until n) {
            for (ix in 0 until n) {
                val x = -Terrain.HALF + ix * step
                val z = -Terrain.HALF + iz * step
                val x1 = x + step
                val z1 = z + step
                // include cells that only just reach the water, so the surface
                // slides under the bank instead of stopping short of it
                val d00 = Terrain.WATER_Y - Terrain.height(x, z)
                val d10 = Terrain.WATER_Y - Terrain.height(x1, z)
                val d01 = Terrain.WATER_Y - Terrain.height(x, z1)
                val d11 = Terrain.WATER_Y - Terrain.height(x1, z1)
                if (d00 < -0.35f && d10 < -0.35f && d01 < -0.35f && d11 < -0.35f) continue
                val y = Terrain.WATER_Y
                fun depth(d: Float) = U.clamp01(d / 1.1f)
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
        water = if (b.isEmpty) null else b.build()
    }

    // ------------------------------------------------------------- plants

    private fun buildPlants(ch: SceneChunk) {
        val bark = MeshBuilder()
        val leaf = MeshBuilder()
        val rock = MeshBuilder()
        val detail = MeshBuilder()
        val flower = MeshBuilder()

        val baseSeed = (ch.ix * 73856093) xor (ch.iz * 19349663)
        var tries: Int

        // ---- trees ----
        for (t in ch.plants) {
            when (t.kind) {
                0 -> pine(bark, leaf, t.x, t.y, t.z, t.scale, t.seed)
                1 -> oak(bark, leaf, t.x, t.y, t.z, t.scale, t.seed)
                2 -> birch(bark, leaf, t.x, t.y, t.z, t.scale, t.seed)
                else -> blossom(bark, leaf, t.x, t.y, t.z, t.scale, t.seed)
            }
            if (bark.vertexCount > 22000 || leaf.vertexCount > 40000) break
        }

        // ---- bushes, boulders and fallen logs ----
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
                roll < 0.34f -> {
                    val rr = 0.34f + U.hash(s * 31) * 0.34f
                    val tone = 0.82f + U.hash(s * 37) * 0.3f
                    leaf.color(0.40f * tone, 0.60f * tone, 0.30f * tone, 0.035f)
                    leaf.blob(x, y + rr * 0.66f, z, rr, 4, 7, 1.1f)
                    if (U.hash(s * 43) < 0.55f) {
                        leaf.blob(x + rr * 0.8f, y + rr * 0.5f, z + rr * 0.35f, rr * 0.68f, 3, 6, 1.1f)
                    }
                }
                roll < 0.62f -> {
                    val rr = 0.28f + U.hash(s * 53) * 0.55f
                    val tone = 0.86f + U.hash(s * 59) * 0.26f
                    rock.color(tone, tone, tone * 0.98f, 0f)
                    rock.blob(x, y + rr * 0.34f, z, rr, 3, 6, 0.8f)
                    if (rr > 0.55f) rock.blob(x + rr * 0.7f, y + rr * 0.2f, z - rr * 0.4f, rr * 0.5f, 3, 6, 0.8f)
                }
                roll < 0.72f && Terrain.steepness(x, z) < 0.3f -> {
                    // a fallen log, mossy on top
                    val len = 1.4f + U.hash(s * 61) * 1.6f
                    val ang = U.hash(s * 67) * 3.1416f
                    bark.color(0.86f, 0.82f, 0.78f, 0f)
                    logAt(bark, x, y + 0.18f, z, len, 0.19f, ang)
                    leaf.color(0.40f, 0.58f, 0.32f, 0.02f)
                    leaf.blob(x, y + 0.34f, z, 0.16f, 3, 5, 1f)
                }
            }
            if (rock.vertexCount > 20000 || leaf.vertexCount > 44000) break
        }

        // ---- ground detail: tufts, ferns, reeds, lily pads ----
        tries = 0
        while (tries < 430) {
            tries++
            val s = baseSeed + 33331 + tries * 61
            val x = ch.x0 + U.hash(s * 3 + 1) * CHUNK
            val z = ch.z0 + U.hash(s * 5 + 3) * CHUNK
            val h = Terrain.height(x, z)
            val ang = U.hash(s * 71) * 3.1416f
            if (h < Terrain.WATER_Y - 0.65f) {
                // deep water: a lily pad, only on the still pond
                val dp = sqrt((x - World.POND_X) * (x - World.POND_X) + (z - World.POND_Z) * (z - World.POND_Z))
                if (dp < Terrain.POND_R && U.hash(s * 79) < 0.16f) {
                    val rr = 0.30f + U.hash(s * 83) * 0.22f
                    detail.color(0.92f, 1f, 0.9f, 0f)
                    flatSprite(detail, x, Terrain.WATER_Y + 0.035f, z, rr, ang, 0.5f, 0.5f)
                }
                continue
            }
            if (h < Terrain.WATER_Y + 0.35f) {
                // the water's edge: rushes
                if (U.hash(s * 89) < 0.5f) {
                    detail.color(0.88f, 1.0f, 0.78f, 0.14f)
                    detail.cross(x, h, z, 0.26f, 0.55f + U.hash(s * 97) * 0.4f, ang, 0.14f, 0.5f, 0f, 0.5f, 0.5f)
                }
                continue
            }
            if (World.distToPath(x, z) < 0.9f) continue
            if (World.inField(x, z, 0.4f)) continue
            if (Terrain.steepness(x, z) > 0.72f) continue
            val shade = 0.82f + U.hash(s * 101) * 0.36f
            val roll = U.hash(s * 103)
            if (roll < 0.14f) {
                // a fern, in the greener shade
                detail.color(0.86f * shade, 1.02f * shade, 0.78f * shade, 0.07f)
                detail.cross(x, h, z, 0.30f, 0.42f + U.hash(s * 107) * 0.2f, ang, 0.07f, 0f, 0.5f, 0.5f, 0.5f)
            } else if (roll < 0.24f) {
                // wildflowers, in patches rather than sprinkled evenly
                val patch = U.noise(x * 0.22f, 5) * 0.5f + U.noise(z * 0.19f + 2f, 9) * 0.5f
                if (patch > 0.52f) {
                    val q = (U.hash(s * 109) * 4f).toInt().coerceIn(0, 3)
                    flower.color(1f, 1f, 1f, 0.10f)
                    flower.cross(
                        x, h, z, 0.17f, 0.32f + U.hash(s * 113) * 0.12f, ang, 0.10f,
                        (q % 2) * 0.5f, (q / 2) * 0.5f, 0.5f, 0.5f
                    )
                }
            } else {
                val tall = if (U.hash(s * 127) < 0.25f) 1.5f else 1f
                detail.color(0.74f * shade, 1.0f * shade, 0.56f * shade, 0.13f)
                detail.cross(x, h - 0.03f, z, 0.22f * tall, (0.30f + U.hash(s * 131) * 0.22f) * tall, ang, 0.13f, 0f, 0f, 0.5f, 0.5f)
            }
            if (detail.vertexCount > 34000 || flower.vertexCount > 20000) break
        }

        ch.bark = if (bark.isEmpty) null else bark.build()
        ch.leaf = if (leaf.isEmpty) null else leaf.build()
        ch.rock = if (rock.isEmpty) null else rock.build()
        ch.detail = if (detail.isEmpty) null else detail.build()
        ch.flower = if (flower.isEmpty) null else flower.build()
    }

    /** True where a plant may take root: dry land, off the track, clear of home. */
    private fun plantable(x: Float, z: Float, clearance: Float): Boolean {
        val h = Terrain.height(x, z)
        if (h < Terrain.WATER_Y + 0.4f) return false
        if (World.distToPath(x, z) < 1.5f + clearance * 0.4f) return false
        if (World.inField(x, z, 2.0f)) return false
        if (Terrain.onBridge(x, z)) return false
        for (s in World.solids) {
            if (x > s.x0 - 3f && x < s.x1 + 3f && z > s.z0 - 3f && z < s.z1 + 3f) return false
        }
        for (p in World.props) {
            if (abs(p.x - x) < 2.0f && abs(p.z - z) < 2.0f) return false
        }
        for (t in World.trees) {
            if (abs(t.x - x) < 2.2f && abs(t.z - z) < 2.2f) return false
        }
        if (Terrain.onBridge(x, z)) return false
        for (f in World.forage) {
            if (abs(f.x - x) < 1.2f && abs(f.z - z) < 1.2f) return false
        }
        // leave the fire circle, the bench and the front yard open
        if (abs(x - World.FIRE_X) < 3f && abs(z - World.FIRE_Z) < 3f) return false
        return true
    }

    /** Which tree grows here: pines up high, blossom by the water, oak between. */
    private fun pickSpecies(x: Float, z: Float, seed: Int): Int {
        val h = Terrain.height(x, z)
        val roll = U.hash(seed * 149 + 11)
        val nearWater = Terrain.sandiness(x, z) > 0.25f
        if (nearWater && roll < 0.35f) return 3
        if (h > 3.5f) return if (roll < 0.78f) 0 else 2
        return when {
            roll < 0.34f -> 0
            roll < 0.74f -> 1
            roll < 0.90f -> 2
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
            // offset perpendicular to the log's axis
            val px = -sin(ang); val pz = cos(ang)
            val v = b.vertexCount
            b.vertex(x - dx + px * o0, y + y0, z - dz + pz * o0, px * o0, y0, pz * o0, 0f, 0f)
            b.vertex(x + dx + px * o0, y + y0, z + dz + pz * o0, px * o0, y0, pz * o0, len, 0f)
            b.vertex(x + dx + px * o1, y + y1, z + dz + pz * o1, px * o1, y1, pz * o1, len, r * 2f)
            b.vertex(x - dx + px * o1, y + y1, z - dz + pz * o1, px * o1, y1, pz * o1, 0f, r * 2f)
            b.tri(v, v + 1, v + 2); b.tri(v, v + 2, v + 3)
        }
    }

    /** A sprite lying flat on the ground or the water — lily pads, puddles. */
    private fun flatSprite(b: MeshBuilder, x: Float, y: Float, z: Float, r: Float, ang: Float, u0: Float, v0: Float) {
        val c = cos(ang) * r
        val s = sin(ang) * r
        val v = b.vertexCount
        b.vertex(x - c + s, y, z - s - c, 0f, 1f, 0f, u0, v0 + 0.5f)
        b.vertex(x + c + s, y, z + s - c, 0f, 1f, 0f, u0 + 0.5f, v0 + 0.5f)
        b.vertex(x + c - s, y, z + s + c, 0f, 1f, 0f, u0 + 0.5f, v0)
        b.vertex(x - c - s, y, z - s + c, 0f, 1f, 0f, u0, v0)
        b.tri(v, v + 2, v + 1); b.tri(v, v + 3, v + 2)
    }

    // ------------------------------------------------------------- species

    private fun pine(bark: MeshBuilder, leaf: MeshBuilder, x: Float, y: Float, z: Float, s: Float, seed: Int) {
        val tone = 0.86f + U.hash(seed * 151) * 0.28f
        bark.color(0.74f, 0.66f, 0.60f, 0f)
        bark.cylinder(x, y - 0.1f, z, 0.20f * s, 0.13f * s, 1.5f * s, 6, 1f)
        leaf.color(0.30f * tone, 0.52f * tone, 0.34f * tone, 0.03f)
        val tiers = 4
        for (k in 0 until tiers) {
            val cy = y + (0.8f + k * 0.95f) * s
            val cr = (1.5f - k * 0.28f) * s
            val chh = (1.7f - k * 0.16f) * s
            leaf.cone(x, cy, z, cr, chh, 8, 0.9f)
        }
    }

    private fun oak(bark: MeshBuilder, leaf: MeshBuilder, x: Float, y: Float, z: Float, s: Float, seed: Int) {
        val tone = 0.88f + U.hash(seed * 157) * 0.3f
        val autumn = U.hash(seed * 163) < 0.16f
        bark.color(0.80f, 0.72f, 0.62f, 0f)
        bark.cylinder(x, y - 0.1f, z, 0.24f * s, 0.17f * s, 1.9f * s, 6, 1f)
        // a couple of limbs, so the canopy has something holding it up
        bark.cylinder(x, y + 1.4f * s, z, 0.09f * s, 0.06f * s, 0.9f * s, 5, 1f)
        if (autumn) leaf.color(0.98f * tone, 0.62f * tone, 0.26f * tone, 0.055f)
        else leaf.color(0.46f * tone, 0.74f * tone, 0.34f * tone, 0.055f)
        leaf.blob(x, y + 2.9f * s, z, 1.55f * s, 4, 8, 0.8f)
        leaf.blob(x - 1.0f * s, y + 2.35f * s, z + 0.45f * s, 0.95f * s, 3, 7, 0.8f)
        leaf.blob(x + 1.05f * s, y + 2.5f * s, z - 0.4f * s, 0.9f * s, 3, 7, 0.8f)
        leaf.blob(x + 0.2f * s, y + 3.7f * s, z + 0.2f * s, 0.8f * s, 3, 7, 0.8f)
    }

    private fun birch(bark: MeshBuilder, leaf: MeshBuilder, x: Float, y: Float, z: Float, s: Float, seed: Int) {
        val tone = 0.9f + U.hash(seed * 167) * 0.26f
        // pale tint on the shared bark tile: birch without a second draw call
        bark.color(1.34f, 1.28f, 1.18f, 0f)
        bark.cylinder(x, y - 0.1f, z, 0.14f * s, 0.10f * s, 2.4f * s, 6, 1f)
        leaf.color(0.62f * tone, 0.84f * tone, 0.42f * tone, 0.075f)
        leaf.blob(x, y + 3.0f * s, z, 1.05f * s, 4, 7, 0.9f)
        leaf.blob(x - 0.55f * s, y + 2.5f * s, z + 0.3f * s, 0.72f * s, 3, 6, 0.9f)
        leaf.blob(x + 0.5f * s, y + 3.5f * s, z - 0.25f * s, 0.66f * s, 3, 6, 0.9f)
    }

    private fun blossom(bark: MeshBuilder, leaf: MeshBuilder, x: Float, y: Float, z: Float, s: Float, seed: Int) {
        val pink = U.hash(seed * 173) < 0.6f
        bark.color(0.70f, 0.60f, 0.56f, 0f)
        bark.cylinder(x, y - 0.1f, z, 0.19f * s, 0.13f * s, 1.5f * s, 6, 1f)
        bark.cylinder(x + 0.25f * s, y + 1.2f * s, z, 0.08f * s, 0.05f * s, 0.8f * s, 5, 1f)
        if (pink) leaf.color(1.15f, 0.72f, 0.82f, 0.07f)
        else leaf.color(1.12f, 1.02f, 0.86f, 0.07f)
        leaf.blob(x, y + 2.3f * s, z, 1.25f * s, 4, 8, 0.85f)
        leaf.blob(x - 0.85f * s, y + 1.95f * s, z + 0.35f * s, 0.8f * s, 3, 7, 0.85f)
        leaf.blob(x + 0.8f * s, y + 2.1f * s, z - 0.3f * s, 0.72f * s, 3, 7, 0.85f)
    }
}
