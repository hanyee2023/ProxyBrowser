package com.proxybrowser.app

import android.app.Application
import com.proxybrowser.app.core.V2RayManager

/**
 * 应用入口。
 * 预热：确认 nativeLibraryDir 中的 libxray.so 可执行，避免第一次启动节点时再检测造成卡顿。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            V2RayManager.ensureInstalled(this)
        } catch (_: Exception) {
            // 安装失败不致命；后续真用到节点时再尝试
        }
    }
}