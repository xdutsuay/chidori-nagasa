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
    private val control = FakeComputerControl()
    private val controlRoutes = ComputerApiRoutes(token, control) { bridge }
    private val pairing = PairingRegistry(
        nowMs = { 1_000L },
        codeSource = { "123456" },
        tokenSource = { "paired-tok" },
        idSource = { "client-1" },
    )
    private val pairingRoutes = ComputerApiRoutes(token, control, pairing) { bridge }
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

    /** POST with a body against [routes] (the control-enabled instance by default). */
    private fun post(
        target: String,
        body: String,
        authorization: String? = "Bearer $token",
        routesOverride: ComputerApiRoutes? = null,
    ): ApiResponse = kotlinx.coroutines.runBlocking {
        (routesOverride ?: controlRoutes).handle(
            ApiRequest(method = "POST", target = target, authorization = authorization, body = body),
        )
    }

    /** POST /v1/pair with [remote] as the client's address (pairing needs no Authorization). */
    private fun pair(body: String, remote: String = "192.168.1.5"): ApiResponse =
        kotlinx.coroutines.runBlocking {
            pairingRoutes.handle(
                ApiRequest(method = "POST", target = ComputerApiRoutes.PAIR_PATH, authorization = null, body = body, remote = remote),
            )
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

    // ---- slice 2: read_only flag ----

    @Test
    fun `state read_only is false when a control is configured`() {
        val r = kotlinx.coroutines.runBlocking {
            controlRoutes.handle(ApiRequest("GET", ComputerApiRoutes.STATE_PATH, "Bearer $token"))
        }
        assertEquals(200, r.status)
        assertEquals(false, JSONObject(r.text()).getBoolean("read_only"))
    }

    // ---- slice 2: control not enabled ----

    @Test
    fun `exec without a control is 403 control not enabled`() {
        val r = post(ComputerApiRoutes.EXEC_PATH, """{"command":"ls"}""", routesOverride = routes)
        assertEquals(403, r.status)
        jsonError(r, "control not enabled")
    }

    @Test
    fun `gui without a control is 403 control not enabled`() {
        val r = post(ComputerApiRoutes.GUI_PATH, """{"action":"tap"}""", routesOverride = routes)
        assertEquals(403, r.status)
        jsonError(r, "control not enabled")
    }

    // ---- slice 2: body validation ----

    @Test
    fun `exec with a non-object body is 400 and never asks`() {
        val r = post(ComputerApiRoutes.EXEC_PATH, "not json at all")
        assertEquals(400, r.status)
        jsonError(r, "body must be a JSON object")
        assertTrue(control.approvals.isEmpty())
        assertTrue(control.execCalls.isEmpty())
    }

    @Test
    fun `exec with a JSON array body is 400`() {
        val r = post(ComputerApiRoutes.EXEC_PATH, """[1,2,3]""")
        assertEquals(400, r.status)
        jsonError(r, "body must be a JSON object")
        assertTrue(control.approvals.isEmpty())
    }

    @Test
    fun `exec with a missing command is 400`() {
        val r = post(ComputerApiRoutes.EXEC_PATH, """{"other":1}""")
        assertEquals(400, r.status)
        assertTrue(control.approvals.isEmpty())
        assertTrue(control.execCalls.isEmpty())
    }

    @Test
    fun `exec with a blank command is 400`() {
        val r = post(ComputerApiRoutes.EXEC_PATH, """{"command":"   "}""")
        assertEquals(400, r.status)
        assertTrue(control.approvals.isEmpty())
        assertTrue(control.execCalls.isEmpty())
    }

    @Test
    fun `gui with a missing action is 400 and never asks`() {
        val r = post(ComputerApiRoutes.GUI_PATH, """{"x":1}""")
        assertEquals(400, r.status)
        assertTrue(control.approvals.isEmpty())
        assertTrue(control.guiCalls.isEmpty())
    }

    @Test
    fun `gui with a blank action is 400`() {
        val r = post(ComputerApiRoutes.GUI_PATH, """{"action":""}""")
        assertEquals(400, r.status)
        assertTrue(control.approvals.isEmpty())
        assertTrue(control.guiCalls.isEmpty())
    }

    // ---- slice 2: exec ----

    @Test
    fun `exec asks with action cos_exec and the command as summary`() {
        val r = post(ComputerApiRoutes.EXEC_PATH, """{"command":"ls -la"}""")
        assertEquals(200, r.status)
        assertEquals(listOf(FakeComputerControl.ApprovalCall("cos_exec", "ls -la")), control.approvals)
    }

    @Test
    fun `exec passes exactly the command and drops other fields`() {
        val r = post(ComputerApiRoutes.EXEC_PATH, """{"command":"ls -la","extra":"dropped","n":7}""")
        assertEquals(200, r.status)
        val forwarded = JSONObject(control.execCalls.single())
        assertEquals("ls -la", forwarded.getString("command"))
        assertEquals("exec must receive exactly one field", 1, forwarded.length())
        assertFalse(forwarded.has("extra"))
        assertFalse(forwarded.has("n"))
    }

    @Test
    fun `exec returns the control's JSON result as-is`() {
        control.execResultJson = """{"exit":3,"stdout":"hi"}"""
        val r = post(ComputerApiRoutes.EXEC_PATH, """{"command":"ls"}""")
        assertEquals(200, r.status)
        assertEquals("""{"exit":3,"stdout":"hi"}""", r.text())
    }

    @Test
    fun `exec denied on the phone is 403 and nothing runs`() {
        control.approved = false
        val r = post(ComputerApiRoutes.EXEC_PATH, """{"command":"rm -rf /"}""")
        assertEquals(403, r.status)
        jsonError(r, "denied on phone")
        assertEquals("deny must be recorded", 1, control.approvals.size)
        assertTrue("denied exec must not run", control.execCalls.isEmpty())
    }

    // ---- slice 2: gui ----

    @Test
    fun `gui asks with action cos_gui and a non-blank summary`() {
        val r = post(ComputerApiRoutes.GUI_PATH, """{"action":"tap","x":10}""")
        assertEquals(200, r.status)
        val call = control.approvals.single()
        assertEquals("cos_gui", call.action)
        assertTrue("summary must be non-blank", call.summary.isNotBlank())
    }

    @Test
    fun `gui forwards the body unchanged`() {
        val body = """{"action":"swipe","x":10,"y":20}"""
        val r = post(ComputerApiRoutes.GUI_PATH, body)
        assertEquals(200, r.status)
        assertEquals("gui must receive the body unchanged", body, control.guiCalls.single())
    }

    @Test
    fun `gui returns the control's JSON result as-is`() {
        control.guiResultJson = """{"done":true}"""
        val r = post(ComputerApiRoutes.GUI_PATH, """{"action":"tap"}""")
        assertEquals(200, r.status)
        assertEquals("""{"done":true}""", r.text())
    }

    @Test
    fun `gui denied on the phone is 403 and gui never runs`() {
        control.approved = false
        val r = post(ComputerApiRoutes.GUI_PATH, """{"action":"tap"}""")
        assertEquals(403, r.status)
        jsonError(r, "denied on phone")
        assertEquals(1, control.approvals.size)
        assertTrue(control.guiCalls.isEmpty())
    }

    @Test
    fun `gui when the desktop is not running is 409 without asking`() {
        bridge.stopDesktop()
        val r = post(ComputerApiRoutes.GUI_PATH, """{"action":"tap"}""")
        assertEquals(409, r.status)
        jsonError(r, "desktop not running")
        assertTrue("409 must not ask the phone", control.approvals.isEmpty())
        assertTrue(control.guiCalls.isEmpty())
    }

    // ---- slice 2: methods and auth ordering ----

    @Test
    fun `GET on the exec path is 405`() {
        val r = kotlinx.coroutines.runBlocking {
            controlRoutes.handle(ApiRequest("GET", ComputerApiRoutes.EXEC_PATH, "Bearer $token"))
        }
        assertEquals(405, r.status)
    }

    @Test
    fun `GET on the gui path is 405`() {
        val r = kotlinx.coroutines.runBlocking {
            controlRoutes.handle(ApiRequest("GET", ComputerApiRoutes.GUI_PATH, "Bearer $token"))
        }
        assertEquals(405, r.status)
    }

    @Test
    fun `POST on the state path is 405 even with a control`() {
        assertEquals(405, post(ComputerApiRoutes.STATE_PATH, "{}").status)
    }

    @Test
    fun `POST on the screenshot path is 405 even with a control`() {
        assertEquals(405, post(ComputerApiRoutes.SCREENSHOT_PATH, "{}").status)
    }

    @Test
    fun `401 comes before everything on control paths`() {
        assertEquals(401, post(ComputerApiRoutes.EXEC_PATH, """{"command":"ls"}""", authorization = null).status)
        assertEquals(
            401,
            post(ComputerApiRoutes.GUI_PATH, """{"action":"tap"}""", authorization = "Bearer wrong").status,
        )
        assertTrue("401 must not reach the control", control.approvals.isEmpty())
        assertTrue(control.execCalls.isEmpty())
        assertTrue(control.guiCalls.isEmpty())
    }

    @Test
    fun `unknown POST paths still 404 with a valid token`() {
        assertEquals(404, post("/v1/computer/does-not-exist", "{}").status)
    }

    // ---- slice 3: pairing ----

    @Test
    fun `pair works without Authorization and returns token and client_id`() {
        pairing.issueCode()
        val r = pair("""{"code":"123456","name":" Pixel "}""")
        assertEquals(200, r.status)
        val json = JSONObject(r.text())
        assertEquals("paired-tok", json.getString("token"))
        assertEquals("client-1", json.getString("client_id"))
        // redeem must have received ApiRequest.remote as the client address
        val client = pairing.clients.value.single()
        assertEquals("192.168.1.5", client.address)
        assertEquals("Pixel", client.name)
    }

    @Test
    fun `pair with a wrong code is 403 and registers nothing`() {
        val r = pair("""{"code":"999999"}""")
        assertEquals(403, r.status)
        jsonError(r, "invalid or expired code")
        assertTrue(pairing.clients.value.isEmpty())
    }

    @Test
    fun `GET pair without Authorization is 405`() {
        val r = kotlinx.coroutines.runBlocking {
            pairingRoutes.handle(ApiRequest("GET", ComputerApiRoutes.PAIR_PATH, authorization = null))
        }
        assertEquals(405, r.status)
    }

    @Test
    fun `pair with a non-object body is 400 and never registers`() {
        assertEquals(400, pair("not json at all").status)
        assertEquals(400, pair("""[1,2]""").status)
        assertTrue(pairing.clients.value.isEmpty())
    }

    @Test
    fun `pair with a missing or non-string code is 400`() {
        assertEquals(400, pair("""{}""").status)
        assertEquals(400, pair("""{"code":123456}""").status)
        assertTrue(pairing.clients.value.isEmpty())
    }

    @Test
    fun `a paired token authorizes the state endpoint`() {
        pairing.issueCode()
        pair("""{"code":"123456"}""")
        val r = handle(ComputerApiRoutes.STATE_PATH, authorization = "Bearer paired-tok", routesOverride = pairingRoutes)
        assertEquals(200, r.status)
    }

    @Test
    fun `an unknown token is still 401 with pairing enabled`() {
        val r = handle(ComputerApiRoutes.STATE_PATH, authorization = "Bearer unknown-token", routesOverride = pairingRoutes)
        assertEquals(401, r.status)
    }

    @Test
    fun `the static token still authorizes when pairing is enabled`() {
        val r = handle(ComputerApiRoutes.STATE_PATH, routesOverride = pairingRoutes)
        assertEquals(200, r.status)
    }

    @Test
    fun `an empty static token with pairing accepts only paired tokens`() {
        val reg = PairingRegistry(
            nowMs = { 1_000L },
            codeSource = { "123456" },
            tokenSource = { "paired-tok" },
            idSource = { "client-1" },
        )
        val emptyRoutes = ComputerApiRoutes("", pairing = reg) { bridge }
        reg.issueCode()
        assertEquals(
            "no static token, so no Bearer can authenticate",
            401,
            kotlinx.coroutines.runBlocking {
                emptyRoutes.handle(ApiRequest("GET", ComputerApiRoutes.STATE_PATH, "Bearer anything"))
            }.status,
        )
        val pr = kotlinx.coroutines.runBlocking {
            emptyRoutes.handle(
                ApiRequest("POST", ComputerApiRoutes.PAIR_PATH, null, """{"code":"123456"}""", "192.168.1.5"),
            )
        }
        assertEquals(200, pr.status)
        val tok = JSONObject(pr.text()).getString("token")
        assertEquals(
            200,
            kotlinx.coroutines.runBlocking {
                emptyRoutes.handle(ApiRequest("GET", ComputerApiRoutes.STATE_PATH, "Bearer $tok"))
            }.status,
        )
    }

    @Test
    fun `with pairing null the pair path is an ordinary unknown path`() {
        assertEquals(401, post(ComputerApiRoutes.PAIR_PATH, """{"code":"1"}""", authorization = null).status)
        assertEquals(404, post(ComputerApiRoutes.PAIR_PATH, """{"code":"1"}""").status)
    }
}
