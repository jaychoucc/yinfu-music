import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ===== 版本号自动生成 =====
//
// 用户要求：每次构建出的 APK 都要带版本号便于区分。
//
// 设计：
//  * versionName = "1.0.0-yyyyMMdd.HHmm"（可读、含构建时间）
//      例：1.0.0-20260910.1538
//  * versionCode = yyyyMMddHH（int，小时级粒度）
//      例：2026091015
//      Android 要求 versionCode 单调递增、不可回退；
//      同一小时内多次构建 versionCode 相同，但 versionName 仍能区分（分钟级）。
//  * APK 输出文件名 = yinfu-music-{versionName}.apk
//      避免反复覆盖同名文件时混淆「装的是哪一版」。
//
// 所有时间以 Asia/Shanghai 固定时区计算，避免构建机时区差异导致版本号跳动。
val buildTs: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd.HHmm")
val versionCodeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH")

val generatedVersionName: String = "1.0.0-${buildTs.format(timestampFormatter)}"
val generatedVersionCode: Int = buildTs.format(versionCodeFormatter).toInt()

android {
    namespace = "com.soundtrack.music"
    compileSdk = 33
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.soundtrack.music"
        minSdk = 24
        targetSdk = 33
        versionCode = generatedVersionCode
        versionName = generatedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // 允许在 inline lambda（forEach/let/runCatching 等）里使用 break/continue。
        // 各音源解析逻辑大量依赖这种写法，逐文件改写成本极高且易错，故全局开启。
        freeCompilerArgs += listOf("-XXLanguage:+BreakContinueInInlineLambdas")
    }

    buildFeatures {
        viewBinding = false
    }
}

// 把版本号通过 BuildConfig 暴露到运行时（用于通知显示、about 页等）
//
// 改用 doLast 后置重命名：AGP 8.1.4 的 androidComponents.onVariants 闭包内
// `output.outputFileName` / `variant.versionName` 在编译期对 Kotlin DSL
// 不可见（API 私有/未暴露），会报 Unresolved reference。
// doLast 在 assembleDebug outputs 写入完成后执行，闭包捕获本脚本顶部
// 的 generatedVersionName —— 简单可靠、跨 AGP 版本都好用。
afterEvaluate {
    tasks.findByName("assembleDebug")?.doLast {
        val debugDir = file("$buildDir/outputs/apk/debug")
        val src = File(debugDir, "app-debug.apk")
        if (src.exists()) {
            val dst = File(debugDir, "yinfu-music-$generatedVersionName.apk")
            if (src.absolutePath != dst.absolutePath) {
                if (dst.exists()) dst.delete()
                src.renameTo(dst)
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    implementation("androidx.media3:media3-exoplayer:1.0.2")
    implementation("androidx.media3:media3-session:1.0.2")
    implementation("androidx.media3:media3-ui:1.0.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
