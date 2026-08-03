package com.druk.lmplayground.coordinator.transport

import com.druk.lmplayground.coordinator.model.AgentMode
import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.CoordinatorStatus
import com.druk.lmplayground.coordinator.model.InstanceId
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for the two production bugs fixed in
 * `OkHttpCoordinatorApi`:
 *   1. Every blocking okhttp `.execute()` must be hopped onto
 *      `Dispatchers.IO` (`onIo {}`), never run on the caller's context —
 *      on a real device the caller is `viewModelScope` (main thread) and
 *      Android's StrictMode default policy throws
 *      NetworkOnMainThreadException, which made pairing/status calls fail
 *      on every real device despite working fine on plain JVM/dev builds.
 *   2. `revokePairing` must send its bearer `Authorization` header via the
 *      same `authedRequest()` helper as every other authenticated call —
 *      it previously sent an unauthenticated DELETE, which the desktop
 *      401s, silently leaving the desktop's copy of the token valid after
 *      the phone believed it had unpaired.
 */
class OkHttpCoordinatorApiTest {

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

    // ---- beginPairing ----------------------------------------------------

    @Test
    fun `beginPairing sends POST to pairing begin with empty json body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = api.beginPairing(instanceId, server.hostName, server.port)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/pairing/begin", recorded.path)
        assertEquals("{}", recorded.body.readUtf8())
        assertTrue(result is PairingOutcome.Success)
    }

    @Test
    fun `beginPairing surfaces HTTP 500 as a Failure mentioning the status code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = api.beginPairing(instanceId, server.hostName, server.port)

        assertTrue(result is PairingOutcome.Failure)
        assertTrue((result as PairingOutcome.Failure).reason.contains("500"))
    }

    // ---- confirmPairing ----------------------------------------------------

    @Test
    fun `confirmPairing happy path parses auth token, instance id and protocol version`() = runTest {
        val responseJson = JSONObject()
            .put("instance_id", "srv-1")
            .put("auth_token", "tok123")
            .put("protocol_version", "1.2.0")
            .toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val result = api.confirmPairing(instanceId, "123456")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/pairing/confirm", recorded.path)
        assertEquals("""{"code":"123456"}""", recorded.body.readUtf8())

        assertTrue(result is PairingConfirmResult.Success)
        val success = result as PairingConfirmResult.Success
        assertEquals("tok123", success.confirmation.authToken)
        assertEquals(InstanceId("srv-1"), success.confirmation.instanceId)
        assertEquals("1.2.0", success.confirmation.protocolVersion.value)
    }

    @Test
    fun `confirmPairing without auth_token field in response is a Failure`() = runTest {
        val responseJson = JSONObject()
            .put("instance_id", "srv-1")
            .put("protocol_version", "1.2.0")
            .toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val result = api.confirmPairing(instanceId, "123456")

        assertTrue(result is PairingConfirmResult.Failure)
    }

    @Test
    fun `confirmPairing surfaces HTTP 403 as a Failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = api.confirmPairing(instanceId, "123456")

        assertTrue(result is PairingConfirmResult.Failure)
    }

    // ---- revokePairing (regression for missing Authorization header) ------

    @Test
    fun `revokePairing sends DELETE to pairing slash id with the bearer auth header`() = runTest {
        currentToken = "tok123"
        server.enqueue(MockResponse().setResponseCode(200))

        api.revokePairing(instanceId)

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/pairing/${instanceId.value}", recorded.path)
        assertEquals("Bearer tok123", recorded.getHeader("Authorization"))
    }

    @Test
    fun `revokePairing omits the Authorization header when no token is available`() = runTest {
        currentToken = null
        server.enqueue(MockResponse().setResponseCode(200))

        api.revokePairing(instanceId)

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    // ---- getStatus ----------------------------------------------------

    @Test
    fun `getStatus maps running status and carries the bearer header`() = runTest {
        currentToken = "tok123"
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"running"}"""))

        val status = api.getStatus(instanceId)

        val recorded = server.takeRequest()
        assertEquals("/coordinator/status", recorded.path)
        assertEquals("Bearer tok123", recorded.getHeader("Authorization"))
        assertEquals(CoordinatorStatus.RUNNING, status)
    }

    @Test
    fun `getStatus maps idle status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"idle"}"""))

        val status = api.getStatus(instanceId)

        assertEquals(CoordinatorStatus.IDLE, status)
    }

    // ---- listRuns ----------------------------------------------------

    @Test
    fun `listRuns parses a single run summary from the runs array`() = runTest {
        val responseJson = """{"runs":[{"run_id":"r1","mode":"agent","started_at":123,"state":"running"}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val runs = api.listRuns(instanceId)

        assertEquals(1, runs.size)
        val run = runs[0]
        assertEquals("r1", run.runId)
        assertEquals(AgentMode.AGENT, run.mode)
        assertEquals(123L, run.startedAtEpochMillis)
        assertEquals(AgentRunState.RUNNING, run.state)
    }

    // ---- Dispatchers.IO hop (regression for NetworkOnMainThreadException) --

    @Test
    fun `suspend calls complete from a single-threaded caller dispatcher even when the server is slow`() = runTest {
        // On plain JVM there's no Android main-thread policy to violate, so
        // this can't reproduce NetworkOnMainThreadException directly. What it
        // does verify: onIo{}'s withContext(Dispatchers.IO) hop means the
        // blocking OkHttp call never occupies the caller's own (single)
        // thread, so a single-threaded caller dispatcher isn't starved out
        // and the call still completes promptly. If onIo{} were removed, the
        // blocking execute() would run directly on this single thread as
        // well and produce the same passing result here — this test exists
        // to document/exercise the hop and to gate the singleThreadedIo
        // test below.
        val singleThreadedCaller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"status":"running"}""")
                    .setBodyDelay(200, TimeUnit.MILLISECONDS)
            )

            val status = withContext(singleThreadedCaller) {
                withTimeout(5_000) { api.getStatus(instanceId) }
            }

            assertEquals(CoordinatorStatus.RUNNING, status)
        } finally {
            singleThreadedCaller.close()
        }
    }

    @Test
    fun `onIo hop frees the caller thread while the blocking call is in flight`() = runTest {
        // Stronger version of the above: pin the calling coroutine to a
        // single-threaded dispatcher, then concurrently run a second
        // coroutine on that *same* dispatcher that just flips a flag. If
        // onIo{} correctly moves the blocking execute() onto
        // Dispatchers.IO, the single caller thread is free to run the
        // second coroutine while the (delayed) HTTP response is pending,
        // so the flag flips before getStatus() returns. Without the
        // Dispatchers.IO hop the blocking call would monopolize the lone
        // thread and the flag would only flip afterwards.
        val singleThreadedCaller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"status":"running"}""")
                    .setBodyDelay(300, TimeUnit.MILLISECONDS)
            )

            var otherWorkRanWhileCallInFlight = false
            withContext(singleThreadedCaller) {
                val callJob = launch { api.getStatus(instanceId) }
                // Give the call a moment to start and hop off onto IO.
                delay(50)
                otherWorkRanWhileCallInFlight = true
                callJob.join()
            }

            assertTrue(otherWorkRanWhileCallInFlight)
        } finally {
            singleThreadedCaller.close()
        }
    }
}
