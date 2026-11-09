# MEMORY.md — 从前慢（GDMusicPlayer-Android-v3）
> 精简重写 2026-11-09（原文件注入被截断，7 节合并为 5 节）。§5 收尾流程强制，改完代码不算完。

## 1. 构建 / git 铁律
- 构建：`"C:\Users\linghit\.jdks\jbr-17.0.14\bin\java.exe" -cp "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain assembleDebug --console=plain`（要新包加 `clean`，约 2m21s）。必须带 `dangerouslyDisableSandbox`（gradle 锁）；**不要管道到 tail**（沙箱失败会假成功），只以 APK 时间戳验真。`cmd //c gradlew.bat` 假执行、`bash gradlew` 被拦；PATH 无 java。
- 设备实装版本可能高于仓库 HEAD：构建前 `dumpsys package com.linghit.gdmusic | grep -E "versionCode|versionName"` 对一下；装不上加 `adb install -r -d`。
- ⚠️ **用户会主动 `git reset --hard origin/main` 丢弃本地分支，是刻意对齐 —— 不要从 reflog 复活被丢提交**（11-04 被明确否决）。以远端 main 为基线，只加当前需求。
- 远程 github.com/chillaxor/music.git (main)，`http.sslBackend openssl` 已固化；`git fetch` 可能假成功，先 `ls-remote`。push 报 `curl 7 ...502` / `unexpected disconnect` / `TLS unexpected eof` → `git -c http.sslBackend=openssl -c http.version=HTTP/1.1 push origin main`；curl 502 会假报 `Everything up-to-date`，**必须 `git ls-remote origin HEAD` 验真**。
- worktree：`git branch -vv` 带 `+` = 被检出，删前 `git worktree remove` + status/patch/stash 双保险 + `-d`。⚠️ **同文件多条 Edit 禁止并行发**（并发写静默丢），改完 grep 复核。

## 2. 视频播放器（核心，改动前必读）
- 控制条覆写 app 级 `res/layout/exo_player_control_view.xml`；**进度条定位靠覆写 dimen `exo_styled_progress_margin_bottom`**（改 XML margin 会被 PlayerControlViewLayoutManager 覆盖）。片头/片尾跳过两按钮 + `VideoSkipStore` 按影片详情维度存、同片所有集共用；`skipEndArmed` 必须武装（切集 currentPosition 不立刻重置，否则 16ms 空跳）。
- ⚠️ **11-09 定案「进度条在配置行上边 + 显示总时长」**：详情页内嵌起播触发 media3 minimal 模式 → layout manager 把 `exo_progress` bottomMargin 强制 0（进度条贴底压住 `skipCfgRow`），并把 `exo_bottom_bar` 设 INVISIBLE（原塞在里面的 `exo_time` 总时长一起消失）；全屏态反而正常。修法：`VideoActivity.onCreate` 用 `playerView.viewTreeObserver.addOnPreDrawListener` 把 bottomMargin 纠正回 `exo_styled_progress_margin_bottom`(42dp)；`exo_time` 从 `exo_bottom_bar` 提到 `<merge>` 顶层（`bottom|start`/marginBottom=92dp），ID 不变 media3 照常 setText。分隔符改 `/`、总时长纯白 16sp 加粗。dimen 42/90/92 保持 11-05 定案不动。实测内嵌 900×431：时间行 217~258 → 进度条 262~352 → 配置行 359~423；全屏 `00:01 / 00:52`。
- ⚠️ 11-05 定案「去黑底 + 进度条下移贴配置行」：media3 `exo_bottom_bar_background=#b0000000` 叠 `exo_controls_background=#98000000` → 更黑通栏，`exo_bottom_bar` 背景改透明；dimen `exo_styled_progress_margin_bottom` 108→**42dp**，`exo_bottom_bar` marginBottom 40→**90dp**、`exo_minimal_controls` 44→**92dp**，`skipCfgRow` 仍 4dp 贴底。从底往上：配置行 4~38dp → 进度条 42~90dp → 时间/按钮行 90~150dp。实测全屏 1600×900 `exo_progress [0,731][1600,821]`、配置行 `[553,828][1047,892]`、时间 `[19,656][102,692]`、设置键 `[1506,629][1596,719]`。
- ⚠️ 全屏键 `videoFull` 贴右下角：`marginEnd=8dp / marginBottom=4dp`（XML + `positionSideBtns()` 两处一致）；实测全屏 `[1521,829][1585,893]`、内嵌 900×476 `[821,405][885,469]`。
- 倍数（11-06 定案，用户要「一个倍数按钮 + 在倍数那里弹菜单选倍数」）：`skipCfgRow` 第 4 chip `speedBtn`（文案 `倍数 1.0×` 实时显示），点它 `android.widget.PopupMenu(this, speedBtn, Gravity.END)` **就地弹菜单**，不要 AlertDialog（做过被否）。档位 `[0.5,0.75,1,1.25,1.5,2,3]`、当前档打 ✓；`fmtSpeed` 用 `%.2f` 去尾零；`playbackSpeed` 记档，`ensurePlayer()` 与 `playMediaUrl()` 都重新 `setPlaybackParameters`。长按屏幕临时 3×（`videoSpeed` 指示器 top|center_horizontal/52dp/`bg_speed_pill`/`3.0×`，不要 ⏩ emoji），DOWN 记 `origSpeed`，松手 `restoreLongPressSpeed()` 回所选档。⚠️ ACTION_UP 先置 `longPressActive=false`，恢复日志必须打在 `wasLong` 分支。实测按钮内嵌 `[631,404][782,468]`、全屏 `[981,828][1132,892]`。
- 切集（⏮/⏭）**必须先判直链**：`epsJson` 存站点原始 url，量子/liangzi 是播放页 HTML，直接丢 ExoPlayer 会 `Source error`。流程 `isPlayableUrl()` → `safeEval(__resolveDirectAt(i,b64))` → JS `sniff` → `Android.onEpResolved()` → `finishSwitch()` 播放并回填 url；`pendingResolveIdx` 丢过期回传、`startVideo()` 重置；`playEp()` sniff 成功也回填。爬虫源走 `__playSpiderAt`。
- 下载按钮 `videoDownload`：**当前仓库没有**（曾随 f763c0c 引入被 reset）。日后重做：ACTION_VIEW + URL + extra_filename 甩系统选择器，IDM+/ADM，m3u8/mpd 也支持；Manifest `<queries>` 声明下载器包名 + VIEW intent（targetSdk 34）；Referer 403 手动补。
- 小窗：`inlineMode` 只表示「从详情页内嵌起播」，展开全屏后仍 true → 用 `isCompactInline = inlineMode && !inlineExpanded` 判形态；`applyPlayerLayout()` 末尾统一 `updatePipButton()`；进小窗前 `clearInlineDock()` 撤 230px 占位。11-04 去 🖼 emoji 与黑直角块，改「小窗」文字 + `@drawable/bg_glass_pill`。
- 耳机断开停播：`App.onCreate` 注册 ACTION_AUDIO_BECOMING_NOISY → PlayerManager.stop() + VideoFloatState.player?.pause()；VideoActivity 另注册覆盖前台（投屏中不暂停）。⚠️ AudioFocus 全项目 0 命中：PlayerManager:133 / VideoActivity:929 裸 `ExoPlayer.Builder().build()`，音乐视频会抢声音。

## 3. UI 版式 / 主题
- 首页 activity_main.xml（画 mockup 前必读）：暗色玻璃拟态 + 每日一图 + 白字。顶栏 → 玻璃页签 → NestedScrollView{问候行 → 2 排双列信息卡 92dp → 工具卡 shortcutRow FlowLayout perRow=6} → playerBar 滚动区外钉底。固定入口 7 个（记账/看片/收藏/播客/下载/问一句/水印）。
- 蒙层 `dimOverlay`（activity_main.xml:44-48）常驻全屏、Kotlin 零引用、不随每日一图切换；`bg_dim_gradient.xml` 三色标是线性插值（注释「中间 50% 全透明」错），11-05 试验版改纯透明 `#00000000`，恢复法见该文件头注释。
- 玻璃卡 `bg_liquid_glass` 三层：`#A8→#33→#08FFFFFF` 亮边 18dp / **`#301C1A18`(19%)** 本体 16.8dp inset 1.2dp / `#2B→#0A→#00` 高光 centerY 0.45；①③ 是亮边/高光，**调透只动 ②**。演进 `#8A`→`#70`→`#5A`→`#40`→`#30`；⚠️ `#30` 接近裸白字可读下限，再降要补 shadow。**8 处卡共用**。药丸：`bg_pill_glass` `#26`→`#1A`（activity_video 的 videoFull 也复用）、`bg_pill_purple` `#4A`→`#3A`、`bg_pill_white` `#FF`→`#EB`。`bg_glass_card.xml` 0 引用，改它无效。
- 主题：12 套纯色+渐变，`ThemePrefs.PACKS` + `theme_idx`；`applyTheme(this)` 必须早于 setContentView；渐变在 `MainActivity.applyThemeColors()` 画、换 header 后补底部 24dp 圆角。⚠️ 用户不要暗色/形状/质感维度。
- 快捷位拖动：拖动中不 remove/add，只 translation + highlightSlot 预览，松口才改序；`HomeShortcutStore.Item` 无 equals 比 `id()`；超过 touchSlop 才算 dragging；shortcutRow clipChildren/Padding=false。launcher 图标 = 用户圆形书法徽章，`tools/make_launcher_icon.py` 按内切圆裁切。
- 校验坑：`android:text="字面量"` 编译后在 AXML(res/layout/*.xml)，不在 resources.arsc。

## 4. 其他定案 / 接口 / 盘点
- 加水印**保留**（WatermarkActivity/Engine + page/watermark + ShortcutRouter + Manifest），去水印**彻底删除**；`ImagePrep` 仍在用不可删。读书板块已回滚（要「看内容」不是管元数据 + 嫌中文书少）；这类需求先问「内容消费还是元数据管理」。
- 链接页唤起外部 App：只允许手动配置的链接唤起，https 网页内部跳转不给跳；清单见根目录 `链接页受信唤起改造清单.md`（未执行）。搜索 tab「今日榜单」：网易云只做发现层，播放/下载仍走 music-api.gdstudio.xyz（VIP 403）；首次 switchTab(true) 懒加载。
- 每日一诗：问候行下方，折叠 52dp / 展开整首+译文+分享图+收藏；PoemStore 按天缓存，展开态只在换诗时重置为默认。
- 记账口径（11-04 定案）：**还款一律用「转账」**（银行卡→信用卡/花呗），既不计支出也不计收入；禁止「收入记到负债账户」（提交校验拦截 + 汇总排除历史旧数据）。三处收支查询统一「日/月/年范围 ∩ 筛选条件」取交集。11-05 顶部 4 卡改 2×2（标签数字同行、长金额自动收字号、总高 63px）。
- 免 key 接口（11-04 实测）✅ 今日诗词 v2.jinrishici.com(/token→/one.json 带 X-User-Token)｜open-meteo + geocoding-api｜必应 HPImageArchive.aspx｜网易云榜单 `music.163.com/api/playlist/detail?id=`（需 UA+Referer；热歌 3778678/飙升 19723756/新歌 3779629/原创 2884035/ACG 71385702）+ `api/song/lyric`｜hitokoto｜MyMemory｜radio-browser｜豆瓣 rexxar 图书系。❌ 网易云个性化推荐 404｜gushi.ci｜apiopen.top 变 SPA｜Open Library 超时｜Google Books 配额 0｜api.douban.com 400｜豆瓣 movie_hot 400。纪律：内容型需求先 curl 实测再写进推荐，标 ✅/❌；别把「内容 API 死了」泛化成「什么都本地做」。
- 瘦身盘点（11-04 只读，未执行）：APK 49.5MB，native 壳 34.7MB（liblitertlm_jni 22MB + libvosk 8.9MB + libtensorflowlite_jni 3.8MB），dex 25.6MB；`tensorflow-lite:2.14.0` 孤儿依赖（唯一使用者 LaMaInpaint.kt）可删省 3.8MB。入口缺口：LyricActivity 仅队列页、VideoHistoryActivity 仅 VideoActivity 内部、NewsActivity 仅首页新闻卡；快捷位 target 散在 4 处无枚举；MainActivity.kt 2081 行过载。

## 5. 每次新功能 / 修复后的固定收尾流程（强制，用户 2026-11-04 原话「把这个写到记忆里 每次新功能都这样操作」）
**改完代码不算完，必须走完 ①~⑦ 才能回报。**
① **连模拟器**：每条 Bash 都重新 `export MSYS_NO_PATHCONV=1`（否则 `/sdcard/x` 被转成 PortableGit 路径，screencap/dump/pull 静默失败）；`ADB="/c/Users/linghit/AppData/Local/Android/Sdk/platform-tools/adb.exe"`、`DEV="127.0.0.1:21503"`、`"$ADB" devices`（有 device 即可，空了才 connect）。adb 不在 PATH；端口逍遥 21503(多开+10)/雷电 5555/MuMu 7555/夜神 62001；机型 NX809J Android 9。pull 目标写 `C:/...`，所有 adb 带 `-s "$DEV"`。
② **构建**：增量 `assembleDebug`，用户说「重新 build / 要新包」用 `clean assembleDebug`；构建前 `git status --short` 核清单。
③ **装机 + 内容校验**：`install -r -d` → `dumpsys package | grep versionCode|versionName` → Python `zipfile` 读 APK 内 `assets/*.html`/dex 关键字确认改动打进包。**资源/dimen 类改动改用 `build-tools/34.0.0/aapt2.exe dump resources <apk>` 与 `dump xmltree <apk> --file res/layout/xxx.xml`**（AXML 不保留 `90dp` 字面量；xmltree 能看到 `layout_marginBottom=90.000000dp`）。
④ **实测验收（能自动验的都自动验）**
- 启动冒烟：`am force-stop` + `am start -n com.linghit.gdmusic/.SplashActivity` → `dumpsys window | grep mCurrentFocus` → logcat 扫 `FATAL EXCEPTION`。
- WebView 页逻辑：Node `vm` 抽函数造样本单测（月份边界、收支口径）。
- ⭐ WebView 多数据态实测：debug 包 `WebView.setWebContentsDebuggingEnabled(true)`，`/proc/net/unix` 找 `@webview_devtools_remote_<pid>` → `adb forward tcp:9222 localabstract:<socket>` → `http://127.0.0.1:9222/json/list` 取 `webSocketDebuggerUrl` → 本机无 ws 库，Python `socket+base64+os+struct` 手写 WS 客户端调 `Runtime.evaluate`（脚本 `C:/Users/linghit/AppData/Local/Temp/gd_cdp_eval.py`，第二参按 url 选页），注入样本后 `getBoundingClientRect()` 拿 CSS 坐标。**只改内存不调 save()，force-stop 即恢复**。
- 布局：`uiautomator dump --compressed /sdcard/w.xml` + pull，Python 正则解析 bounds 验坐标。⚠️ 逍遥 `screencap` 静止/冷启动恒返回 9KB 纯白；有效条件 Python `subprocess.run([ADB,'-s',DEV,'exec-out','screencap','-p'],capture_output=True).stdout` 直接接二进制 + 先触发真实重绘。⚠️ **11-06 复核：`dumpsys display` 若带 `FLAG_SECURE`（实测 app 1600x900/density 300）系统级禁截屏 → 恒 9074B 纯白，别再折腾截图，用控件树 + logcat 验收，需要视觉时用 dump 坐标还原示意图并标「非截图」**。dump 在 WebView+Surface 混合时偶发 SIGKILL，重试一次。
- 交互：`logcat -c` → 按 dump 坐标 `input tap` / 长按 `input swipe x y x y 2500` → `logcat -d -t 500 | grep` 看业务日志。报错自己上模拟器复现，不凭代码猜、不来回问用户。
- ⚠️ 视频真播验收（11-06）：`file:///sdcard/x.mp4` 会 EACCES → `adb push x.mp4 /data/local/tmp/` + `run-as com.linghit.gdmusic cp /data/local/tmp/x.mp4 files/x.mp4`，播 `file:///data/data/com.linghit.gdmusic/files/x.mp4`（install -r 后仍在）。启动：`am start .SplashActivity` 后**等 5s** 再 `am start .VideoActivity`，`dumpsys window | grep mCurrentFocus` 确认焦点。`uiautomator dump --compressed` 丢隐藏节点，PlayerView 控制条 5s 自动隐藏 → 轻点画面唤出，循环重试 8 轮取坐标；长按期间 dump 常抓不到，用 logcat 作主证据。
⑤ **归档 + 交付**：`cp app-debug.apk "从前慢-vX-说明-MMDD-HHMM.apk"`（根目录带时间戳），`present_files` 给 APK；`.workbuddy/memory/YYYY-MM-DD.md` 追加当日日志。
⑥ **推送**：`git status --short` 核清单 → **只 `git add` 本次相关文件，绝不 `git add -A`**（工作区有未跟踪私人文件如 `净资产快照.json`）→ `commit` → `git -c http.sslBackend=openssl -c http.version=HTTP/1.1 push origin main` → `ls-remote` 验真。本仓库 **public**，`.gitignore` 只放行 `.workbuddy/memory/MEMORY.md`，每日日志与私人数据继续忽略；⚠️ 公开仓库永久留痕，含个人数据的文件一律不放行。
⑦ **汇报三段式**：改了什么 / 验到什么（贴实测数据、坐标、日志原文）/ **没验到什么**（如实标注），不要只报「完成」。
