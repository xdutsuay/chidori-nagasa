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

    fun isComputerEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_COMPUTER_ENABLED, DEFAULT_ENABLED)

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
