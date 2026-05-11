package com.arthenica.ffmpegkit

class MediaInformation {
    fun getFormat(): FormatInformation {
        return FormatInformation()
    }

    val streams: List<StreamInformation>
        get() = emptyList()

    val format: String?
        get() = getFormat().format

    val formatInfo: FormatInformation
        get() = FormatInformation()

    val duration: String?
        get() = formatInfo.duration

    val bitrate: String?
        get() = formatInfo.bitrate
}

class StreamInformation {
    fun get(key: String): String? {
        return null
    }

    val allProperties: Map<String, String>? = emptyMap()
    val index: String? = "0"
    val type: String = ""
    val codec: String = ""
    val width: Int = 0
    val height: Int = 0
    val size: Long = 0
    val frameRate: Double = 0.0
    val sampleRate: Int = 0
    val channels: Int = 0
}

class FormatInformation {
    fun get(key: String): String? {
        return null
    }

    val format: String? = null
    val duration: String? = null
    val bitrate: String? = null
}

class FFprobeSession {
    val returnCode: ReturnCode = ReturnCode(-1)
    val output: String? = null
    val mediaInformation: MediaInformation? = null
}

object FFprobeKit {
    fun execute(command: String): FFprobeSession {
        return FFprobeSession()
    }

    fun getMediaInformation(path: String): FFprobeSession {
        return FFprobeSession()
    }
}