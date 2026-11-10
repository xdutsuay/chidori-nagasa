package com.druk.lmplayground

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import com.druk.llamacpp.InferenceClient
import com.druk.llamacpp.LlamaCpp
import com.druk.lmplayground.checkin.BackgroundActivityPause
import com.druk.lmplayground.checkin.CheckInCompleter
import com.druk.lmplayground.checkin.CheckInPreferences
import com.druk.lmplayground.checkin.CheckInScheduler
import com.druk.lmplayground.checkin.CloudCheckInCompleter
import com.druk.lmplayground.checkin.LocalCheckInCompleter
import com.druk.lmplayground.checkin.OpenItemCapture
import com.druk.lmplayground.checkin.OpenItemHooks
import com.druk.lmplayground.checkin.OpenItemRepository
import com.druk.lmplayground.checkin.PreferencesPendingStore
import com.druk.lmplayground.checkin.RoutingCheckInCompleter
import com.druk.lmplayground.checkin.TeamHandoffIngest
import com.druk.lmplayground.cloud.CloudClient
import com.druk.lmplayground.cloud.CloudKeyStore
import com.druk.lmplayground.cloud.CloudProviderRepository
import com.druk.lmplayground.cloud.EncryptedCloudKeyStore
import com.druk.lmplayground.computer.desktop.LocalDesktopBridge
import com.druk.lmplayground.coordinator.CoordinatorRepository
import com.druk.lmplayground.coordinator.notifications.NeedsYouBadgePoller
import com.druk.lmplayground.data.AppDatabase
import com.druk.lmplayground.data.ChatRepository
import com.druk.lmplayground.data.SystemPromptRepository
import com.druk.lmplayground.data.UsageRepository
import com.druk.lmplayground.team.TeamHandoffEvents
import com.druk.lmplayground.download.DownloadNotificationManager
import com.druk.lmplayground.inference.LlamaService
import com.druk.lmplayground.inference.ProcessUtils
import com.druk.lmplayground.storage.LegacyDownloadCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class App : Application() {

    lateinit var inferenceClient: InferenceClient
        private set
    lateinit var llamaCpp: LlamaCpp
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var systemPromptRepository: SystemPromptRepository
        private set
    lateinit var cloudKeyStore: CloudKeyStore
        private set
    lateinit var cloudProviderRepository: CloudProviderRepository
        private set
    lateinit var usageRepository: UsageRepository
        private set
    lateinit var openItemRepository: OpenItemRepository
        private set
    var openItemCapture: OpenItemCapture? = null
        private set
    var checkInCompleter: CheckInCompleter? = null
        private set
    lateinit var cloudClient: CloudClient
        private set

    // chidori desktop companion features (net/coordinator, see CHIDORI_PROTOCOL.md §2/§3.4).
    // Construction is cheap (no I/O in the stub implementations) so it's safe to eagerly
    // create here like the other repositories; discovery/pairing/transport are unimplemented
    // stubs as of this first draft — see coordinator/README.md.
    lateinit var coordinatorRepository: CoordinatorRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var needsYouBadgePoller: NeedsYouBadgePoller? = null

    /**
     * True while any activity is in the started..stopped window, i.e. the
     * UI is visible to the user. Used to decide whether to play the
     * response-ready chime (only when the app is backgrounded). Updated by
     * the lifecycle callbacks below; only meaningful in the main process.
     */
    @Volatile
    var isAppInForeground: Boolean = false
        private set
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        // App.onCreate runs in *every* process the app spawns. The :llama
        // process only needs to host LlamaService — skip Room, repos, and
        // the inference-client binding (no service to bind from there).
        if (ProcessUtils.isLlamaProcess()) return

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                isAppInForeground = true
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    isAppInForeground = false
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        inferenceClient = InferenceClient(
            appContext = applicationContext,
            serviceComponent = ComponentName(this, LlamaService::class.java),
        )
        inferenceClient.bind()
        llamaCpp = LlamaCpp(inferenceClient)

        DownloadNotificationManager.createChannel(this)

        // Reclaim space from model files the old download flow may have
        // orphaned in app-private storage (see LegacyDownloadCleanup). Runs off
        // the main thread; cheap no-op once the dirs are clean.
        val appContext = applicationContext
        thread(isDaemon = true, name = "legacy-download-cleanup") {
            LegacyDownloadCleanup.run(appContext)
        }

        // CP2: publish the COS desktop runtime for the chat tools (DesktopBridges.bridge).
        // get() reads the rootfs install marker, so keep it off the main thread.
        thread(isDaemon = true, name = "cos-desktop-bridge") {
            LocalDesktopBridge.get(appContext)
        }

        val database = AppDatabase.getInstance(this)
        chatRepository = ChatRepository(database.chatDao())
        systemPromptRepository = SystemPromptRepository(database.systemPromptDao())
        cloudKeyStore = EncryptedCloudKeyStore.create(this)
        cloudProviderRepository = CloudProviderRepository(
            database.cloudProviderDao(),
            cloudKeyStore,
        )
        usageRepository = UsageRepository(database.usageEventDao())
        openItemRepository = OpenItemRepository(database.openItemDao())
        cloudClient = CloudClient()

        val checkInPreferences = CheckInPreferences(this)
        val completer = RoutingCheckInCompleter(
            local = LocalCheckInCompleter(),
            cloud = CloudCheckInCompleter(
                provider = { cloudProviderRepository.getAll().firstOrNull { it.defaultModel.isNotBlank() } },
                apiKey = { cloudKeyStore.getKey(it) },
                client = cloudClient,
            ),
            hybridEnabled = { checkInPreferences.hybridCloudEnabled },
        )
        checkInCompleter = completer
        openItemCapture = OpenItemCapture(
            scope = appScope,
            repository = openItemRepository,
            sessionCommands = openItemRepository,
            messages = { sessionId -> chatRepository.getMessages(sessionId) },
            completer = completer,
            enabled = { checkInPreferences.enabled },
            pending = PreferencesPendingStore(this),
        ).also { capture ->
            OpenItemHooks.onPersisted = { capture.onPersisted(it) }
        }
        TeamHandoffEvents.listener = { room, handoff ->
            appScope.launch {
                runCatching {
                    TeamHandoffIngest.onHandoff(
                        handoff,
                        room,
                        openItemRepository,
                        System.currentTimeMillis(),
                    )
                }
            }
        }

        coordinatorRepository = CoordinatorRepository(this)
        val backgroundPause = BackgroundActivityPause(this)
        needsYouBadgePoller = NeedsYouBadgePoller(
            coordinator = coordinatorRepository,
            scope = appScope,
            isAppInForeground = { isAppInForeground },
            isPaused = { backgroundPause.isPaused },
        ).also { it.start() }

        // Default off: a fresh install does not touch WorkManager.
        if (checkInPreferences.enabled) {
            runCatching { CheckInScheduler.sync(this) }
        }
    }
}
