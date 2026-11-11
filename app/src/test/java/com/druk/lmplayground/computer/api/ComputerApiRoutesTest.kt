package com.druk.lmplayground.computer.api

import com.druk.lmplayground.computer.desktop.DesktopState
import com.druk.lmplayground.computer.desktop.ImageFormat
import com.druk.lmplayground.tools.FakeDesktopBridge
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Spec tests for [ComputerApiRoutes] (slice 1): pure routing, no sockets.
 * JSON bodies are parsed with org.json (Robolectric provides it) so assertions stay
 * structural instead of depending on serialization whitespace.
 */
@RunWith(RobolectricTestRunner::class)
class ComputerApiRoutesTest {

    private val token = "secret-token"
    private val bridge = FakeDesktopBridge(display = ":1")
    private lateinit var routes: ComputerApiRoutes

    @Before
    fun setUp() {
        routes = ComputerApiRoutes(token) { bridge }
    }

    private fun handle(
        target: String,
        authorization: String? = "Bearer $token",
        method: String = "GET",
        routesOverride: ComputerApiRoutes? = null,
    ): ApiResponse = kotlinx.coroutines.runBlocking {
        (routesOverride ?: routes).handle(ApiRequest(method, target, authorization))
    }

    private fun state(): ApiResponse = handle(ComputerApiRoutes.STATE_PATH)

    private fun screenshot(query: String = ""): ApiResponse =
        handle(ComputerApiRoutes.SCREENSHOT_PATH + query)

    private fun jsonError(r: ApiResponse, expected: String) {
        assertEquals(ComputerApiRoutes.JSON, r.contentType)
        assertEquals(expected, JSONObject(r.text()).getString("error"))
    }

    /** True when any string value, at any depth of [node], contains [needle]. */
    private fun anyStringValueContains(node: Any?, needle: String): Boolean = when (node) {
        is JSONObject -> node.keys().asSequence().any { anyStringValueContains(node.get(it), needle) }
        is org.json.JSONArray -> (0 until node.length()).any { anyStringValueContains(node.get(it), needle) }
        is String -> node.contains(needle)
        else -> false
    }

    // ---- auth ----

    @Test
    fun `missing authorization is 401 unauthorized`() = runTest {
        val r = handle(ComputerApiRoutes.STATE_PATH, authorization = null)
        assertEquals(401, r.status)
        jsonError(r, "unauthorized")
    }

    @Test
    fun `wrong token is 401`() = runTest {
        val r = handle(ComputerApiRoutes.STATE_PATH, authorization = "Bearer wrong-token")
        assertEquals(401, r.status)
        jsonError(r, "unauthorized")
    }

    @Test
    fun `wrong scheme is 401`() = runTest {
        val r = handle(ComputerApiRoutes.STATE_PATH, authorization = "Basic $token")
        assertEquals(401, r.status)
        jsonError(r, "unauthorized")
    }

    @Test
    fun `lowercase bearer scheme is accepted`() {
        val r = handle(ComputerApiRoutes.STATE_PATH, authorization = "bearer $token")
        assertEquals(200, r.status)
    }

    @Test
    fun `empty configured token rejects every request`() = runTest {
        val empty = ComputerApiRoutes("") { bridge }
        assertEquals(
            401,
            handle(ComputerApiRoutes.STATE_PATH, authorization = "Bearer ", routesOverride = empty).status,
        )
        assertEquals(
            401,
            handle(ComputerApiRoutes.STATE_PATH, authorization = "Bearer $token", routesOverride = empty).status,
        )
        assertEquals(
            401,
            handle(ComputerApiRoutes.STATE_PATH, authorization = null, routesOverride = empty).status,
        )
    }

    @Test
    fun `auth is checked before routing so unknown paths do not leak 404`() = runTest {
        val r = handle("/v1/computer/does-not-exist", authorization = null)
        assertEquals(401, r.status)
        jsonError(r, "unauthorized")
    }

    // ---- routing ----

    @Test
    fun `unknown path with a valid token is 404 not found`() = runTest {
        val r = handle("/v1/computer/does-not-exist")
        assertEquals(404, r.status)
        jsonError(r, "not found")
    }

    @Test
    fun `POST on the state path is 405 method not allowed`() = runTest {
        val r = handle(ComputerApiRoutes.STATE_PATH, method = "POST")
        assertEquals(405, r.status)
        jsonError(r, "method not allowed")
    }

    @Test
    fun `POST on the screenshot path is 405 method not allowed`() = runTest {
        val r = handle(ComputerApiRoutes.SCREENSHOT_PATH, method = "POST")
        assertEquals(405, r.status)
        jsonError(r, "method not allowed")
    }

    // ---- state JSON ----

    @Test
    fun `state with no bridge is unavailable and read only`() = runTest {
        val nullBridgeRoutes = ComputerApiRoutes(token) { null }
        val r = handle(ComputerApiRoutes.STATE_PATH, routesOverride = nullBridgeRoutes)
        assertEquals(200, r.status)
        assertEquals(ComputerApiRoutes.JSON, r.contentType)
        val json = JSONObject(r.text())
        assertEquals("unavailable", json.getString("desktop"))
        assertTrue(json.getBoolean("read_only"))
        assertFalse("unavailable state must not carry running geometry", json.has("width"))
        assertFalse(json.has("height"))
        assertFalse(json.has("depth"))
    }

    @Test
    fun `state when stopped`() = runTest {
        bridge.stopDesktop()
        val json = JSONObject(state().text())
        assertEquals("stopped", json.getString("desktop"))
        assertTrue(json.getBoolean("read_only"))
        assertFalse(json.has("width"))
        assertFalse(json.has("height"))
        assertFalse(json.has("depth"))
    }

    @Test
    fun `state when running carries geometry but never display or file paths`() = runTest {
        val r = state()
        assertEquals(200, r.status)
        val json = JSONObject(r.text())
        assertEquals("running", json.getString("desktop"))
        assertTrue(json.getBoolean("read_only"))
        assertEquals(1280, json.getInt("width"))
        assertEquals(720, json.getInt("height"))
        assertEquals(24, json.getInt("depth"))
        // FakeDesktopBridge.Running carries display=":1", xauthority and an Xvfb path:
        // none of those internals may leak into the response. The display is checked as a
        // JSON *value* (a raw ":1" substring would false-match inside "width":1280);
        // the file paths are unambiguous in any encoding, so the raw body is checked too.
        val body = r.text()
        val jsonAll = JSONObject(body)
        assertFalse("a 'display' key leaked: $body", jsonAll.has("display"))
        assertFalse(
            "display name leaked as a value: $body",
            anyStringValueContains(jsonAll, ":1"),
        )
        assertFalse("xauthority path leaked: $body", body.contains("/root/.Xauthority"))
        assertFalse("framebuffer path leaked: $body", body.contains("Xvfb_screen0"))
        assertFalse("host path leaked: $body", body.contains("/nonexistent"))
    }

    @Test
    fun `state when error carries the message and no geometry`() = runTest {
        bridge.state.value = DesktopState.Error("desktop exploded", null)
        val json = JSONObject(state().text())
        assertEquals("error", json.getString("desktop"))
        assertEquals("desktop exploded", json.getString("message"))
        assertFalse(json.has("width"))
        assertFalse(json.has("height"))
        assertFalse(json.has("depth"))
    }

    // ---- screenshot ----

    @Test
    fun `screenshot defaults to png at scale 1 and passes the bytes through`() = runTest {
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        bridge.pngOverride = { _, _ -> bytes }
        val r = screenshot()
        assertEquals(200, r.status)
        assertEquals(ComputerApiRoutes.PNG, r.contentType)
        assertArrayEquals(bytes, r.body)
        assertEquals(listOf(ImageFormat.PNG to 1f), bridge.screenshots)
    }

    @Test
    fun `screenshot format=jpeg returns the jpeg content type and forwards JPEG`() = runTest {
        val bytes = byteArrayOf(0x0A, 0x0B)
        bridge.pngOverride = { _, _ -> bytes }
        val r = screenshot("?format=jpeg")
        assertEquals(200, r.status)
        assertEquals(ComputerApiRoutes.JPEG, r.contentType)
        assertArrayEquals(bytes, r.body)
        assertEquals(listOf(ImageFormat.JPEG to 1f), bridge.screenshots)
    }

    @Test
    fun `screenshot format is case-insensitive`() = runTest {
        bridge.pngOverride = { _, _ -> ByteArray(4) }
        val r = screenshot("?format=JPEG")
        assertEquals(200, r.status)
        assertEquals(ComputerApiRoutes.JPEG, r.contentType)
        assertEquals(listOf(ImageFormat.JPEG to 1f), bridge.screenshots)
    }

    @Test
    fun `screenshot scale is forwarded to the bridge`() = runTest {
        bridge.pngOverride = { _, _ -> ByteArray(4) }
        val r = screenshot("?scale=0.5")
        assertEquals(200, r.status)
        assertEquals(listOf(ImageFormat.PNG to 0.5f), bridge.screenshots)
    }

    @Test
    fun `screenshot accepts format and scale together`() = runTest {
        bridge.pngOverride = { _, _ -> ByteArray(4) }
        val r = screenshot("?format=jpeg&scale=0.5")
        assertEquals(200, r.status)
        assertEquals(ComputerApiRoutes.JPEG, r.contentType)
        assertEquals(listOf(ImageFormat.JPEG to 0.5f), bridge.screenshots)
    }

    @Test
    fun `screenshot scale below the minimum is 400`() {
        assertEquals(400, screenshot("?scale=0.05").status)
    }

    @Test
    fun `screenshot scale above the maximum is 400`() {
        assertEquals(400, screenshot("?scale=1.5").status)
    }

    @Test
    fun `screenshot scale that is not a number is 400`() {
        assertEquals(400, screenshot("?scale=abc").status)
    }

    @Test
    fun `screenshot format other than png or jpeg is 400`() {
        assertEquals(400, screenshot("?format=gif").status)
    }

    @Test
    fun `screenshot when the desktop is not running is 409`() = runTest {
        bridge.stopDesktop()
        val r = screenshot()
        assertEquals(409, r.status)
        jsonError(r, "desktop not running")
    }

    @Test
    fun `screenshot with no bridge is 409`() = runTest {
        val nullBridgeRoutes = ComputerApiRoutes(token) { null }
        val r = handle(ComputerApiRoutes.SCREENSHOT_PATH, routesOverride = nullBridgeRoutes)
        assertEquals(409, r.status)
        jsonError(r, "desktop not running")
    }

    @Test
    fun `screenshot failure is 503 and does not leak the exception message`() = runTest {
        bridge.pngOverride = { _, _ -> throw IllegalStateException("SECRET-ROOT-CAUSE") }
        val r = screenshot()
        assertEquals(503, r.status)
        jsonError(r, "screenshot failed")
        val body = r.text()
        assertFalse("exception message leaked: $body", body.contains("SECRET-ROOT-CAUSE"))
    }
}
