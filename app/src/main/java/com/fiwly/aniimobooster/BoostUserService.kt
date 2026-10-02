package com.fiwly.aniimobooster

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class BoostUserService : IBoostService.Stub() {
    override fun execute(command: String): String {
        if (command.isBlank() || command.length > 512) return "exit=-1\nInvalid command"
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "exit=-124\nCommand timed out"
            }
            val output = BufferedReader(InputStreamReader(process.inputStream))
                .readText().trim().takeLast(12000)
            "exit=${process.exitValue()}\n${output}"
        } catch (t: Throwable) {
            "exit=-1\n${t.javaClass.simpleName}: ${t.message ?: "execution failed"}"
        }
    }
    override fun exit() = destroy()
    override fun destroy() { System.exit(0) }
}