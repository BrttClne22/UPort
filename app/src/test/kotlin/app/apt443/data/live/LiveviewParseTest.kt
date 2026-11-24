package app.apt443.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveviewParseTest {
    @Test
    fun parsesSlotsCamerasAndCycling() {
        val body = """
            [
              {"id":"lv1","modelKey":"liveview","name":"Front","isDefault":true,"isGlobal":true,
               "owner":"u1","layout":4,
               "slots":[
                 {"cameras":["c1"],"cycleMode":"time","cycleInterval":10},
                 {"cameras":["c2","c3"],"cycleMode":"time","cycleInterval":5},
                 {"cameras":[],"cycleMode":"time","cycleInterval":10},
                 {"cameras":["c4"],"cycleMode":"motion","cycleInterval":1}
               ]},
              {"name":"no id","slots":[]}
            ]
        """.trimIndent()
        val views = parseLiveviews(body)
        assertEquals(1, views.size)
        val view = views.single()
        assertEquals("Front", view.name)
        assertTrue(view.isDefault)
        assertEquals(4, view.slots.size)
        assertEquals(listOf("c2", "c3"), view.slots[1].cameraIds)
        assertEquals(5, view.slots[1].cycleIntervalSec)
        assertTrue(view.slots[2].cameraIds.isEmpty())
        assertEquals(3, view.slots[3].cycleIntervalSec)
    }

    @Test
    fun acceptsWrappedResponse() {
        val body = """{"data":[{"id":"lv2","name":"Yard","slots":[{"cameras":[{"id":"c9"}]}]}]}"""
        assertEquals(listOf("c9"), parseLiveviews(body).single().slots.single().cameraIds)
    }
}
