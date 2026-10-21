plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.proxybrowser.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.proxybrowser.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }

    // xray 二进制以 .so 形式打包，禁止 AAPT 做压缩/对齐破坏 ELF 头
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    // 只保留 arm64-v8a：xray 官方 android 二进制只有该架构
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

/**
 * 本地没有 xray 二进制时（比如离线、GitHub 下载超时），生成一个占位 .so，
 * 保证 assembleDebug 能出包用于验证 UI 与编译链路。
 * 真正的二进制由 CI（.github/workflows/build.yml）下载并覆盖同名文件。
 * 装到手机上若日志提示「xray 二进制缺失/过小」，就是这个占位文件，不是 bug。
 */
val ensureXrayBinary by tasks.registering {
    val soFile = file("src/main/jniLibs/arm64-v8a/libxray.so")
    val minSize = 1_000_000L
    doLast {
        if (soFile.exists() && soFile.length() > minSize) {
            println("[xray] 使用已有二进制: ${soFile.length() / 1024} KB")
            return@doLast
        }
        soFile.parentFile.mkdirs()
        val placeholder = ByteArray(1_100_000) { index ->
            // 前 4 字节写成 ELF magic，其余填 0，避免 file 命令完全判定失败
            when (index) {
                0 -> 0x7F
                1 -> 'E'.code
                2 -> 'L'.code
                3 -> 'F'.code
                else -> 0
            }.toByte()
        }
        soFile.writeBytes(placeholder)
        println("[xray] ⚠ 未找到真正的 libxray.so，已生成占位文件（仅用于编译验证，代理功能不可用）")
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(ensureXrayBinary) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
