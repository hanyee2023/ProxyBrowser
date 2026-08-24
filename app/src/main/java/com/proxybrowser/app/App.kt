package com.proxybrowser.app

import android.app.Application
import com.proxybrowser.app.core.V2RayManager

/**
 * 应用入口。
 * 预热：把 assets/xray/xray 复制到 nativeLibraryDir 并 chmod +x，避免第一次启动节点时再拷贝造成卡顿。
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