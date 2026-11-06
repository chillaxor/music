# MEMORY.md — 从前慢（GDMusicPlayer-Android-v3）

> 精简重写于 2026-11-06（原文件超长被注入截断）。完整收尾流程见 §7，改完代码不算完。

## 1. 构建 / 版本 / git 铁律
- 构建：`"C:\Users\linghit\.jdks\jbr-17.0.14\bin\java.exe" -cp "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain assembleDebug --console=plain`；用户说「重新 build / 要新包」加 `clean`（实测 2m21s）。必须带 `dangerouslyDisableSandbox`（gradle 锁 `~/.gradle/caches/journal-1.lock`）；**不要管道到 tail**（沙箱失败会假成功），只以 APK 时间戳验真。`cmd //c gradlew.bat` 假执行、`bash gradlew` 被 wsl 黑名单拦；本机无 Android Studio、PATH 无 java。
- ⚠️ 设备实装版本可能高于仓库 HEAD：构建前 `dumpsys package com.linghit.gdmusic | grep -E "versionCode|versionName"` 对一下；装不上加 `adb install -r -d` 允许降级。
- ⚠️ **用户会主动 `git reset --hard origin/main` 丢弃本地分支，这是刻意的对齐动作 —— 不要把被丢弃的提交复活**（11-04 我从 reflog 找回 f763c0c 被明确否决）。正确做法：以远端 main 为基线，只加当前需求要改的东西。
- 远程 github.com/chillaxor/music.git (main)，仓库级已固化 `http.sslBackend openssl`；`git fetch` 可能假成功，先 `ls-remote`（偶发 TLS 抖动，重试一次）。
- ⚠️ push 报 `curl 7 CONNECT tunnel failed, 502` / `send-pack unexpected disconnect` / `TLS connect error: unexpected eof` → 用 `git -c http.sslBackend=openssl -c http.version=HTTP/1.1 push origin main` 一次成功。`curl 502` 那次末尾会假报 `Everything up-to-date`，**必须 `git ls-remote origin HEAD` 对比本地 HEAD 验真**。
- worktree：`git branch -vv` 带 `+` = 被检出，删分支前 `git worktree remove`；删前查 status + 导 patch/stash 双保险 + 用 `-d`。
- ⚠️ **同文件多条 Edit 禁止并行发**（并发写静默丢编辑），改完 grep 复核。

## 2. 免 key 接口实测（2026-11-04）
✅ 今日诗词 v2.jinrishici.com（/token → /one.json 带 X-User-Token）｜open-meteo + geocoding-api（中文城市可搜）｜必应壁纸 HPImageArchive.aspx｜网易云榜单 `music.163.com/api/playlist/detail?id=`（需 UA+Referer；热歌 3778678/飙升 19723756/新歌 3779629/原创 2884035/ACG 71385702）+ `api/song/lyric`｜hitokoto｜MyMemory 翻译｜radio-browser｜豆瓣 rexxar（图书系可用，影视 movie_hot 400）
❌ 网易云个性化推荐全 404｜gushi.ci｜apiopen.top 变 SPA｜Open Library 超时｜Google Books 无 key 配额 0｜api.douban.com 400
纪律：内容型需求先 curl 实测再写进推荐，标注「✅实测/❌已死」；别把「内容 API 死了」泛化成「什么都本地做」。

## 3. UI 版式基线
- 首页（activity_main.xml，**画 mockup 前必读**）：暗色玻璃拟态 + 每日一图 + 白字。顶栏 → 玻璃页签 → NestedScrollView{问候行 → 2 排双列信息卡 92dp → 工具卡 shortcutRow FlowLayout perRow=6} → playerBar 在滚动区外钉底。固定入口 **7 个**（记账/看片/收藏/播客/下载/问一句/水印）。
- 蒙层 `dimOverlay`（activity_main.xml:44-48）：常驻全屏 View，Kotlin 零引用，不随每日一图切换。原 `bg_dim_gradient.xml` 三色标 linear 是线性插值（25% 高度处仍压 18%、全屏均值 ≈18%，注释「中间 50% 全透明」是错的）；**11-05 试验版已改纯透明 solid `#00000000`**，恢复方法写在该文件头注释。
- 玻璃卡 `bg_liquid_glass` 三层：`#A8→#33→#08FFFFFF` 亮边 18dp / **`#301C1A18`(19%)** 本体 16.8dp 四边 inset 1.2dp / `#2B→#0A→#00` 高光 centerY 0.45；①③ 是白亮边/高光，**调透只动 ②**。演进 `#8A`→`#70`→`#5A`→`#40`→`#30`；⚠️ `#30` 已接近裸白字可读下限，再降须补 `shadowColor/shadowRadius`。**8 处卡共用**（诗卡/4 信息卡/工具卡/播放条/搜索卡）。药丸：`bg_pill_glass` `#26`→`#1A`（也被 activity_video.xml 的 videoFull 复用）、`bg_pill_purple` `#4A`→`#3A`、`bg_pill_white` `#FF`→`#EB`。`bg_glass_card.xml` 全项目 0 引用，改它无效。
- 主题：12 套纯色+渐变，`ThemePrefs.PACKS` + `theme_idx`；`applyTheme(this)` 必须早于 setContentView；渐变在 `MainActivity.applyThemeColors()` 画、换 header 后补底部 24dp 圆角。⚠️ 用户不要暗色/形状/质感维度。
- 快捷位拖动：拖动中不 remove/add view，只 translation + highlightSlot 预览，松口才改序；`HomeShortcutStore.Item` 无 equals → 比 `id()`；超过 touchSlop 才算 dragging；shortcutRow 需 clipChildren/Padding=false。
- launcher 图标 = 用户圆形书法徽章，脚本 tools/make_launcher_icon.py 按内切圆裁切。
- 校验坑：`android:text="字面量"` 在编译后 res/layout/*.xml（AXML），不在 resources.arsc。

## 4. 视频播放器（VideoActivity / exo_player_control_view.xml）
- 控制条覆写 app 级 `res/layout/exo_player_control_view.xml`；进度条定位靠覆写 dimen `exo_styled_progress_margin_bottom`（改 XML margin 会被 PlayerControlViewLayoutManager 覆盖）。片头/片尾跳过两按钮 + `VideoSkipStore` 按影片详情维度存、同片所有集共用；`skipEndArmed` 必须武装（切集 currentPosition 不立刻重置，否则 16ms 空跳）。
- ⚠️ **11-05 定案：去黑底 + 进度条下移贴配置行**——media3 `exo_bottom_bar_background=#b0000000` 叠 `exo_controls_background=#98000000` 出现更黑通栏 → `exo_bottom_bar` 背景改透明；dimen 108→**42dp**，`exo_bottom_bar` marginBottom 40→**90dp**、`exo_minimal_controls` 44→**92dp**，`skipCfgRow` 仍 4dp 贴底。从底往上：配置行 4~38dp → 进度条 42~90dp → 时间/按钮行 90~150dp。实测 1600×900 `exo_progress [0,731][1600,821]`、配置行 `[553,828][1047,892]`（间隙 7px）、时间 `[19,656][102,692]`、设置键 `[1506,629][1596,719]`，三层不重叠；旧值 108/40/44dp 作废。
- ⚠️ **11-05 全屏键 `videoFull` 真贴右下角**：`marginEnd=8dp / marginBottom=4dp`（XML + `positionSideBtns()` 两处一致，内嵌/全屏态都 4dp；旧值 54/112dp 作废），与贴底居中的配置行同一水平带、不重叠。实测全屏 `[1521,829][1585,893]`；内嵌 900×476 `[821,405][885,469]`。
- 切集（右侧 ⏮/⏭）**必须先判直链**：`epsJson` 存站点原始 url，量子/liangzi 是播放页 HTML。11-05 前 `playIndex()` 直接丢给 ExoPlayer → `Source error`。现在 `isPlayableUrl()` → `safeEval(__resolveDirectAt(i,b64))` → JS `sniff` → `Android.onEpResolved()` → `finishSwitch()` 播放并回填 `epsList[i].url`；`pendingResolveIdx` 丢弃过期回传、`startVideo()` 重置；`playEp()` 起播 sniff 成功后也回填 epsJson。爬虫源仍走 `__playSpiderAt`。
- **倍数（11-06 定案，用户明确要「一个倍数按钮 + 在倍数那里弹菜单选倍数」）**：控制条 `skipCfgRow` 第 4 个 chip `speedBtn`（文案 `倍数 1.0×`，实时显示当前档），点它用 `android.widget.PopupMenu(this, speedBtn, Gravity.END)` **就地弹菜单**，不要用屏幕居中的 AlertDialog（曾做成对话框被用户否掉）。档位 `speedOptions=[0.5,0.75,1,1.25,1.5,2,3]`，当前档打 ✓；`fmtSpeed` 用 `%.2f` 去尾零（`0.5×` 不是 `0.50×`）；`playbackSpeed` 记住档位，`ensurePlayer()` 与 `playMediaUrl()` 都重新 `setPlaybackParameters`，换集/重建 player 不丢。长按屏幕不动仍是临时 3×（`videoSpeed` 指示器 `top|center_horizontal`/52dp/`bg_speed_pill`、文案 `3.0×`、不要 ⏩ emoji），DOWN 时 `origSpeed=playbackSpeed`，松手 `restoreLongPressSpeed()` 回到所选档。⚠️ ACTION_UP 会先把 `longPressActive` 置 false，恢复日志必须打在 `wasLong` 分支里，放 restore 内部永远不触发。实测：内嵌 900×476 按钮 `[631,404][782,468]`、全屏 1600×900 `[981,828][1132,892]`；菜单右对齐锚点，内嵌态向下展开、全屏态向上展开（系统按剩余空间自动选，都贴着按钮）；日志 `倍速 -> 2.0×` / `长按倍速进入 3.0×（松手回到 2.0×）` / `长按倍速结束，恢复 -> 2.0×`。
- 下载按钮 `videoDownload`：**当前仓库没有**（曾随 f763c0c 引入、被用户 reset 掉）。日后重做：甩系统选择器（ACTION_VIEW + URL + extra_filename），IDM+/ADM 处理，m3u8/mpd 也支持；Manifest 必须 `<queries>` 声明下载器包名 + VIEW intent（targetSdk 34）；Referer 403 需手动补。
- 小窗：`inlineMode` 只表示「从详情页内嵌起播」，展开全屏后仍 true → 用 `isCompactInline = inlineMode && !inlineExpanded` 判形态；`applyPlayerLayout()` 末尾统一调 `updatePipButton()`；进小窗前 `clearInlineDock()` 撤 230px 占位。11-04 去 🖼 emoji 与黑直角块，改「小窗」文字 + `@drawable/bg_glass_pill`。
- 耳机断开停播：`App.onCreate` 注册 ACTION_AUDIO_BECOMING_NOISY → PlayerManager.stop() + VideoFloatState.player?.pause()；VideoActivity 另注册覆盖前台（投屏中不暂停）。
- ⚠️ AudioFocus 全项目 0 命中：PlayerManager:133 / VideoActivity:929 裸 `ExoPlayer.Builder().build()`，音乐视频会抢声音。

## 5. 已定案 / 已否决
- 加水印**保留**（WatermarkActivity/Engine + 入口 page/watermark + ShortcutRouter + Manifest），去水印**彻底删除**；`ImagePrep` 仍在用不可删。
- 读书板块已回滚（用户要「看内容」不是管元数据 + 嫌中文书少）；这类需求先问「内容消费还是元数据管理」。
- 链接页唤起外部 App：只允许手动配置的链接唤起，https 网页内部跳转不给跳；清单见根目录 `链接页受信唤起改造清单.md`（未执行）。
- 搜索 tab「今日榜单」：网易云只做发现层，播放/下载仍走 music-api.gdstudio.xyz（网易云播放地址 VIP 403）；首次 switchTab(true) 懒加载。
- 每日一诗：问候行下方，折叠 52dp / 展开整首+译文+分享图+收藏；PoemStore 按天缓存（跨天失效），展开态只在换诗时重置为设置默认值。
- 记账口径（11-04 定案）：**还款一律用「转账」**（银行卡 → 信用卡/花呗），既不计支出也不计收入；禁止「收入记到负债账户」（提交校验拦截 + 汇总排除历史旧数据）。收支查询三处（明细 / 顶部收支卡 / 底部汇总）统一「日/月/年范围 ∩ 筛选条件」取交集，筛选不再豁免日期。11-05 顶部 4 卡改 **2×2**（标签数字同行、长金额自动收字号、总高 63px）。

## 6. 瘦身 / 结构现状（2026-11-04 只读盘点，未执行）
- APK 49.5MB：liblitertlm_jni.so 22MB + libvosk.so 8.9MB + libtensorflowlite_jni.so 3.8MB = 34.7MB native 壳（模型不进包、按需下载）；dex 25.6MB。
- `tensorflow-lite:2.14.0` 是孤儿依赖：唯一使用者 `LaMaInpaint.kt`（配套 BrushView.kt / InpaintEngine.kt 已无引用），可删 → 省 3.8MB。
- 入口缺口：LyricActivity 仅队列页可进；VideoHistoryActivity 仅 VideoActivity 内部；NewsActivity 仅首页新闻卡。快捷位 target 字符串散在 4 处，无统一枚举。MainActivity.kt 2081 行职责过载。

## 7. 每次新功能 / 修复后的固定收尾流程（强制，用户 2026-11-04 定案）
用户原话「把这个写到记忆里 每次新功能都这样操作」。**改完代码不算完，必须走完 ①~⑦ 才能回报。**

① **连模拟器**（adb server 已挂着，不用真连）
```bash
export MSYS_NO_PATHCONV=1
ADB="/c/Users/linghit/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="127.0.0.1:21503"
"$ADB" devices   # 有 `127.0.0.1:21503 device` 即可；空了才 connect
```
`adb` 不在 PATH，只能用 SDK platform-tools 绝对路径；端口：逍遥 21503（多开 +10）、雷电 5555、MuMu 7555、夜神 62001。机型 NX809J / Android 9。⚠️ **每条 Bash 命令都要重新 export `MSYS_NO_PATHCONV=1`**（shell state 不跨命令），否则 `/sdcard/x` 被转成 PortableGit 本地路径，`screencap`/`dump`/`pull` 全静默失败。`pull` 目标写 Windows 风格 `C:/...`；所有 adb 带 `-s "$DEV"`。

② **构建**：常规增量 `assembleDebug`，用户说「重新 build / 要新包」用 `clean assembleDebug`。构建前 `git status --short` 核改动清单，别把未知改动带进包。

③ **装机 + 内容校验**：`install -r -d` → `dumpsys package | grep versionCode|versionName` → Python `zipfile` 读 APK 内 `assets/*.html` / dex 关键字，确认本次改动**真的打进包**。**资源 / dimen 类改动改用 `build-tools/34.0.0/aapt2.exe dump resources <apk>` 与 `dump xmltree <apk> --file res/layout/xxx.xml` 验真**（AXML 不保留 `90dp` 字面量，zipfile 搜字符串全 False；xmltree 能看到 `layout_marginBottom=90.000000dp`）。

④ **实测验收（能自动验的都自动验）**
- 启动冒烟：`am force-stop` + `am start -n com.linghit.gdmusic/.SplashActivity` → `dumpsys window | grep mCurrentFocus` → logcat 扫 `FATAL EXCEPTION`。
- WebView 页逻辑：Node `vm` 抽函数造样本数据单测（月份边界、收支口径）。
- ⭐ **WebView 多数据态实测**：debug 包开了 `WebView.setWebContentsDebuggingEnabled(true)`，`/proc/net/unix` 找 `@webview_devtools_remote_<pid>` → `adb forward tcp:9222 localabstract:<socket>` → `http://127.0.0.1:9222/json/list` 取 `webSocketDebuggerUrl` → 本机无 `ws` 库，用 Python `socket+base64+os+struct` 手写 WS 客户端调 `Runtime.evaluate`（脚本可放 `C:/Users/linghit/AppData/Local/Temp/gd_cdp_eval.py`，第二个参数按 url 选页面），注入样本后 `getBoundingClientRect()` 拿精确 CSS 坐标。**只改内存、不调 save()，force-stop 即恢复**。
- 布局：`uiautomator dump --compressed /sdcard/w.xml` + `pull`，Python 正则解析 `bounds` 验坐标。⚠️ 这台逍遥模拟器 `screencap` 静止/冷启动后恒返回 9KB 纯白 PNG；有效条件：① Python `subprocess.run([ADB,'-s',DEV,'exec-out','screencap','-p'],capture_output=True).stdout` 直接接二进制；② **先触发一次真实重绘**（切搜索页再切回首页）后截。截图只作补充，dump 坐标才是主力；dump 在 WebView+Surface 混合时偶发被 SIGKILL，重试一次即可。
- 交互：`logcat -c` → 按 dump 坐标 `input tap` / 长按用 `input swipe x y x y 2500` → `logcat -d -t 500 | grep` 看业务日志。报错自己上模拟器复现，不凭代码猜、不来回问用户。
- ⚠️ **视频播放器真播验收（11-06 踩坑）**：`file:///sdcard/x.mp4` 会 `EACCES`（App 无外部存储权限，`chmod 666` 在 FUSE 上无效）→ 用 `adb push x.mp4 /data/local/tmp/` + `run-as com.linghit.gdmusic cp /data/local/tmp/x.mp4 files/x.mp4`，播 `file:///data/data/com.linghit.gdmusic/files/x.mp4`（install -r 后仍在）。启动顺序：`am start .SplashActivity` 后**必须等 5s**（闪屏延时会拉起 MainActivity 盖住影视仓，否则 dump 抓成首页控件树）再 `am start .VideoActivity`，并用 `dumpsys window | grep mCurrentFocus` 确认焦点。`uiautomator dump --compressed` 会丢掉隐藏节点，而 PlayerView 控制条 5s 自动隐藏 → 轻点画面唤出，循环重试 8 轮取坐标；长按期间 dump 常抓不到，用 logcat 日志作主证据。

⑤ **归档 + 交付**：`cp app-debug.apk "从前慢-vX-说明-MMDD-HHMM.apk"`（根目录带时间戳），`present_files` 给 APK；`.workbuddy/memory/YYYY-MM-DD.md` 追加当日日志。

⑥ **推送**：`git status --short` 核清单 → **只 `git add` 本次相关文件，绝不 `git add -A`**（工作区常有未跟踪私人文件如 `净资产快照.json`）→ `commit` → `git -c http.sslBackend=openssl -c http.version=HTTP/1.1 push origin main` → `ls-remote` 验真。本仓库 **public**，`.gitignore` 只放行 `.workbuddy/memory/MEMORY.md`，每日日志与私人数据继续忽略；⚠️ 公开仓库永久留痕，含个人数据的文件一律不放行。

⑦ **汇报三段式**：改了什么 / 验到什么（贴实测数据、坐标、日志原文）/ **没验到什么**（如实标注），不要只报「完成」。
