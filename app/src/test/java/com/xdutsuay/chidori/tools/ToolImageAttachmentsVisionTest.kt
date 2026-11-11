package com.xdutsuay.chidori.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 — honest when blind: without vision, screenshot results say the model
 * cannot see them, and every cos_gui result in the batch is marked unverified
 * (also when the batch has no screenshot). With vision, screenshots attach and
 * cos_gui results carry no unverified marker.
 */
class ToolImageAttachmentsVisionTest {

    private val images = ToolImageAttachments()
    private val pngBytes = FakeDesktopBridge.pngHeader(64, 48)

    /** A cos_screenshot result whose content carries a live image_id. */
    private fun screenshotResults(): String {
        val imageId = images.put(pngBytes)
        return JSONArray()
            .put(
                JSONObject()
                    .put("id", "call_shot")
                    .put("name", "cos_screenshot")
                    .put(
                        "content",
                        JSONObject().put("image_id", imageId).put("exit_code", 0).toString(),
                    ),
            )
            .toString()
    }

    private fun batchWithGui(): String {
        val shot = JSONArray(screenshotResults())
        shot.put(
            JSONObject()
                .put("id", "call_gui")
                .put("name", "cos_gui")
                .put("content", JSONObject().put("exit_code", 0).put("action", "click").toString()),
        )
        return shot.toString()
    }

    private fun entryAt(resultsJson: String, index: Int): JSONObject {
        val content = JSONObject(JSONArray(resultsJson).getJSONObject(index).getString("content"))
        return content
    }

    @Test
    fun `no vision - screenshot entry image is the cannot-see note`() {
        val prepared = images.prepare(screenshotResults(), vision = false)
        val content = entryAt(prepared.resultsJson, 0)
        assertEquals(ToolImageAttachments.NO_VISION_NOTE, content.optString("image"))
        val note = ToolImageAttachments.NO_VISION_NOTE.lowercase()
        assertTrue("note says the model cannot see", note.contains("cannot see"))
        assertTrue("note says not to describe the screen", note.contains("describe") || note.contains("guess"))
        // Nothing is staged for the model without vision.
        assertEquals(null, prepared.image)
        assertEquals(null, prepared.attachedToolCallId)
    }

    @Test
    fun `no vision - cos_gui entries in the batch get an unverified note`() {
        val prepared = images.prepare(batchWithGui(), vision = false)
        val gui = entryAt(prepared.resultsJson, 1)
        assertTrue(gui.optString("unverified").isNotBlank())
        // The screenshot entry is noted, the gui entry is not marked as an image.
        assertEquals(ToolImageAttachments.NO_VISION_NOTE, entryAt(prepared.resultsJson, 0).optString("image"))
        assertFalse(gui.has("image"))
    }

    @Test
    fun `no vision - cos_gui is marked unverified even with no screenshot in the batch`() {
        val results = JSONArray()
            .put(
                JSONObject()
                    .put("id", "call_gui")
                    .put("name", "cos_gui")
                    .put("content", JSONObject().put("exit_code", 0).toString()),
            )
            .toString()
        val prepared = images.prepare(results, vision = false)
        val gui = entryAt(prepared.resultsJson, 0)
        assertTrue("unverified note present", gui.optString("unverified").isNotBlank())
        assertEquals(null, prepared.image)
    }

    @Test
    fun `vision - cos_gui entries carry no unverified field and the screenshot attaches`() {
        val prepared = images.prepare(batchWithGui(), vision = true)
        val gui = entryAt(prepared.resultsJson, 1)
        assertFalse("no unverified marker with vision", gui.has("unverified"))
        val shot = entryAt(prepared.resultsJson, 0)
        assertEquals("attached", shot.optString("image"))
        assertNotNull(prepared.image)
        assertTrue(pngBytes.contentEquals(prepared.image!!))
        assertEquals("call_shot", prepared.attachedToolCallId)
    }
}
