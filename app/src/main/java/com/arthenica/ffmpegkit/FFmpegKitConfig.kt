package com.arthenica.ffmpegkit

object FFmpegKitConfig {
    fun getVersion(): String {
        return "unknown"
    }

    fun getBuildDate(): String {
        return "unknown"
    }

    fun setLogCallback(callback: (String) -> Unit) {}

    fun setFontDirectory(path: String, recursive: Boolean) {}
}