package org.openssreader.android

/** Deleting a history row never deletes downloaded PDZ/PDF, and never stops a live job. */
object DownloadHistoryPolicy {
    private val activeStages = setOf("尝试直连", "等待授权", "准备下载", "下载中", "等待解码", "解码中")
    fun canRemove(stage: String): Boolean = stage !in activeStages
}
