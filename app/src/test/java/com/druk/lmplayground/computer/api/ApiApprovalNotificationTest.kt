package com.druk.lmplayground.computer.api

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.druk.lmplayground.tools.CosConfirmGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.util.Collections
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android side of [ApiApprovalRelay]: the "Computer API requests" channel / notification,
 * [ApiApprovalNotification.AndroidPoster] and [ApiApprovalReceiver.handle].
 *
 * Robolectric here has no app resources, so text comes from a fake [ApiApprovalNotification.Texts].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiApprovalNotificationTest {

    private lateinit var context: Application
    private lateinit var nm: NotificationManager

    private val texts = ApiApprovalNotification.Texts(
        channelName = "Fake channel name",
        channelDescription = "Fake channel description",
        title = { action -> "Fake title for <$action>" },
        publicTitle = "Fake public title",
        publicText = "Fake public text",
        allow = "Fake Allow",
        deny = "Fake Deny",
    )

    private val summary = "cat /home/user/secret-summary.txt"
    private val request = ApiApprovalRelay.Request(
        key = "req-key-abc",
        gateId = 7,
        action = "exec",
        summary = summary,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(nm).setNotificationsEnabled(true)
    }

    private fun build(r: ApiApprovalRelay.Request = request) = ApiApprovalNotification.build(context, r, texts)

    private fun poster() = ApiApprovalNotification.AndroidPoster(context) { texts }

    private fun Notification.text(key: String): String =
        extras.getCharSequence(key)?.toString().orEmpty()

    private fun Notification.contentText(): String = listOf(
        text(Notification.EXTRA_TITLE),
        text(Notification.EXTRA_TEXT),
        text(Notification.EXTRA_BIG_TEXT),
        text(Notification.EXTRA_SUB_TEXT),
    ).joinToString("\n")

    private fun Notification.action(label: String): Notification.Action =
        actions.single { it.title.toString() == label }

    // --- channel ---

    @Test
    fun `ensureChannel creates a high importance channel named from texts`() {
        ApiApprovalNotification.ensureChannel(context, texts)

        val channel = nm.getNotificationChannel(ApiApprovalNotification.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(texts.channelName, channel.name.toString())
        assertEquals(texts.channelDescription, channel.description)
    }

    @Test
    fun `ensureChannel twice keeps a single channel`() {
        ApiApprovalNotification.ensureChannel(context, texts)
        ApiApprovalNotification.ensureChannel(context, texts.copy(channelName = "Other name"))

        val matching = nm.notificationChannels.filter { it.id == ApiApprovalNotification.CHANNEL_ID }
        assertEquals(1, matching.size)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, matching.single().importance)
        assertEquals(texts.channelName, matching.single().name.toString())
    }

    // --- build ---

    @Test
    fun `build posts on the approval channel`() {
        assertEquals(ApiApprovalNotification.CHANNEL_ID, build().channelId)
    }

    @Test
    fun `build content shows the action and the summary`() {
        val n = build()

        assertEquals(texts.title("exec"), n.text(Notification.EXTRA_TITLE))
        assertEquals(summary, n.text(Notification.EXTRA_TEXT))
        val content = n.contentText()
        assertTrue(content, content.contains(summary))
        assertTrue(content, content.contains("exec"))
    }

    @Test
    fun `build is private with a public version that hides the summary`() {
        val n = build()

        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion
        assertNotNull(public)
        assertEquals(texts.publicTitle, public.text(Notification.EXTRA_TITLE))
        assertEquals(texts.publicText, public.text(Notification.EXTRA_TEXT))
        val publicContent = public.contentText()
        assertFalse(publicContent, publicContent.contains(summary))
        assertFalse(publicContent, publicContent.contains("secret-summary"))
    }

    @Test
    fun `build has exactly Allow then Deny actions`() {
        val n = build()

        assertNotNull(n.actions)
        assertEquals(listOf(texts.allow, texts.deny), n.actions.map { it.title.toString() })
    }

    @Test
    fun `allow requires authentication and deny does not`() {
        val n = build()

        assertTrue(n.action(texts.allow).isAuthenticationRequired)
        assertFalse(n.action(texts.deny).isAuthenticationRequired)
    }

    @Test
    fun `build times out with the gate and is not auto cancel`() {
        val n = build()

        assertEquals(CosConfirmGate.DEFAULT_TIMEOUT_MS, n.timeoutAfter)
        assertEquals(0, n.flags and Notification.FLAG_AUTO_CANCEL)
    }

    @Test
    fun `actions fire immutable broadcasts to the receiver with the request key`() {
        val n = build()
        val receiver = ComponentName(context, ApiApprovalReceiver::class.java)

        listOf(
            texts.allow to ApiApprovalNotification.ACTION_ALLOW,
            texts.deny to ApiApprovalNotification.ACTION_DENY,
        ).forEach { (label, expectedAction) ->
            val pi = n.action(label).actionIntent
            assertNotNull(pi)
            val shadow = shadowOf(pi)
            assertTrue("$expectedAction broadcast", shadow.isBroadcast)
            assertTrue("$expectedAction immutable", shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            val saved = shadow.savedIntent
            assertEquals(receiver, saved.component)
            assertEquals(expectedAction, saved.action)
            assertEquals(request.key, saved.getStringExtra(ApiApprovalNotification.EXTRA_KEY))
        }
    }

    @Test
    fun `a later request's actions carry the later key`() {
        build()
        val later = request.copy(key = "req-key-later", gateId = 8)

        val n = build(later)

        listOf(texts.allow, texts.deny).forEach { label ->
            val saved = shadowOf(n.action(label).actionIntent).savedIntent
            assertEquals("req-key-later", saved.getStringExtra(ApiApprovalNotification.EXTRA_KEY))
        }
    }

    // --- canPost / AndroidPoster ---

    @Test
    fun `canPost is true with the permission granted and notifications enabled`() {
        assertTrue(ApiApprovalNotification.canPost(context))
    }

    @Test
    fun `canPost is false without POST_NOTIFICATIONS`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(ApiApprovalNotification.canPost(context))
    }

    @Test
    fun `canPost is false when notifications are disabled for the app`() {
        shadowOf(nm).setNotificationsEnabled(false)

        assertFalse(ApiApprovalNotification.canPost(context))
    }

    @Test
    fun `poster show posts the notification and creates the channel`() {
        poster().show(request)

        val posted = shadowOf(nm).getNotification(ApiApprovalNotification.NOTIFICATION_ID)
        assertNotNull(posted)
        assertEquals(ApiApprovalNotification.CHANNEL_ID, posted.channelId)
        assertEquals(texts.title("exec"), posted.text(Notification.EXTRA_TITLE))
        val channel = nm.getNotificationChannel(ApiApprovalNotification.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(texts.channelName, channel.name.toString())
    }

    @Test
    fun `poster show without POST_NOTIFICATIONS posts nothing and does not crash`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        poster().show(request)

        assertNull(shadowOf(nm).getNotification(ApiApprovalNotification.NOTIFICATION_ID))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun `poster show with notifications disabled posts nothing`() {
        shadowOf(nm).setNotificationsEnabled(false)

        poster().show(request)

        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun `poster cancel removes the notification`() {
        val poster = poster()
        poster.show(request)
        assertNotNull(shadowOf(nm).getNotification(ApiApprovalNotification.NOTIFICATION_ID))

        poster.cancel(request)

        assertNull(shadowOf(nm).getNotification(ApiApprovalNotification.NOTIFICATION_ID))
    }

    // --- receiver ---

    private val resolves: MutableList<Pair<Long, Boolean>> = Collections.synchronizedList(mutableListOf())

    private val noopPoster = object : ApiApprovalRelay.Poster {
        override fun show(request: ApiApprovalRelay.Request) = Unit
        override fun cancel(request: ApiApprovalRelay.Request) = Unit
    }

    private fun relayWithPending(): Pair<ApiApprovalRelay, String> {
        val relay = ApiApprovalRelay(
            resolve = { id, approved -> resolves += id to approved },
            poster = noopPoster,
            newKey = { "relay-key-1" },
        )
        relay.onPending(CosConfirmGate.Pending(7, "${ToolComputerControl.APPROVAL_PREFIX}exec", "ls"))
        return relay to relay.current()!!.key
    }

    private fun intent(action: String?, key: String?): Intent = Intent().apply {
        if (action != null) this.action = action
        if (key != null) putExtra(ApiApprovalNotification.EXTRA_KEY, key)
    }

    @Test
    fun `receiver allow answers the relay with approve`() {
        val (relay, key) = relayWithPending()

        assertTrue(ApiApprovalReceiver.handle(intent(ApiApprovalNotification.ACTION_ALLOW, key), relay))

        assertEquals(listOf(7L to true), resolves)
    }

    @Test
    fun `receiver deny answers the relay with deny`() {
        val (relay, key) = relayWithPending()

        assertTrue(ApiApprovalReceiver.handle(intent(ApiApprovalNotification.ACTION_DENY, key), relay))

        assertEquals(listOf(7L to false), resolves)
    }

    @Test
    fun `receiver returns the relay result for an unknown key`() {
        val (relay, _) = relayWithPending()

        assertFalse(ApiApprovalReceiver.handle(intent(ApiApprovalNotification.ACTION_ALLOW, "stale"), relay))

        assertTrue(resolves.isEmpty())
        assertNotNull(relay.current())
    }

    @Test
    fun `receiver ignores null intent wrong action and missing key`() {
        val (relay, key) = relayWithPending()

        assertFalse(ApiApprovalReceiver.handle(null, relay))
        assertFalse(ApiApprovalReceiver.handle(intent("com.example.OTHER", key), relay))
        assertFalse(ApiApprovalReceiver.handle(intent(null, key), relay))
        assertFalse(ApiApprovalReceiver.handle(intent(ApiApprovalNotification.ACTION_ALLOW, null), relay))
        assertFalse(ApiApprovalReceiver.handle(intent(ApiApprovalNotification.ACTION_DENY, null), relay))

        assertTrue(resolves.isEmpty())
        assertNotNull(relay.current())
    }

    // --- source files (Robolectric here has no merged manifest or app resources) ---

    private fun moduleFile(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: throw AssertionError("cannot find $path from ${File(".").absolutePath}")

    private fun parse(file: File) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)

    @Test
    fun `manifest declares the receiver not exported and without intent filters`() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        val doc = parse(moduleFile("src/main/AndroidManifest.xml"))
        val receivers = doc.getElementsByTagName("receiver")
        val matches = (0 until receivers.length)
            .map { receivers.item(it) as Element }
            .filter { it.getAttributeNS(androidNs, "name").endsWith(".ApiApprovalReceiver") }

        assertEquals(1, matches.size)
        val receiver = matches.single()
        assertEquals("false", receiver.getAttributeNS(androidNs, "exported"))
        assertEquals(0, receiver.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun `strings define the approval notification texts`() {
        val doc = parse(moduleFile("src/main/res/values/strings.xml"))
        val strings = doc.getElementsByTagName("string")
        val byName = (0 until strings.length)
            .map { strings.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }

        listOf(
            "computer_api_approval_channel_name",
            "computer_api_approval_channel_description",
            "computer_api_approval_title",
            "computer_api_approval_public_title",
            "computer_api_approval_public_text",
            "cos_confirm_allow",
            "cos_confirm_deny",
        ).forEach { name ->
            assertTrue("missing string $name", !byName[name].isNullOrBlank())
        }
        assertTrue(byName.getValue("computer_api_approval_title").contains("%1\$s"))
    }
}
