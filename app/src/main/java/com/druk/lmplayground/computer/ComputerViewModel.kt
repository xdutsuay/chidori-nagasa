package com.druk.lmplayground.computer

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.computer.desktop.LocalDesktopBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ComputerUiState(
    val enabled: Boolean = false,
    val status: ComputerStatus = ComputerStatus.Disabled,
    val sessionId: String = "",
    val terminal: String = "",
    val input: String = "",
    val cwd: String = CosCwd.DEFAULT,
    val busy: Boolean = false,
    val recipes: List<CosRecipe> = CosRecipeCatalog.BUNDLED,
    val installedRecipes: Set<String> = emptySet(),
    /** Ready-marker mode (`alpine-tar` / `offline-stub`) or null if missing. */
    val rootfsMode: String? = null,
    val rootfsUsedBytes: Long = 0L,
    /** True once proot fell back to `PROOT_NO_SECCOMP=1` on this device. */
    val prootNoSeccomp: Boolean = false,
)

class ComputerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
    private val flags = ComputerFlagStore.fromSharedPreferences(prefs)
    private val sessionStore = CosSessionStore(object : CosSessionBackend {
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    })
    private val recipeStore = CosRecipeStore()
    private val runtime = LocalComputerRuntime(
        context = app,
        flags = flags,
        batteryPercent = { readBatteryPercent(app) },
        thermalStatus = { readThermal(app) },
    )

    private val history = ComputerPrefs.getHistory(prefs).toMutableList()
    /** -1 = editing live draft; otherwise index into [history]. */
    private var historyIndex = -1
    private var draftInput = ""

    private val _state = MutableStateFlow(
        run {
            val snap = runtime.rootfsSnapshot()
            ComputerUiState(
                enabled = flags.isComputerEnabled(),
                status = runtime.status(),
                sessionId = sessionStore.currentSessionId(),
                cwd = CosCwd.normalizeOrNull(ComputerPrefs.getCwd(prefs)) ?: CosCwd.DEFAULT,
                rootfsMode = snap.mode,
                rootfsUsedBytes = snap.usedBytes,
                prootNoSeccomp = flags.isProotNoSeccomp(),
            )
        },
    )
    val state: StateFlow<ComputerUiState> = _state.asStateFlow()

    fun refresh() {
        val snap = runtime.rootfsSnapshot()
        _state.value = _state.value.copy(
            enabled = flags.isComputerEnabled(),
            status = runtime.status(),
            sessionId = sessionStore.currentSessionId(),
            installedRecipes = recipeStore.installed(),
            rootfsMode = snap.mode,
            rootfsUsedBytes = snap.usedBytes,
            prootNoSeccomp = flags.isProotNoSeccomp(),
        )
    }

    fun setEnabled(enabled: Boolean) {
        flags.setComputerEnabled(enabled)
        if (!enabled) {
            runtime.stop()
            // Turning Computer off also stops the desktop (whole proot tree, off-thread).
            LocalDesktopBridge.peek()?.stopAsync("computer-disabled")
            CosComputerService.stop(getApplication())
        }
        refresh()
    }

    fun onInputChange(value: String) {
        if (historyIndex != -1) {
            historyIndex = -1
            draftInput = value
        }
        _state.value = _state.value.copy(input = value)
    }

    fun onHistoryPrev() {
        if (history.isEmpty()) return
        if (historyIndex == -1) {
            draftInput = _state.value.input
            historyIndex = history.lastIndex
        } else if (historyIndex > 0) {
            historyIndex--
        } else {
            return
        }
        _state.value = _state.value.copy(input = history[historyIndex])
    }

    fun onHistoryNext() {
        if (historyIndex == -1) return
        if (historyIndex < history.lastIndex) {
            historyIndex++
            _state.value = _state.value.copy(input = history[historyIndex])
        } else {
            historyIndex = -1
            _state.value = _state.value.copy(input = draftInput)
        }
    }

    fun ensureRootfs() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            appendTerminal("${prompt()} ensure-rootfs\n")
            val status = runtime.ensureRootfsStreaming { line ->
                appendTerminal("$line\n")
            }
            val snap = runtime.rootfsSnapshot()
            _state.value = _state.value.copy(
                busy = false,
                status = status,
                rootfsMode = snap.mode,
                rootfsUsedBytes = snap.usedBytes,
            )
            appendTerminal(
                when (status) {
                    is ComputerStatus.Error -> "!! ${status.message}\n"
                    ComputerStatus.Ready -> "ok: Ready\n"
                    else -> "status=$status\n"
                },
            )
            appendTerminal("${snap.statusLine}\n")
        }
    }

    /** Delete rootfs (keep session prefs), then ensure again. */
    fun reinstallRootfs() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            appendTerminal("${prompt()} reinstall-rootfs\n")
            val status = runtime.reinstallRootfsStreaming { line ->
                appendTerminal("$line\n")
            }
            val snap = runtime.rootfsSnapshot()
            _state.value = _state.value.copy(
                busy = false,
                status = status,
                rootfsMode = snap.mode,
                rootfsUsedBytes = snap.usedBytes,
            )
            appendTerminal(
                when (status) {
                    is ComputerStatus.Error -> "!! ${status.message}\n"
                    ComputerStatus.Ready -> "ok: Ready\n"
                    else -> "status=$status\n"
                },
            )
            appendTerminal("${snap.statusLine}\n")
        }
    }

    fun runInput() {
        val line = _state.value.input.trim()
        if (line.isEmpty() || _state.value.busy) return
        rememberHistory(line)
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, input = "")
            appendTerminal("${prompt()} $line\n")
            val tokens = CosShellLexer.tokenize(line)
            if (tokens.firstOrNull() == "cd") {
                handleCd(tokens.drop(1).firstOrNull())
                _state.value = _state.value.copy(busy = false, status = runtime.status())
                return@launch
            }
            if (runtime.status() is ComputerStatus.MissingRootfs) {
                runtime.ensureRootfsStreaming { appendTerminal("$it\n") }
            }
            val argv = CosShellLexer.toArgv(line)
            val result = runtime.execStreaming(argv, _state.value.cwd) { chunk ->
                appendTerminal("$chunk\n")
            }
            if (result.exitCode != 0 && result.stderr.isNotBlank() &&
                !result.stderr.lines().all { _state.value.terminal.contains(it) }
            ) {
                // stderr already streamed with [err] prefix usually
            }
            appendTerminal("[exit ${result.exitCode}]\n")
            _state.value = _state.value.copy(
                busy = false,
                status = runtime.status(),
                prootNoSeccomp = flags.isProotNoSeccomp(),
            )
        }
    }

    fun smokeExec() {
        _state.value = _state.value.copy(input = "uname -a")
        runInput()
    }

    fun stopExec() {
        runtime.stop()
        appendTerminal("^C stopped\n")
        _state.value = _state.value.copy(busy = false, status = runtime.status())
    }

    fun prune() {
        CosComputerService.startPrune(getApplication())
        val deleted = runtime.pruneIfNeeded()
        appendTerminal(
            if (deleted.isEmpty()) "prune: nothing\n"
            else "prune: ${deleted.joinToString()}\n",
        )
        _state.value = _state.value.copy(status = runtime.status())
    }

    fun installRecipe(id: String) {
        val ok = recipeStore.install(id)
        appendTerminal(if (ok) "recipe installed: $id\n" else "unknown recipe: $id\n")
        _state.value = _state.value.copy(installedRecipes = recipeStore.installed())
    }

    fun rotateSession() {
        val id = sessionStore.rotateSessionId()
        _state.value = _state.value.copy(sessionId = id)
        appendTerminal("new session $id\n")
    }

    fun clearTerminal() {
        _state.value = _state.value.copy(terminal = "")
    }

    private fun handleCd(argument: String?) {
        when (val resolved = CosCwd.resolveCd(_state.value.cwd, argument)) {
            is CosCwd.Result.Err -> {
                appendTerminal("${resolved.message}\n[exit 1]\n")
            }
            is CosCwd.Result.Ok -> {
                if (runtime.rootfsPresent()) {
                    val dir = runtime.workDirFor(resolved.path)
                    if (dir == null || !dir.isDirectory) {
                        appendTerminal("cd: no such directory: ${argument ?: "/"}\n[exit 1]\n")
                        return
                    }
                }
                _state.value = _state.value.copy(cwd = resolved.path)
                ComputerPrefs.setCwd(prefs, resolved.path)
                appendTerminal("[exit 0]\n")
            }
        }
    }

    private fun rememberHistory(line: String) {
        if (history.lastOrNull() != line) {
            history += line
            while (history.size > CosCwd.MAX_HISTORY) {
                history.removeAt(0)
            }
            ComputerPrefs.setHistory(prefs, history)
        }
        historyIndex = -1
        draftInput = ""
    }

    private fun prompt(): String = CosCwd.promptLabel(_state.value.cwd)

    private fun appendTerminal(chunk: String) {
        val next = (_state.value.terminal + chunk).takeLast(32_000)
        _state.value = _state.value.copy(terminal = next)
    }

    private fun readBatteryPercent(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 100
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct else 100
    }

    private fun readThermal(ctx: Context): Int {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 0
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            pm.currentThermalStatus
        } else {
            0
        }
    }
}
