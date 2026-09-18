package dev.heynol.afterglow.visuals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {
    private val assets = File("src/main/assets")

    @Test
    fun catalogParsesAndReferencesExistingShaders() {
        val catalog = Catalog.parse(File(assets, "visuals.json").readText())
        assertTrue(catalog.visuals.size >= 24)
        for (v in catalog.visuals) {
            assertTrue("${v.id}: missing ${v.shader}", File(assets, "shaders/visuals/${v.shader}.frag").exists())
            v.composite?.let { assertTrue("${v.id}: missing $it", File(assets, "shaders/visuals/$it.frag").exists()) }
            for (p in v.params) assertTrue("${v.id}.${p.id} default", p.default in 0f..1f)
        }
        for (c in catalog.categories) assertTrue("empty category ${c.id}", catalog.visuals.any { it.category == c.id })
    }

    @Test
    fun paletteSamplingMatchesShaderCurve() {
        val catalog = Catalog.parse(File(assets, "visuals.json").readText())
        val p = catalog.palette("neon")
        val first = p.sample(0f)
        assertEquals(p.colors[0], first[0], 1e-4f)
        val wrapped = p.sample(1f)
        assertEquals(first[1], wrapped[1], 1e-4f)
    }

    @Test
    fun paramLabels() {
        val param = ParamDef("mirror", Text("Mirror", "Spiegel"), 1f, listOf(Text("Off", "Aus"), Text("On", "An")))
        assertEquals(1, param.steps)
        assertEquals("On", param.label(1f).let { if (it == "An") "On" else it })
    }
}
