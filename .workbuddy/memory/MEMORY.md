# MEMORY.md — 从前慢（GDMusicPlayer-Android-v3）
> 精简索引版 2026-11-10（原 17.8KB 注入截断，已压缩）。**实现细节 / 坐标 / 日志全文见 `.workbuddy/memory/YYYY-MM-DD.md`（本地可读，git 忽略）**。本文件只留铁律 + 定案 + 索引。**§5 收尾流程强制。**

## 1. 构建 / git
- 构建：`"C:\Users\linghit\.jdks\jbr-17.0.14\bin\java.exe" -cp "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain assembleDebug --console=plain`（用户要新包加 `clean`，约 2m20s）。必须 `dangerouslyDisableSandbox`；**不要管道 tail**（沙箱失败会假成功），只以 APK 时间戳验真。`cmd //c gradlew.bat` 假执行、`bash gradlew` 被拦、PATH 无 java。
- 装机：`dumpsys package com.linghit.gdmusic | grep versionCode|versionName` 对齐 → `adb install -r -d`；58MB+ 大包加 `-t --no-streaming`；掉线先 `adb connect 127.0.0.1:21503`。
- 远程 github.com/chillaxor/music.git (main)，**public 仓库**含第三方 jar 已永久留痕。用户主动 `git reset --hard origin/main` 是刻意对齐，**不要从 reflog 复活**；以远端 main 为基线只加当前需求。`git fetch` 可能假成功，先 `ls-remote`；push 用 `git -c http.sslBackend=openssl -c http.version=HTTP/1.1 push origin main`，curl 502 会假报 `Everything up-to-date`，**必须 `git ls-remote origin HEAD` 验真**。
- ⚠️ **同文件多条 Edit 禁止并行发**（并发写静默丢），改完 grep 复核。`.gitignore` 只放行本文件，每日日志与私人数据（如 `净资产快照.json`）继续忽略。

## 2. 视频播放器（改动前必读；细节见 11-05 / 11-09 日志）
- 控制条覆写 `res/layout/exo_player_control_view.xml`；进度条定位**只能覆写 dimen `exo_styled_progress_margin_bottom`**。片头/片尾跳过 + `VideoSkipStore` 按影片详情维度存；`skipEndArmed` 必须武装。
- 定案「去黑底 + 进度条贴配置行上边 + 显示总时长」：`exo_bottom_bar` 透明；dimen 进度条 42dp / `exo_bottom_bar` marginBottom 90dp / `exo_minimal_controls` 92dp / `skipCfgRow` 4dp 贴底。内嵌起播触发 minimal → `exo_progress` bottomMargin 被置 0，修法 `VideoActivity.onCreate` 用 `addOnPreDrawListener` 纠正。`exo_time` 提到 `<merge>` 顶层（marginBottom 92dp）。全屏键 `videoFull` 右下 marginEnd 8dp / marginBottom 4dp（XML + `positionSideBtns()` 一致）。
- 倍数：`skipCfgRow` 第 4 chip `speedBtn`，点它 `PopupMenu(this, speedBtn, Gravity.END)` 就地弹菜单（不要 AlertDialog，被否过）。档位 `[0.5,0.75,1,1.25,1.5,2,3]`；`ensurePlayer()` 与 `playMediaUrl()` 都重设 `playbackSpeed`。长按临时 3×，松手回档；ACTION_UP 先置 `longPressActive=false`。
- 切集必须先判直链：`isPlayableUrl()` → `safeEval(__resolveDirectAt(i,b64))` → JS `sniff` → `Android.onEpResolved()` → `finishSwitch()`；`pendingResolveIdx` 丢过期回传。爬虫源走 `__playSpiderAt`。
- 下载按钮 `videoDownload` **当前仓库没有**（曾引入被 reset）；重做法＝ACTION_VIEW + URL + extra_filename 甩系统下载器，Manifest `<queries>` 声明。
- 小窗：`isCompactInline = inlineMode && !inlineExpanded`；`applyPlayerLayout()` 末尾 `updatePipButton()`；进小窗前 `clearInlineDock()`。耳机断开停播已做；⚠️ **AudioFocus 全项目 0 命中**。
- ⚠️ 默认 2 条订阅线路（`VideoActivity.DEFAULT_SOURCES`，v3 起）：`盒子迷多仓=https://盒子迷.top/禁止贩卖`（91 站）、`盒子迷动漫仓=https://盒子迷.top/摸鱼`（301→带尾斜杠，108 站）。UA 必须 `okhttp/3.15.0`；`SOURCES_VER=3` 仅用户没动过时替换。影视仓 `moyu` 那条填 /禁止贩卖、`dd` 才是摸鱼。
- ⚠️ 11-09 两项大修：① `assets/tvbox/` 内置 42 文件 / 9.95MiB（订阅、11 dex jar〔名=`md5(jarUrl)`〕、25 js lib、2 js + 3 py、manifest）；接线＝`SpiderEngine.ensureJar()` 内置优先跳过 md5、`httpGet` 支持 `asset://`、`video.html` 在线失败回退 + `builtinSubFor()`、`collectSpiderSites()` 用站点自带 `s.jar`（47 站）豁免 `SPIDER_DENY`。APK +8.8MB（现约 60MB）。② 播放链路三断点：根因 `playerContent` 返 `parse:1`（iframe 页）后影视仓走 parses + WebView 嗅探 + 整包 header，我方原只有静态 GET + 4 正则且只透传 Referer；修法＝`STATE.srcCfg[i]` 保留 parses/flags/rules、`ensureBuiltInLoaders()` 冷启动 11 jar 建类表、`resolveViaParses()` 遍历 parses 失败回退 `sniff()`、`playResolvedEx + curHeaders → playMediaUrl` 全量注入（过滤 HOP_BY_HOP，403 去 Referer）。验收 `[67,68]` / parses `[15,13]`、E2E → `STATE_READY`。
- **未闭环**：`flags`(33)/`ijk`/`hosts`/`doh`/`playerType` 只读取未消费；`.js`/`.py` 源未接 QuickJS/Chaquopy，相对路径脚本订阅 404 未打包；金牌 `hkybqufgh.com` TLS 失败 / 骚火 `shdy2.com` 522；全网 99 源搜索未在 UI 跑通；悬浮球 Android 10+ 边缘返回手势发 ACTION_CANCEL（推测），根治＝主球撑成覆盖扇形大窗 + `FLAG_NOT_TOUCH_MODAL`。

## 3. UI 版式 / 主题
- 首页 `activity_main.xml`：暗色玻璃拟态 + 每日一图 + 白字；顶栏 → 玻璃页签 → NestedScrollView{问候行 → 2 排双列信息卡 92dp → shortcutRow FlowLayout perRow=6} → playerBar 钉底。固定入口 7 个。`dimOverlay` 常驻但 Kotlin 零引用。
- 玻璃卡 `bg_liquid_glass` 三层：亮边 `#A8→#33→#08FFFFFF` / **本体 `#301C1A18`**（调透只动这层；`#30` 近裸白字可读下限）/ 高光；**8 处卡共用**。药丸 `bg_pill_glass` `#26→#1A`、`bg_pill_purple`、`bg_pill_white`。`bg_glass_card.xml` 0 引用。
- 主题 12 套纯色+渐变，`ThemePrefs.PACKS` + `theme_idx`；`applyTheme(this)` 必须早于 setContentView；渐变在 `MainActivity.applyThemeColors()` 画。用户不要暗色/形状/质感维度。
- 快捷位拖动：拖动中只 translation + highlightSlot 预览，松口才改序；`HomeShortcutStore.Item` 比 `id()`；超 touchSlop 才算 dragging。launcher 图标＝用户圆形书法徽章（`tools/make_launcher_icon.py`）。

## 4. 其他定案 / 接口
- 加水印**保留**、去水印**彻底删除**；`ImagePrep` 仍在用不可删。读书板块已回滚（要「看内容」不是管元数据）。链接页只允许手动配置的链接唤起外部 App（清单 `链接页受信唤起改造清单.md` 未执行）。搜索 tab「今日榜单」只做发现层，播放下载仍走 music-api.gdstudio.xyz。
- 每日一诗折叠 52dp / 展开整首+译文+分享图+收藏，`PoemStore` 按天缓存。记账：**还款一律「转账」**（不计收支）、禁止「收入记到负债账户」、三处收支查询统一「日/月/年范围 ∩ 筛选条件」、顶部 4 卡 2×2；已有净资产快照 / 年度总结 / JSON 备份 / 桌面歌词浮层。
- 免 key 接口（11-04 实测）✅ 今日诗词 `v2.jinrishici.com`｜open-meteo + geocoding｜必应 HPImageArchive｜网易云榜单 `music.163.com/api/playlist/detail?id=`（UA+Referer；热歌 3778678/飙升 19723756/新歌 3779629/原创 2884035/ACG 71385702）+ `api/song/lyric`｜hitokoto｜MyMemory｜radio-browser｜豆瓣 rexxar 图书系。❌ 网易云个性化推荐 404｜gushi.ci｜apiopen.top 变 SPA｜Open Library 超时｜Google Books 配额 0｜豆瓣 movie_hot 400。纪律：内容型需求先 curl 实测再写进推荐。
- 瘦身盘点（11-04 只读未执行）：`tensorflow-lite:2.14.0` 孤儿依赖可删省 3.8MB；入口缺口 LyricActivity / VideoHistoryActivity / NewsActivity；快捷位 target 散在 4 处；MainActivity.kt 2081 行过载。

## 5. 每次新功能 / 修复后的固定收尾流程（强制，用户 2026-11-04 原话）
**改完代码不算完，必须走完 ①~⑦ 才能回报。**
① **连模拟器**：每条 Bash 重新 `export MSYS_NO_PATHCONV=1`；`ADB="/c/Users/linghit/AppData/Local/Android/Sdk/platform-tools/adb.exe"`、`DEV="127.0.0.1:21503"`、`"$ADB" devices`。adb 不在 PATH；机型 NX809J Android 9；所有 adb 带 `-s "$DEV"`，pull 目标写 `C:/...`。
② **构建**：增量 `assembleDebug`，要新包用 `clean assembleDebug`；构建前 `git status --short`。
③ **装机 + 内容校验**：`install -r -d` → `dumpsys package` 对版本 → Python `zipfile` 读 APK 内 `assets/*.html`/dex 关键字。资源/dimen 改动用 `build-tools/34.0.0/aapt2.exe dump resources <apk>` 与 `dump xmltree <apk> --file res/layout/xxx.xml`。
④ **实测验收（能自动验的都自动验）**
- 冒烟：`am force-stop` + `am start -n com.linghit.gdmusic/.SplashActivity` → `dumpsys window | grep mCurrentFocus` → logcat 扫 `FATAL EXCEPTION`。
- WebView 逻辑：Node `vm` 抽函数造样本单测。多数据态用 CDP：debug 包开 `setWebContentsDebuggingEnabled(true)` → `/proc/net/unix` 找 `@webview_devtools_remote_<pid>` → `adb forward tcp:9222 localabstract:<socket>` → `http://127.0.0.1:9222/json/list` 取 `webSocketDebuggerUrl` → Python 手写 WS 调 `Runtime.evaluate`（脚本 `C:/Users/linghit/AppData/Local/Temp/gd_cdp_eval.py`，第二参按 url 选页）。**只改内存不调 save()，force-stop 即恢复**；默认 timeout 10s，长调用改 90~120s。
- 布局：`uiautomator dump --compressed /sdcard/w.xml` + pull + Python 正则解析 bounds。⚠️ `dumpsys display` 带 FLAG_SECURE → 截图恒 9074B 纯白，别折腾截图，用控件树 + logcat；需视觉时用 dump 坐标还原示意图并标「非截图」。
- 交互：`logcat -c` → 按 dump 坐标 `input tap` / `input swipe x y x y 2500` → `logcat -d -t 500 | grep` 看业务日志。报错自己上模拟器复现，不凭代码猜。⚠️ 逍遥 `input text` 会合并相同字符 → `ime disable` 后输入 + 点击再 `ime enable`。
- 视频真播：`file:///sdcard/x.mp4` 会 EACCES → `adb push /data/local/tmp/` + `run-as com.linghit.gdmusic cp ... files/x.mp4`，播 `file:///data/data/com.linghit.gdmusic/files/x.mp4`。`am start .SplashActivity` 后等 5s 再起 VideoActivity 确认焦点；控制条 5s 自动隐藏 → 轻点唤出，循环重试 8 轮取坐标。
⑤ **归档 + 交付**：`cp app-debug.apk "从前慢-vX-说明-MMDD-HHMM.apk"` → `present_files` → 追加当日日志。
⑥ **推送**：`git status --short` 核清单 → **只 `git add` 本次相关文件，绝不 `git add -A`** → `commit` → `git -c http.sslBackend=openssl -c http.version=HTTP/1.1 push origin main` → `ls-remote` 验真。含个人数据的文件一律不放行。
⑦ **汇报三段式**：改了什么 / 验到什么（贴实测数据、坐标、日志原文）/ **没验到什么**（如实标注）。
