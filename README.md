# GD 音乐播放器 v3（从前慢）

> 版本 2.0.4（versionCode 20） · Android 7.0+（minSdk 24 / targetSdk 34） · Kotlin 2.4 / AGP 8.6.1 / Gradle 8.9

以音乐播放为起点，逐步扩展到影音、阅读和生活工具的 Android 应用。首页为「首页 / 搜索」双页签，下方是常驻播放条和可自定义的快捷资源位；无需登录账号，收藏、历史、设置等数据都保存在本机。

## 功能地图

| 模块 | 主要入口 | 能做什么 |
| --- | --- | --- |
| 音乐播放 | 首页播放条、搜索页 | 通过可配置的音乐 API 搜索、播放、下载；专辑封面、LRC 歌词、收藏、最近播放、播放队列；本地音乐导入与 ID3 标签读写；Media3 前台播放服务、系统媒体通知、桌面歌词、DLNA 投屏 |
| 发现榜单 | 搜索页「今日榜单」 | QQ 音乐 8 个榜单、两列网格展示；点条目自动搜索，播放与下载仍走音乐 API |
| 影视 | 看片 | 内置 TVBox 订阅与爬虫资源；豆瓣多维度榜单发现；片头片尾跳过、倍速、小窗播放、播放历史；m3u8 合并为 MP4 下载 |
| 小说 | 小说 | 书源聚合搜索、书架、阅读进度与收藏；章节正文左右滑翻页、目录升降序切换 |
| MD 文档 | 看MD | 选一个文件夹后按层级浏览子目录与 md 文件；Markdown 正文渲染、本地相对路径图片与远端 http 图片、文首 YAML 元数据属性卡、图片点开放大 |
| 生活工具 | 首页信息卡与快捷位 | 净资产记账（含年度总结 / JSON 备份）、提醒、高铁往返、播客电台、今日热榜、每日一图与每日一诗、链接唤起外部 App |
| AI 与语音 | 问一句、语音助手、水印 | LiteRT-LM 端侧模型本地问答（零 Key）+ 网页搜索增强；Vosk 离线语音识别；图片加水印 |
| 个性化 | 设置、首页快捷位 | 12 套纯色 / 渐变主题、自定义快捷入口与拖动排序、悬浮球快捷菜单、每日一图自定义目录 |

## 快速开始

环境要求：JDK 17、Android SDK Platform 34 与 Build Tools 34.0.0。Gradle 8.9 通过 wrapper 自带，无需单独安装；依赖仓库优先配置了国内镜像。

```bash
./gradlew assembleDebug        # Windows 下用 gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- 仓库内附带共享的 debug 签名（`signing/debug.keystore`），多台机器编译出的包可互相覆盖安装；仅用于 debug，不要拿去正式发布签名。
- 原生库只保留 `arm64-v8a`。x86_64 模拟器可以运行除端侧 AI 以外的功能（LiteRT-LM 无对应 ABI 的 so）。
- 每次交付会递增 `versionCode`，设置页顶部会显示版本号和构建时间，可据此确认手机上装的是哪一版。

## 目录结构

```text
app/src/main/java/com/linghit/gdmusic/   Kotlin 业务代码（Activity / Store / Engine）
app/src/main/assets/
  index.html      音乐首页与搜索页
  video.html      影视仓：榜单、订阅、播放设置
  novel.html      小说：搜索、书架、阅读器
  mdviewer.html   MD 文档：目录浏览与阅读
  rail.html       高铁往返
  tvbox/          内置订阅、爬虫 jar / js / py 资源
app/src/main/res/                       布局、主题与 dimen（播放器控制条覆写）
signing/                                共享 debug 签名
tools/                                  图标等辅助脚本
```

## 关键实现

- **WebView + JS 桥**：音乐、影视、小说、MD 的界面都在 `assets/*.html` 中，Kotlin 侧通过 `@JavascriptInterface` 提供文件读取、播放、下载等能力。
- **MD 文档读取**：targetSdk 34 的分区存储下 App 没有公共目录读权限，所以用系统文件选择器（SAF）让用户选一次目录并持久化授权，再递归扫描 `.md`。正文里的本地图片按相对路径从授权目录读取后注入 WebView；`http(s)` 图片保留原地址直连（应用已声明允许明文流量）。文首的 YAML frontmatter 会被解析成属性卡，正文只渲染元数据之后的部分。
- **播放器**：Media3 ExoPlayer + MediaSession，前台服务与媒体样式通知；控制条覆写 `exo_player_control_view.xml`，进度条位置通过 dimen 调整。
- **端侧智能**：LiteRT-LM 在本地跑小模型，Vosk 负责离线语音识别；模型或权限缺失时功能自动降级，不影响主流程。
- **主题系统**：`ThemePrefs.PACKS` 管理 12 套主题，`applyTheme()` 在 `setContentView` 之前调用。

## 权限与隐私

| 权限 | 用途 |
| --- | --- |
| INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE | 访问内容接口、封面与图片，DLNA 发现与网络状态判断 |
| FOREGROUND_SERVICE 及媒体、specialUse 类型 | 后台播放与常驻悬浮球 |
| POST_NOTIFICATIONS / VIBRATE | 播放控制通知、提醒通知、悬浮球震动反馈 |
| WAKE_LOCK | 播放与下载期间保持 Wi-Fi，避免锁屏后中断 |
| RECORD_AUDIO | 语音助手的离线识别 |
| SYSTEM_ALERT_WINDOW | 桌面歌词悬浮窗，需用户在系统设置中授权 |
| SCHEDULE_EXACT_ALARM / RECEIVE_BOOT_COMPLETED | 提醒的精确闹钟与开机后重排 |
| 包可见性声明 | 让首页自定义快捷位能拉起任意已安装 App |

应用没有账号体系。收藏、历史、记账数据、崩溃日志和设置仅保存在本机；MD 文档只会读取用户主动授权的目录中的文件，不会主动上传。

## 免责声明

音乐、影视和小说内容来自第三方公开接口或用户自行配置的书源与订阅，本项目不内置、不打包、不分发受版权保护的音视频或文字内容。接口可用性与返回内容由外部服务决定，能拿到播放地址不代表已获得内容的再分发授权。公开 APK 前请自行确认相关版权与平台条款。
