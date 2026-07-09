package dev.reviewsmith.provider.claudecode

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

interface ProcessRunner {
    fun run(workingDir: String, command: List<String>, stdin: String? = null): String
}

/** Thrown when the agent binary cannot be launched (e.g. not installed / not on PATH). */
class AgentUnavailableException(val binary: String, cause: Throwable) :
    RuntimeException("Could not launch '$binary': ${cause.message}", cause)

object DefaultProcessRunner : ProcessRunner {
    private const val TIMEOUT_MINUTES = 10L

    override fun run(workingDir: String, command: List<String>, stdin: String?): String {
        val proc = try {
            ProcessBuilder(command)
                .directory(File(workingDir))
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            throw AgentUnavailableException(command.firstOrNull() ?: "agent", e)
        }

        // Write stdin on a separate thread so stdout is drained concurrently; otherwise a
        // large prompt plus large output can deadlock on the OS pipe buffer.
        val stdinThread = Thread {
            proc.outputStream.use { os ->
                if (stdin != null) os.write(stdin.toByteArray())
            }
        }.apply { isDaemon = true; start() }

        val out = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished) {
            proc.destroyForcibly()
            return ""
        }
        stdinThread.join(TimeUnit.SECONDS.toMillis(5))
        return out
    }
}
