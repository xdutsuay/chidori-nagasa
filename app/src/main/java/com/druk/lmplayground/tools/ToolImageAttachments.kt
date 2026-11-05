package com.druk.lmplayground.tools

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Hand-off of tool-produced images (from `cos_screenshot`) to the generation loop.
 *
 * Tools return text JSON to the model; the image bytes never go through JSON. A
 * tool [put]s the bytes and returns `"image_id"`. After the tool round,
 * [prepare] decides which single image is attached to the tool results sent back
 * to the model (the native session supports one image per submit, like user
 * attachments), marks that result with `"attach_image": true` and returns the
 * bytes to stage with `LlamaGenerationSession.setImageData` before
 * `submitToolResults`.
 *
 * Bounded: keeps the newest [capacity] images.
 */
class ToolImageAttachments(private val capacity: Int = 2) {

    class Image(val id: String, val bytes: ByteArray, val mimeType: String)

    private val images = LinkedHashMap<String, Image>()

    @Synchronized
    fun put(bytes: ByteArray, mimeType: String = "image/png"): String {
        val id = "img_" + UUID.randomUUID().toString().replace("-", "").take(12)
        images[id] = Image(id, bytes, mimeType)
        while (images.size > capacity) images.remove(images.keys.first())
        return id
    }

    @Synchronized
    fun get(id: String): Image? = images[id]

    @Synchronized
    fun take(id: String): Image? = images.remove(id)

    @Synchronized
    fun clear() = images.clear()

    @Synchronized
    fun size(): Int = images.size

    /**
     * @property resultsJson tool results to submit (entries possibly rewritten).
     * @property image bytes to stage for the native session, or null.
     */
    data class Prepared(val resultsJson: String, val image: ByteArray?, val attachedToolCallId: String?)

    /**
     * Rewrites [resultsJson] (`[{id,name,content}]`): the last result whose content
     * has a known `image_id` gets `"attach_image": true` and its content gains
     * `"image":"attached"` when [vision] is true; otherwise content notes that the
     * model cannot see images. Earlier images in the same batch are noted as
     * superseded. Used images are removed from the store.
     */
    fun prepare(resultsJson: String, vision: Boolean): Prepared {
        val arr = runCatching { JSONArray(resultsJson) }.getOrNull()
            ?: return Prepared(resultsJson, null, null)
        val withImage = mutableListOf<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val content = runCatching { JSONObject(entry.optString("content")) }.getOrNull() ?: continue
            val id = content.optString("image_id", "")
            if (id.isNotEmpty() && get(id) != null) withImage += i to id
        }
        if (withImage.isEmpty()) return Prepared(resultsJson, null, null)
        var staged: ByteArray? = null
        var attachedId: String? = null
        withImage.forEachIndexed { n, (i, imageId) ->
            val entry = arr.getJSONObject(i)
            val content = JSONObject(entry.getString("content"))
            val image = take(imageId)
            val isLast = n == withImage.lastIndex
            when {
                isLast && vision && image != null -> {
                    content.put("image", "attached")
                    entry.put("attach_image", true)
                    staged = image.bytes
                    attachedId = entry.optString("id").ifEmpty { null }
                }
                isLast -> content.put(
                    "image",
                    "not shown: the current model has no vision module; use a vision model to see screenshots",
                )
                else -> content.put("image", "superseded by a later screenshot in this round")
            }
            entry.put("content", content.toString())
        }
        return Prepared(arr.toString(), staged, attachedId)
    }
}
