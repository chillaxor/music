# MEMORY.md — 从前慢（GDMusicPlayer-Android-v3）

## 1. 构建 / 版本铁律
👉 **每次新功能 / 修复的完整收尾流程见 §7（用户 2026-11-04 定案，强制）**，改完代码不算完。
- 构建：`"C:\Users\linghit\.jdks\jbr-17.0.14\bin\java.exe" -cp "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain assembleDebug --console=plain`；用户说「重新 build / 要个新包」时加 `clean`（`clean assembleDebug`，避免增量复用旧产物，实测 2m21s / 38 task 全执行）。必须带 `dangerouslyDisableSandbox`（gradle 锁 `~/.gradle/caches/journal-1.lock`）；**不要管道到 tail**（沙箱失败会假成功），只以 APK 时间戳验真。`cmd //c gradlew.bat` 假执行、`bash gradlew` 被 wsl 黑名单拦；本机无 Android Studio、PATH 无 java。
- ⚠️ **设备实装版本可能高于仓库 HEAD**：构建前 `adb shell dumpsys package com.linghit.gdmusic | grep -E "versionCode|versionName"` 对一下。当前仓库 versionCode=2 / 2.0，手机上曾装过 3 / 2.5（那是用户自己 reset 掉的分支产物）→ 装不上的话加 `adb install -r -d` 允许降级。
- ⚠️ **用户会主动 `git reset --hard origin/main` 丢弃本地分支，这是刻意的对齐远端动作 —— 不要把被丢弃的提交复活**。11-04 我曾从 reflog 找回 f763c0c 并合入，用户明确否决（"本来我就是强制拉取覆盖本地的你又给我弄上去了"）。**正确做法：以 origin/main 为基线，只加当前需求要改的东西。**
- 远程 github.com/chillaxor/music.git (main)。Windows schannel 对 GitHub 握手失败 → 仓库级已固化 `http.sslBackend openssl`；`git fetch` 可能假成功，先 `ls-remote`；本机 `timeout` 是 Windows 交互命令。
- worktree：`git branch -vv` 带 `+` = 被检出，删分支前 `git worktree remove`；删前查 status + 导 patch/stash 双保险 + 用 `-d`。
- ⚠️ **同文件多条 Edit 禁止并行发**（并发写静默丢编辑），改完 grep 复核。

## 2. 免 key 接口实测（2026-11-04 复测）
✅ 今日诗词 v2.jinrishici.com（/token → /one.json 带 X-User-Token）｜open-meteo（天气/海拔/空气/日出日落）+ geocoding-api（中文城市可搜）｜必应壁纸 HPImageArchive.aspx｜网易云榜单 `music.163.com/api/playlist/detail?id=`（需 UA+Referer；热歌 3778678/飙升 19723756/新歌 3779629/原创 2884035/ACG 71385702）+ `api/song/lyric`｜hitokoto｜MyMemory 翻译｜radio-browser｜豆瓣 rexxar（图书系可用，影视 movie_hot 400）
❌ 网易云个性化推荐全 404｜gushi.ci｜apiopen.top 变 SPA｜Open Library 超时｜Google Books 无 key 配额 0｜api.douban.com 400
纪律：内容型需求先 curl 实测再写进推荐，标注「✅实测/❌已死」；别把「内容 API 死了」泛化成「什么都本地做」。

## 3. UI 版式基线
- 首页（activity_main.xml，**画 mockup 前必读**）：暗色玻璃拟态，每日一图 + bg_dim_gradient + bg_liquid_glass 白边玻璃卡 + 白字。顶栏 → 玻璃页签 → NestedScrollView{问候行 → 2 排双列信息卡 92dp → 工具卡 shortcutRow FlowLayout perRow=6} → playerBar 在滚动区外钉底。固定入口 6 个（记账/看片/收藏/播客/下载/问一句）。
- 主题：12 套纯色+渐变，`ThemePrefs.PACKS` + `theme_idx`；`applyTheme(this)` 必须早于 setContentView；渐变在 `MainActivity.applyThemeColors()` 用 GradientDrawable 画、换 header 后补底部 24dp 圆角。⚠️ 用户明确不要暗色/形状/质感维度。
- 快捷位拖动：拖动中不 remove/add view，只 translation + highlightSlot 预览，松口才改序；`HomeShortcutStore.Item` 无 equals → 必须比 `id()`；超过 touchSlop 才算 dragging；shortcutRow 需 clipChildren/Padding=false。
- launcher 图标 = 用户圆形书法徽章，脚本 tools/make_launcher_icon.py 按内切圆裁切。
- 校验坑：`android:text="字面量"` 在编译后的 res/layout/*.xml（AXML），不在 resources.arsc。

## 4. 视频播放器（VideoActivity / exo_player_control_view.xml）
- 片头/片尾跳过：控制条两按钮，`VideoSkipStore` 按影片详情维度存、同片所有集共用；`skipEndArmed` 必须武装（切集 currentPosition 不立刻重置，否则 16ms 空跳）。控制条覆写 app 级 `res/layout/exo_player_control_view.xml`；进度条让位靠覆写 dimen `exo_styled_progress_margin_bottom`（52→108dp），改 XML margin 无效。⚠️ 11-04 已按需求把 `skipCfgRow` 从 `marginBottom=60dp`（进度条上方）改到 **4dp 贴画面最底部**，联动 `exo_bottom_bar` +40dp、`exo_minimal_controls` 44dp、全屏键 XML 与 `positionSideBtns()` 均 112dp —— 四处联动，只改一处会与时间/按钮行重叠。实测 `skipCfgRow [0,404][900,468]`、播放器区 `[0,45][900,476]`（离底 8px）。
- 下载按钮 `videoDownload`：**当前仓库没有**（曾随 f763c0c 引入、被用户 reset 掉）。若日后重做：甩给系统选择器（ACTION_VIEW + URL + extra_filename），由 IDM+/ADM 处理，m3u8/mpd 也支持；`AndroidManifest` 必须 `<queries>` 声明下载器包名 + VIEW intent 才可见（targetSdk 34），再 setPackage 直拉；Referer 403 需手动补。
- 长按 3 倍速 `videoSpeed`：11-04 已改为 **`top|center_horizontal` + `marginTop=52dp` + `@drawable/bg_speed_pill`**（顶部标题下一行，避开缓冲/亮度/音量居中叠字）。⚠️ 旧基线「居中 + #33000000/999dp 胶囊」已作废，别按旧记忆改回去；文案 `3.0×`（不要 ⏩ emoji）。
- 小窗按钮：`inlineMode` 只表示「从详情页内嵌起播」，展开全屏后仍 true → 用 `isCompactInline = inlineMode && !inlineExpanded` 判形态；`applyPlayerLayout()` 末尾统一调 `updatePipButton()`；进小窗前 `clearInlineDock()` 撤 230px 占位。11-04 已去掉 🖼 emoji 与 `#66000000` 直角黑块，改「小窗」文字 + `@drawable/bg_glass_pill` 圆角玻璃胶囊。
- 耳机断开停播：`App.onCreate` 注册 ACTION_AUDIO_BECOMING_NOISY → PlayerManager.stop() + VideoFloatState.player?.pause()；VideoActivity 另注册覆盖前台（投屏中不暂停）。
- ⚠️ AudioFocus 全项目 0 命中：PlayerManager:133 / VideoActivity:929 裸 `ExoPlayer.Builder().build()`，音乐视频会抢声音。

## 5. 已定案 / 已否决
- 加水印**保留**（WatermarkActivity/Engine + 入口 page/watermark + ShortcutRouter + Manifest），去水印**彻底删除**；`ImagePrep` 仍在用不可删。
- 读书板块已回滚（用户要「看内容」不是管元数据 + 嫌中文书少）；教训：这类需求先问「内容消费还是元数据管理」。
- 链接页唤起外部 App：只允许手动配置的链接唤起，https 网页内部跳转不给跳；清单见根目录 `链接页受信唤起改造清单.md`（未执行）。
- 搜索 tab「今日榜单」：网易云只做发现层（元信息+封面），播放/下载仍走 music-api.gdstudio.xyz（网易云播放地址 VIP 403）；首次 switchTab(true) 懒加载。
- 每日一诗：问候行下方，折叠 52dp / 展开整首+译文+分享图+收藏；PoemStore 按天缓存（跨天失效），展开态只在换诗时重置为设置默认值。
- 记账口径（11-04 定案）：**还款一律用「转账」**（银行卡 → 信用卡/花呗），既不计支出也不计收入；禁止「收入记到负债账户」（提交校验拦截 + 汇总排除历史旧数据），同月还/跨月还都不重复计支出。收支查询三处（明细 / 顶部收支卡 / 底部汇总）统一「日/月/年范围 ∩ 筛选条件」取交集，筛选不再豁免日期。

## 6. 瘦身 / 结构现状（2026-11-04 只读盘点，尚未执行）
- APK 49.5MB：liblitertlm_jni.so 22MB + libvosk.so 8.9MB + libtensorflowlite_jni.so 3.8MB = 34.7MB native 壳（模型不进包、按需下载）；dex 25.6MB。clean 重构建实测 49,332,355 B。
- `tensorflow-lite:2.14.0` 是孤儿依赖：唯一使用者 `LaMaInpaint.kt`（配套 BrushView.kt / InpaintEngine.kt 已无引用），可直接删 → 省 3.8MB。
- 入口缺口：LyricActivity 仅队列页可进；VideoHistoryActivity 仅 VideoActivity 内部；NewsActivity 仅首页新闻卡。
- 快捷位 target 字符串散在 4 处（fixedShortcuts / showShortcutPicker / ShortcutRouter / NetAssetActivity.open_view 白名单），无统一枚举。
- MainActivity.kt 2081 行，职责过载。

## 7. 每次新功能 / 修复后的固定收尾流程（强制，用户 2026-11-04 定案）
用户原话「把这个写到记忆里 每次新功能都这样操作」。**改完代码不算完，必须走完 ①~⑦ 才能回报。**

① **连模拟器（其实不用「连」，adb server 已挂着）**
```bash
export MSYS_NO_PATHCONV=1
ADB="/c/Users/linghit/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="127.0.0.1:21503"
"$ADB" devices                 # 有 `127.0.0.1:21503 device` 即可；空了才 "$ADB" connect "$DEV"
```
- `adb` **不在 PATH**，只能用 SDK platform-tools 绝对路径；别找模拟器自带 adb、别试 5555/7555/62001。端口：逍遥 21503（多开 +10：21513/21523）、雷电 5555、MuMu 7555、夜神 62001。实测机型 NX809J / Android 9。
- ⚠️ **每条 Bash 命令都要重新 export `MSYS_NO_PATHCONV=1`**（shell state 不跨命令）；否则 Git Bash 把 `/sdcard/x` 转成 PortableGit 本地路径，`screencap`/`uiautomator dump`/`pull` 全静默失败。`pull` 目标写 Windows 风格 `"C:/Users/.../Temp/x.xml"`。所有 adb 带 `-s "$DEV"` 锁定设备。

② **构建**：常规增量 `assembleDebug`，用户说「重新 build / 要新包」用 `clean assembleDebug`（完整命令见 §1）。构建前先 `git status --short` 核对改动清单，别把未知改动带进包（11-04 发现过既有 Manifest `<queries>` 未提交改动，合法则保留）。

③ **装机 + 内容校验**：`install -r -d`（`-d` 必备，防版本降级失败）→ `dumpsys package ... | grep versionCode|versionName` 核对 → Python `zipfile` 读 APK 内 `assets/*.html` 关键字，确认本次改动**真的打进包**，防止装了旧包白验。

④ **实测验收（能自动验的都自动验，别只说「已改」）**
- 启动冒烟：`am force-stop` + `am start -n com.linghit.gdmusic/.SplashActivity` → `dumpsys window | grep mCurrentFocus` → logcat 扫 `FATAL EXCEPTION`。
- WebView 页（记账等 `assets/*.html`）逻辑：Node `vm` 抽函数造样本数据单测（月份边界、收支口径），比手点可靠。
- 布局：`uiautomator dump --compressed /sdcard/w.xml` + `pull`，Python 正则解析 `bounds` 验控件坐标。⚠️ 这台逍遥模拟器 `screencap` 恒返回纯白 PNG（约 9KB），`exec-out screencap -p > file` 在 Git Bash 下也拿不到图，**别靠截图目检**；dump 在 WebView + 视频 Surface 混合时偶发被系统 SIGKILL，重试一次即可，不是连接问题。
- 交互：`logcat -c` → 按 dump 出的坐标 `input tap` → `logcat -d -t 500 | grep` 看业务日志。报错要自己上模拟器复现，不凭代码猜、不来回问用户。

⑤ **归档 + 交付**：`cp app-debug.apk "从前慢-vX-说明-MMDD-HHMM.apk"`（根目录、带时间戳），`present_files` 给 APK；`.workbuddy/memory/YYYY-MM-DD.md` 追加当日日志。

⑥ **推送到远程 git**（用户 2026-11-04 追加定案）：`git status --short` 先核清单 → **只 `git add` 本次相关文件，绝不 `git add -A`**（工作区常有未跟踪的私人文件，如 `净资产快照.json`）→ `git commit` → `git push origin main`。推送前 `git ls-remote origin HEAD` 确认远端可达（GitHub schannel 握手失败已用仓库级 `http.sslBackend openssl` 解决）。
- **记忆同步规则**：本仓库是 **public**，`.gitignore` 原本把整个 `.workbuddy/` 标为私有；现仅放行 `.workbuddy/memory/MEMORY.md`（长期技术基线 + 本 SOP，让其他进程 clone 后读得到），每日日志 `YYYY-MM-DD.md`、`净资产快照.json` 等私人数据继续忽略。⚠️ 推到公开仓库的内容会永久留痕，含个人数据的文件一律不要放行。

⑦ **汇报三段式**：改了什么 / 验到什么（贴实测数据、坐标、日志原文）/ **没验到什么**（如实标注，例如源不可播导致某显隐态没抓到），不要只报「完成」。
