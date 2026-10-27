package com.druk.lmplayground.coordinator.transport

import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.ApplyEditsRequest
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.ProposedEdit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * KMA-288 (TEST AUTHOR RED — no production code yet; this file does not
 * compile until the implementer lands the surface below).
 *
 * == IMPLEMENTER CONTRACT (make these green, do not weaken assertions) ==
 *
 * Wire (desktop `lclreason` `internal/api/kma288_companion_approve.go`):
 *
 *   POST /runs/{run_id}/approve       {"workflow_run_id":"<wf run id>"}
 *     → 200|202 execution state JSON (treat both as success; do not throw)
 *   POST /runs/{run_id}/reject        {"workflow_run_id":"<wf run id>"}
 *     → 200 {"run_id":"...","status":"rejected"}
 *   POST /runs/{run_id}/edits/accept  ApplyEditsRequest
 *     → 200 {"applied":[...],"failed":[...]}
 *   POST /runs/{run_id}/edits/reject  ApplyEditsRequest (ack only)
 *     → 200 {"run_id":"...","status":"rejected"}
 *
 * Bearer required on all four (401 → IOException like cancelRun).
 *
 * 1. `CoordinatorApi` / `OkHttpCoordinatorApi` methods (via `authedRequest` +
 *    `onIo`):
 *
 *      suspend fun approveRun(instanceId, runId, workflowRunId)
 *      suspend fun rejectRun(instanceId, runId, workflowRunId)
 *      suspend fun acceptRunEdits(instanceId, runId, request): ApplyEditsResult
 *      suspend fun rejectRunEdits(instanceId, runId, request)
 *
 *    Models in `coordinator.model`:
 *      data class ProposedEdit(path, kind="write_file", after="", before="",
 *                              id=null, summary=null)
 *      data class ApplyEditsRequest(edits: List<ProposedEdit>, sessionId: String? = null)
 *      data class ApplyEditsResult(applied: List<String>, failed: List<String>)
 *
 *    Gate bodies MUST include `workflow_run_id` (required by desktop approve).
 *    Edits bodies match IDE ApplyEditsRequest: optional `session_id`, required
 *    `edits` array of ProposedEdit objects (`path`/`kind`/`after`/…).
 *
 * 2. `AgentRunSummary` gains optional `reason: String?`, `branch: String?`,
 *    and `pr: RunPR?` where
 *      data class RunPR(state: String, number: Int? = null, url: String? = null,
 *                       checksStatus: String? = null, checksUrl: String? = null)
 *    and `toAgentRunSummary` maps wire `reason`/`branch`/`pr` when present
 *    (omit → null). `listRuns` / `getRunDetail` must both pick these up.
 *
 * 3. ViewModel/UI (thin, not covered here): when a run is NEEDS_YOU, expose
 *    approve/reject gate + accept/reject edits actions; surface `pr` on the
 *    monitor row when non-null. Until desktop puts `workflow_run_id` on the
 *    summary, the UI may pass the companion `run_id` as `workflowRunId`.
 *
 * 4. Amend WIRE_CONTRACT.md with the four routes + optional `pr`/`reason`/
 *    `branch` on run summary.
 */
class ChidoriApproveApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: OkHttpCoordinatorApi
    private var instanceId: InstanceId = InstanceId("srv-1")
    private var currentToken: String? = "tok123"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = OkHttpCoordinatorApi(
            client = OkHttpClient(),
            authTokenProvider = { currentToken },
            endpointProvider = { _ -> server.hostName to server.port },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- gate approve ----------------------------------------------------

    @Test
    fun `approveRun posts workflow_run_id body with bearer auth`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"completed"}"""))

        api.approveRun(instanceId, "companion-run", "wf-run-9")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/runs/companion-run/approve", recorded.requestUrl?.encodedPath)
        assertEquals("Bearer tok123", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("wf-run-9", body.getString("workflow_run_id"))
    }

    @Test
    fun `approveRun treats HTTP 202 as success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"paused"}"""))

        api.approveRun(instanceId, "r1", "wf-1")
        // no throw
    }

    @Test(expected = IOException::class)
    fun `approveRun throws on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        api.approveRun(instanceId, "r1", "wf-1")
    }

    // ---- gate reject -----------------------------------------------------

    @Test
    fun `rejectRun posts workflow_run_id body with bearer auth`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"run_id":"companion-run","status":"rejected"}"""),
        )

        api.rejectRun(instanceId, "companion-run", "wf-run-9")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/runs/companion-run/reject", recorded.requestUrl?.encodedPath)
        assertEquals("Bearer tok123", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("wf-run-9", body.getString("workflow_run_id"))
    }

    @Test(expected = IOException::class)
    fun `rejectRun throws on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        api.rejectRun(instanceId, "r1", "wf-1")
    }

    // ---- edits accept / reject -------------------------------------------

    @Test
    fun `acceptRunEdits posts ApplyEditsRequest shape and parses applied failed`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"applied":["a.txt"],"failed":["b.txt: boom"]}"""),
        )

        val result = api.acceptRunEdits(
            instanceId,
            "run-edits",
            ApplyEditsRequest(
                sessionId = "sess-1",
                edits = listOf(
                    ProposedEdit(path = "a.txt", kind = "write_file", after = "hello"),
                    ProposedEdit(path = "b.txt", kind = "write_file", after = "nope"),
                ),
            ),
        )

        assertEquals(listOf("a.txt"), result.applied)
        assertEquals(listOf("b.txt: boom"), result.failed)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/runs/run-edits/edits/accept", recorded.requestUrl?.encodedPath)
        assertEquals("Bearer tok123", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("sess-1", body.getString("session_id"))
        assertEquals(2, body.getJSONArray("edits").length())
        assertEquals("a.txt", body.getJSONArray("edits").getJSONObject(0).getString("path"))
        assertEquals("hello", body.getJSONArray("edits").getJSONObject(0).getString("after"))
    }

    @Test
    fun `acceptRunEdits omits session_id when null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"applied":[],"failed":[]}"""))

        api.acceptRunEdits(
            instanceId,
            "run-edits",
            ApplyEditsRequest(edits = listOf(ProposedEdit(path = "x.kt", after = "pkg"))),
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(!body.has("session_id") || body.isNull("session_id"))
        assertEquals(1, body.getJSONArray("edits").length())
    }

    @Test
    fun `rejectRunEdits posts ApplyEditsRequest and succeeds on 200`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"run_id":"run-edits","status":"rejected"}"""),
        )

        api.rejectRunEdits(
            instanceId,
            "run-edits",
            ApplyEditsRequest(edits = listOf(ProposedEdit(path = "skip.txt", after = "x"))),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/runs/run-edits/edits/reject", recorded.requestUrl?.encodedPath)
        assertEquals("Bearer tok123", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals(1, body.getJSONArray("edits").length())
    }

    @Test(expected = IOException::class)
    fun `acceptRunEdits throws on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        api.acceptRunEdits(instanceId, "r1", ApplyEditsRequest(edits = emptyList()))
    }

    @Test(expected = IOException::class)
    fun `rejectRunEdits throws on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        api.rejectRunEdits(instanceId, "r1", ApplyEditsRequest(edits = emptyList()))
    }

    // ---- summary pr / reason / branch mapping ----------------------------

    @Test
    fun `listRuns maps optional reason branch and pr from the wire`() = runTest {
        val responseJson = """
            {"runs":[{
              "run_id":"r-pr",
              "mode":"agent",
              "started_at":100,
              "state":"needs_you",
              "reason":"awaiting clarification",
              "branch":"feat/kma-288",
              "pr":{
                "state":"open",
                "number":42,
                "url":"https://github.com/org/repo/pull/42",
                "checks_status":"pending",
                "checks_url":"https://github.com/org/repo/pull/42/checks"
              }
            }]}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val runs = api.listRuns(instanceId)

        assertEquals(1, runs.size)
        val run = runs[0]
        assertEquals(AgentRunState.NEEDS_YOU, run.state)
        assertEquals("awaiting clarification", run.reason)
        assertEquals("feat/kma-288", run.branch)
        val pr = run.pr
        assertNotNull(pr)
        assertEquals("open", pr!!.state)
        assertEquals(42, pr.number)
        assertEquals("https://github.com/org/repo/pull/42", pr.url)
        assertEquals("pending", pr.checksStatus)
        assertEquals("https://github.com/org/repo/pull/42/checks", pr.checksUrl)
    }

    @Test
    fun `listRuns leaves reason branch pr null when omitted on the wire`() = runTest {
        val responseJson =
            """{"runs":[{"run_id":"r1","mode":"ask","started_at":1,"state":"running"}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val run = api.listRuns(instanceId).single()

        assertNull(run.reason)
        assertNull(run.branch)
        assertNull(run.pr)
    }

    @Test
    fun `getRunDetail maps nested summary pr`() = runTest {
        val responseJson = """
            {"summary":{
              "run_id":"r2","mode":"agent","started_at":2,"state":"completed",
              "pr":{"state":"merged","number":7}
            },"log_tail":[]}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val detail = api.getRunDetail(instanceId, "r2")

        assertNotNull(detail.summary.pr)
        assertEquals("merged", detail.summary.pr!!.state)
        assertEquals(7, detail.summary.pr!!.number)
        assertNull(detail.summary.pr!!.url)
    }
}
