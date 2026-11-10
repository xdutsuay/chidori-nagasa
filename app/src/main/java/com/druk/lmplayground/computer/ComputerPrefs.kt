package com.druk.lmplayground.computer

import android.content.SharedPreferences

/** Raw SharedPreferences accessors for the computer feature flag. */
object ComputerPrefs {
    const val KEY_COMPUTER_ENABLED = "computer.enabled"
    const val KEY_CWD = "computer.cwd"
    const val KEY_HISTORY = "computer.history"
    const val DEFAULT_ENABLED = false

    /** Set once proot needed `PROOT_NO_SECCOMP=1` on this device; later runs start there. */
    const val KEY_PROOT_NO_SECCOMP = "computer.proot_no_seccomp"

    /** CP4 first-run COS explainer dismissed. */
    const val KEY_FIRST_RUN_SEEN = "computer.first_run_seen"

    /**
     * CP7 review-before-act for `cos_gui` / destructive `cos_exec`:
     * `auto` | `ask_once` | `always` (see [com.druk.lmplayground.tools.CosConfirmPolicy.Mode]).
     */
    const val KEY_CONFIRM_MODE = "computer.confirm_mode"
    const val DEFAULT_CONFIRM_MODE = "ask_once"

    /** CP7 evidence-first dogfood: done claims must attach proof paths. */
    const val KEY_EVIDENCE_DOGFOOD = "computer.evidence_dogfood"
    const val DEFAULT_EVIDENCE_DOGFOOD = false

    /** Developer options: unlocks desktop sharing (VNC over USB). */
    const val KEY_DEVELOPER_MODE = "computer.developer_mode"
    const val DEFAULT_DEVELOPER_MODE = false

    fun isDeveloperMode(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_DEVELOPER_MODE, DEFAULT_DEVELOPER_MODE)

    fun setDeveloperMode(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
    }

    fun isComputerEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_COMPUTER_ENABLED, DEFAULT_ENABLED)

    fun getConfirmMode(prefs: SharedPreferences): String =
        prefs.getString(KEY_CONFIRM_MODE, DEFAULT_CONFIRM_MODE) ?: DEFAULT_CONFIRM_MODE

    fun setConfirmMode(prefs: SharedPreferences, mode: String) {
        prefs.edit().putString(KEY_CONFIRM_MODE, mode).apply()
    }

    fun isEvidenceDogfoodEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_EVIDENCE_DOGFOOD, DEFAULT_EVIDENCE_DOGFOOD)

    fun setEvidenceDogfoodEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EVIDENCE_DOGFOOD, enabled).apply()
    }

    fun setComputerEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPUTER_ENABLED, enabled).apply()
    }

    fun getCwd(prefs: SharedPreferences): String =
        prefs.getString(KEY_CWD, CosCwd.DEFAULT)?.ifBlank { CosCwd.DEFAULT } ?: CosCwd.DEFAULT

    fun setCwd(prefs: SharedPreferences, cwd: String) {
        prefs.edit().putString(KEY_CWD, cwd).apply()
    }

    /** Newest-last list, capped at [CosCwd.MAX_HISTORY]. */
    fun getHistory(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(KEY_HISTORY, "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').filter { it.isNotEmpty() }.takeLast(CosCwd.MAX_HISTORY)
    }

    fun setHistory(prefs: SharedPreferences, lines: List<String>) {
        val capped = lines.takeLast(CosCwd.MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, capped.joinToString("\n")).apply()
    }
}

/** Storage backend abstraction so the store is testable without Android. */
interface ComputerFlagBackend {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

class InMemoryFlagBackend : ComputerFlagBackend {
    private val values = mutableMapOf<String, Boolean>()
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] ?: default
    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }
}

/**
 * Testable wrapper around the computer.enabled flag.
 * Default is OFF until the user opts in.
 */
class ComputerFlagStore(private val backend: ComputerFlagBackend) {
    fun isComputerEnabled(): Boolean =
        backend.getBoolean(ComputerPrefs.KEY_COMPUTER_ENABLED, ComputerPrefs.DEFAULT_ENABLED)

    fun setComputerEnabled(enabled: Boolean) =
        backend.putBoolean(ComputerPrefs.KEY_COMPUTER_ENABLED, enabled)

    /** True once proot fell back to `PROOT_NO_SECCOMP=1` on this device. */
    fun isProotNoSeccomp(): Boolean = backend.getBoolean(ComputerPrefs.KEY_PROOT_NO_SECCOMP, false)

    fun setProotNoSeccomp(value: Boolean) = backend.putBoolean(ComputerPrefs.KEY_PROOT_NO_SECCOMP, value)

    companion object {
        /** Factory backed by real SharedPreferences. */
        fun fromSharedPreferences(prefs: SharedPreferences): ComputerFlagStore =
            ComputerFlagStore(object : ComputerFlagBackend {
                override fun getBoolean(key: String, default: Boolean): Boolean =
                    prefs.getBoolean(key, default)

                override fun putBoolean(key: String, value: Boolean) {
                    prefs.edit().putBoolean(key, value).apply()
                }
            })
    }
}
