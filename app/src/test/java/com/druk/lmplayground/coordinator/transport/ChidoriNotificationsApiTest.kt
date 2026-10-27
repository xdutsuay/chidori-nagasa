package com.druk.lmplayground.coordinator.transport

import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.InstanceId
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * KMA-288 (TEST AUTHOR RED — no production code yet; this file does not
 * compile until the implementer lands the surface below).
 *
 * == IMPLEMENTER CONTRACT (make these green, do not weaken assertions) ==
 *
 * Wire (desktop `lclreason` main @ 79b7209, `internal/api/kma288_companion_notify.go`):
 *
 *   GET /notifications?since=<cursor>&wait_ms=<ms>   (companion bearer required)
 *     → 200 {"events":[{"id":<int>,"run_id":"...","state":"...","reason":"...","ts":<epoch ms>}...],"cursor":<int>}
 *     → 401 on missing/malformed/invalid bearer
 *
 * Run-state tokens on the wire (internal/api/chidori_contract.go): "running",
 * "completed", "failed", "needs_you".
 *
 * 1. `CoordinatorApi.pollNotifications(instanceId, since, waitMs)` →
 *    `NotificationsPage(events: List<CompanionNotificationEvent>, cursor: Long)`
 *    where `CompanionNotificationEvent(id: Long, runId: String, state: String,
 *    reason: String, ts: Long)`. Implement on `OkHttpCoordinatorApi` via the
 *    existing `authedRequest()` helper (adds `Authorization: Bearer …` when a
 *    token is available) and the existing `onIo {}` hop. Always send BOTH
 *    query params `since` and `wait_ms` (desktop defaults bad values to 0, but
 *    the client must be explicit). HTTP error → `IOException` (same posture as
 *    `listRuns`).
 *
 * 2. `AgentRunState` gains `NEEDS_YOU`; the `toAgentRunSummary` mapping must
 *    map the wire token "needs_you" to it — NOT to `RUNNING` (a needs_you run
 *    must never render as merely running; desktop KMA-288 made the same
 *    distinction on the board).
 *
 * 3. Long-poll loop (caller side, may live in ChidoriViewModel): call
 *    `pollNotifications(instanceId, since, waitMs)` with wait_ms well inside
 *    the long-poll budget (desktop clamps at 30000 ms), advance
 *    `since = page.cursor` after every response (an empty page keeps the
 *    cursor, so blind `since = cursor` assignment is correct), and feed the
 *    page through the needs_you dispatch hook (see NeedsYouDispatchTest).
 *
 * 4. readTimeout vs wait_ms: `OkHttpCoordinatorApi`'s default client uses a
 *    30 s read timeout and the desktop's own clamp is 30 s — a wait_ms of
 *    30000 races the socket read timeout. The implementation MUST derive the
 *    per-call read timeout from wait_ms (e.g. `client.newBuilder()
 *    .readTimeout(waitMs + margin)`) rather than hoping the shared client's
 *    timeout is longer. `waitLongPollSurvivesDefaultReadTimeout` below pins
 *    this behavior. Document the margin choice at the implementation site.
 *
 * 5. WIRE_CONTRACT.md: the implementer should append /notifications to the
 *    wire-contract doc when landing (the doc is an unreconciled draft — see
 *    its closing section; this endpoint IS implemented on desktop main).
 */
class ChidoriNotificationsApiTest {

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

    private fun pageJson(vararg events: String, cursor: Long): String =
        """{"events":[${events.joinToString(",")}],"cursor":$cursor}"""

    private fun eventJson(
        id: Long,
        runId: String,
        state: String,
        reason: String,
        ts: Long,
    ): String =
        """{"id":$id,"run_id":"$runId","state":"$state","reason":"$reason","ts":$ts}"""

    // ---- parse: wire shape → NotificationsPage ---------------------------

    @Test
    fun `pollNotifications parses events array and cursor from the wire page`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                pageJson(
                    eventJson(7, "run-a", "completed", "", 1_700_000_000_000),
                    eventJson(8, "run-b", "needs_you", "awaiting clarification", 1_700_000_005_000),
                    cursor = 8,
                ),
            ),
        )

        val page = api.pollNotifications(instanceId, since = 0L, waitMs = 0L)

        assertEquals(2, page.events.size)
        val first = page.events[0]
        assertEquals(7L, first.id)
        assertEquals("run-a", first.runId)
        assertEquals("completed", first.state)
        assertEquals("", first.reason)
        assertEquals(1_700_000_000_000L, first.ts)
        val second = page.events[1]
        assertEquals(8L, second.id)
        assertEquals("run-b", second.runId)
        assertEquals("needs_you", second.state)
        assertEquals("awaiting clarification", second.reason)
        assertEquals(8L, page.cursor)
    }

    @Test
    fun `pollNotifications with an empty page keeps the incoming cursor`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(pageJson(cursor = 42)),
        )

        val page = api.pollNotifications(instanceId, since = 42L, waitMs = 0L)

        assertEquals(0, page.events.size)
        assertEquals(42L, page.cursor)
    }

    // ---- request shape: path, query params, bearer -----------------------

    @Test
    fun `pollNotifications sends GET with since and wait_ms query params and bearer auth`() = runTest {
        currentToken = "tok123"
        server.enqueue(MockResponse().setResponseCode(200).setBody(pageJson(cursor = 0)))

        api.pollNotifications(instanceId, since = 41L, waitMs = 500L)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/notifications", recorded.requestUrl?.encodedPath)
        assertEquals("41", recorded.requestUrl?.queryParameter("since"))
        assertEquals("500", recorded.requestUrl?.queryParameter("wait_ms"))
        assertEquals("Bearer tok123", recorded.getHeader("Authorization"))
    }

    @Test
    fun `pollNotifications omits the Authorization header when no token is available`() = runTest {
        currentToken = null
        server.enqueue(MockResponse().setResponseCode(200).setBody(pageJson(cursor = 0)))

        api.pollNotifications(instanceId, since = 0L, waitMs = 0L)

        val recorded = server.takeRequest()
        assertEquals(null, recorded.getHeader("Authorization"))
    }

    @Test
    fun `pollNotifications surfaces HTTP error as IOException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            api.pollNotifications(instanceId, since = 0L, waitMs = 0L)
            throw AssertionError("expected pollNotifications on HTTP 401 to throw IOException")
        } catch (expected: IOException) {
            // 401 (and other HTTP errors) must surface as IOException like
            // listRuns/cancelRun — the long-poll loop treats any failure as
            // "back off and retry", and a 401 specifically means the pairing
            // token went stale.
        }
    }

    @Test(expected = IOException::class)
    fun `pollNotifications throws when no endpoint is known`() = runTest {
        api = OkHttpCoordinatorApi(
            client = OkHttpClient(),
            authTokenProvider = { currentToken },
            endpointProvider = { null },
        )
        api.pollNotifications(instanceId, since = 0L, waitMs = 0L)
    }

    // ---- readTimeout vs wait_ms (contract item 4) ------------------------

    @Test
    fun `waitLongPollSurvivesDefaultReadTimeout - per-call timeout must exceed wait_ms`() = runTest {
        // The shared client here has a 1 s read timeout; the long poll asks
        // the desktop to hold the request for 1.5 s and the mocked server
        // delivers the empty page at ~2 s. The implementation must give this
        // call its own read timeout derived from wait_ms (contract item 4) or
        // the socket read dies with SocketTimeoutException before the page
        // arrives.
        api = OkHttpCoordinatorApi(
            client = OkHttpClient.Builder()
                .readTimeout(1, TimeUnit.SECONDS)
                .build(),
            authTokenProvider = { currentToken },
            endpointProvider = { _ -> server.hostName to server.port },
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(pageJson(cursor = 9))
                .setBodyDelay(2, TimeUnit.SECONDS),
        )

        val page = api.pollNotifications(instanceId, since = 9L, waitMs = 1_500L)

        assertEquals(9L, page.cursor)
        assertEquals(0, page.events.size)
    }

    // ---- AgentRunState.NEEDS_YOU mapping (contract item 2) ---------------

    @Test
    fun `listRuns maps needs_you to AgentRunState NEEDS_YOU not RUNNING`() = runTest {
        val responseJson =
            """{"runs":[{"run_id":"r9","mode":"agent","started_at":123,"state":"needs_you","reason":"awaiting clarification"}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val runs = api.listRuns(instanceId)

        assertEquals(1, runs.size)
        assertNotEquals(AgentRunState.RUNNING, runs[0].state)
        assertEquals(AgentRunState.NEEDS_YOU, runs[0].state)
    }

    @Test
    fun `listRuns keeps the existing state mappings intact`() = runTest {
        val responseJson = """
            {"runs":[
              {"run_id":"r1","mode":"agent","started_at":1,"state":"running"},
              {"run_id":"r2","mode":"agent","started_at":2,"state":"completed"},
              {"run_id":"r3","mode":"agent","started_at":3,"state":"failed"}
            ]}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val runs = api.listRuns(instanceId)

        assertEquals(AgentRunState.RUNNING, runs[0].state)
        assertEquals(AgentRunState.COMPLETED, runs[1].state)
        assertEquals(AgentRunState.FAILED, runs[2].state)
    }
}
