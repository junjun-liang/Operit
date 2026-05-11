package com.arthenica.ffmpegkit

object FFmpegKit {
    fun execute(command: String): FFmpegSession {
        return FFmpegSession()
    }

    fun executeAsync(command: String, completeCallback: (FFmpegSession) -> Unit) {
        completeCallback(FFmpegSession())
    }
}