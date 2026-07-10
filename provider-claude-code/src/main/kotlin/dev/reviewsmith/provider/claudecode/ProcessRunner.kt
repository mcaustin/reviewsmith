package dev.reviewsmith.provider.claudecode

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

interface ProcessRunner {
    fun run(
        workingDir: String,
        command: List<String>,
        stdin: String? = null,
        timeoutSeconds: Long = 300,
        budgetInEffect: Boolean = false,
    ): String
}

/** Thrown when the agent binary cannot be launched (e.g. not installed / not on PATH). */
class AgentUnavailableException(val binary: String, cause: Throwable) :
    RuntimeException("Could not launch '$binary': ${cause.message}", cause)

/** Thrown when the agent call exceeds its per-call timeout and is abandoned. */
class AgentTimeoutException(message: String) : RuntimeException(message)

/** Thrown when the agent exits non-zero while a spend cap is in effect (cap likely reached). */
class AgentBudgetExceededException(message: String) : RuntimeException(message)

object DefaultProcessRunner : ProcessRunner {
    override fun run(
        workingDir: String,
        command: List<String>,
        stdin: String?,
        timeoutSeconds: Long,
        budgetInEffect: Boolean,
    ): String {
        val proc = try {
            ProcessBuilder(command)
                .directory(File(workingDir))
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            throw AgentUnavailableException(command.firstOrNull() ?: "agent", e)
        }

        // Write stdin and drain stdout on separate threads so waitFor is actually reached:
        // readText() blocks until process exit, so reading it inline would make the timeout
        // unreachable and could deadlock on the OS pipe buffer for large prompt+output.
        val stdinThread = Thread {
            proc.outputStream.use { os ->
                if (stdin != null) os.write(stdin.toByteArray())
            }
        }.apply { isDaemon = true; start() }

        val outHolder = AtomicReference("")
        val outThread = Thread {
            outHolder.set(proc.inputStream.bufferedReader().readText())
        }.apply { isDaemon = true; start() }

        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.toHandle().descendants().forEach { it.destroyForcibly() }
            proc.destroyForcibly()
            throw AgentTimeoutException(
                "Agent timed out after ${timeoutSeconds}s (${command.firstOrNull() ?: "agent"})",
            )
        }
        outThread.join(TimeUnit.SECONDS.toMillis(5))
        stdinThread.join(TimeUnit.SECONDS.toMillis(5))
        if (budgetInEffect && proc.exitValue() != 0) {
            throw AgentBudgetExceededException(
                "Agent exited ${proc.exitValue()} with a spend cap in effect — budget likely reached",
            )
        }
        return outHolder.get()
    }
}
