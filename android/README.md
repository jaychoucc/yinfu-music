# 声轨 Soundtrack —— 安卓端

Kotlin 原生安卓应用，聚合 咪咕 / 网易云 / 酷我 / QQ 四源音乐搜索、播放与下载。

## 构建命令

使用主理人提供的本地 Gradle 8.2：

```bash
cd android
..\build-tools\gradle-8.2\bin\gradle.bat --no-daemon assembleDebug
```

APK 将生成在 `app/build/outputs/apk/debug/app-debug.apk`。

## 工程配置

- AGP 8.1.4，Kotlin 1.9.24
- compileSdk / targetSdk 33，minSdk 24
- buildToolsVersion 锁定 34.0.0（对齐本地 build-tools 版本），依赖矩阵选用与 API 33 兼容的 androidx/media3 版本
- 工程内已附带 `local.properties` 指向 `build-tools/android-sdk`，无需额外设置 ANDROID_HOME
- Maven 仓库：腾讯镜像 `https://mirrors.cloud.tencent.com/nexus/repository/maven-public/`
- 播放：Media3 ExoPlayer + MediaSession 前台服务
- 网络：OkHttp 4.12.0
- 图片：自实现 MiniImageLoader（内存 + 磁盘缓存）

## 功能

- 底部导航三页：首页 / 搜索 / 我的
- 搜索页：顶部音源 Chips + 去抖搜索 + 流式结果追加
- 沉浸式播放页：模糊背景、旋转封面、进度条、歌词同步、长按复制
- PlaybackService + 通知栏/锁屏控制
- 下载：保存到 `Music/Soundtrack/` 并通过 MediaStore 入库

## 免责声明

本项目为开源学习作品，仅用于技术研究与个人娱乐；所有音频、歌词、封面版权归原作者及各音乐平台所有。请勿用于商业用途或公开分发，下载后请在 24 小时内删除。开发者不对使用本项目产生的任何法律/版权纠纷负责。
