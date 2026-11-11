package com.xdutsuay.chidori.tools

import com.xdutsuay.chidori.computer.CosAgentBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * The CP1 `cos_exec` chat-Stop pattern, shared by the desktop tools: the blocking
 * [Tool.execute] runs its work in an IO coroutine that [cancelAll] cancels with
 * reason [CosAgentBridge.CHAT_STOP]. The runtime's interruptible wait then tears
 * proot down through ProotTerminator and [run] throws [CancellationException].
 */
class InFlightCalls {
    private val jobs = ConcurrentHashMap.newKeySet<Job>()

    fun cancelAll() {
        jobs.forEach { it.cancel(CancellationException(CosAgentBridge.CHAT_STOP)) }
    }

    val size: Int get() = jobs.size

    fun <T> run(block: suspend CoroutineScope.() -> T): T = runBlocking {
        // LAZY: registered before it can run, so a Stop never misses a started call.
        val job = async(Dispatchers.IO, start = CoroutineStart.LAZY, block = block)
        jobs.add(job)
        try {
            job.await()
        } finally {
            jobs.remove(job)
        }
    }
}
