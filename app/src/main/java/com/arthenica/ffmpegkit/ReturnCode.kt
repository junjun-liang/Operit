package com.arthenica.ffmpegkit

class ReturnCode(val value: Int) {
    companion object {
        fun isSuccess(returnCode: ReturnCode?): Boolean {
            return returnCode?.value == 0
        }

        fun isCancel(returnCode: ReturnCode?): Boolean {
            return returnCode?.value == -1
        }
    }
}