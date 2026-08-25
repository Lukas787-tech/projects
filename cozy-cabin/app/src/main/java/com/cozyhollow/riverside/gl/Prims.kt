package com.cozyhollow.riverside.gl

/** Unit primitives that every object in the game is assembled from. */
class Prims {
    /** x,z in [-0.5,0.5], y in [0,1]. */
    val box: Mesh
    /** Same footprint but with a bottom face, for things seen from below. */
    val boxClosed: Mesh
    /** Radius 0.5, height 1, 8 sides. */
    val cyl: Mesh
    /** Radius 0.5 at base, height 1, 8 sides. */
    val cone: Mesh
    /** Radius 0.5 blob centred at origin. */
    val blob: Mesh
    /** Facing +Z, 1x1, origin at bottom centre. */
    val quad: Mesh
    /** Facing +Y, 1x1, centred. */
    val flat: Mesh
    /** Gable roof prism, 1x1x1, base at y=0, ridge running along X. */
    val roof: Mesh

    init {
        var b = MeshBuilder()
        b.box(0f, 0f, 0f, 1f, 1f, 1f, 1f, top = true, bottom = false)
        box = b.build()

        b = MeshBuilder()
        b.box(0f, 0f, 0f, 1f, 1f, 1f, 1f, top = true, bottom = true)
        boxClosed = b.build()

        b = MeshBuilder()
        b.cylinder(0f, 0f, 0f, 0.5f, 0.5f, 1f, 8, 1f, cap = true)
        cyl = b.build()

        b = MeshBuilder()
        b.cone(0f, 0f, 0f, 0.5f, 1f, 8, 1f)
        cone = b.build()

        b = MeshBuilder()
        b.blob(0f, 0f, 0f, 0.5f, 5, 8, 1f)
        blob = b.build()

        b = MeshBuilder()
        b.quad(
            -0.5f, 0f, 0f, 0.5f, 0f, 0f, 0.5f, 1f, 0f, -0.5f, 1f, 0f,
            0f, 0f, 1f, 1f, 1f
        )
        quad = b.build()

        b = MeshBuilder()
        b.quad(
            -0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f, 0.5f, 0f, -0.5f, -0.5f, 0f, -0.5f,
            0f, 1f, 0f, 1f, 1f
        )
        flat = b.build()

        b = MeshBuilder()
        b.roof(0f, 0f, 0f, 1f, 1f, 1f, 1f)
        roof = b.build()
    }

    fun release() {
        box.release(); boxClosed.release(); cyl.release()
        cone.release(); blob.release(); quad.release(); flat.release(); roof.release()
    }
}
