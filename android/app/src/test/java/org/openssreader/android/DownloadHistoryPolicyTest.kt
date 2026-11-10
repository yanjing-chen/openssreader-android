package org.openssreader.android

import org.junit.Assert.*
import org.junit.Test

class DownloadHistoryPolicyTest {
    @Test fun activeDownloadsCannotBeRemoved() {
        for (stage in listOf("尝试直连", "等待授权", "准备下载", "下载中", "等待解码", "解码中"))
            assertFalse("Active: $stage", DownloadHistoryPolicy.canRemove(stage))
    }
    @Test fun terminalHistoryCanBeRemoved() {
        for (stage in listOf("已完成", "解码未完成", "已取消", "已中断", "下载失败", "解码完成（公共保存失败）"))
            assertTrue("Terminal: $stage", DownloadHistoryPolicy.canRemove(stage))
    }
}
