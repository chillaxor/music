# MEMORY.md — 从前慢（GDMusicPlayer-Android-v3）
> 2026-11-24 重压（原 17.7KB 注入截断）。**细节/坐标/日志全文见 `.workbuddy/memory/YYYY-MM-DD.md`（git 忽略）**。本文件只留铁律+定案+索引。§5 收尾流程强制。

## 1. 构建 / git / 设备
- 构建：`"C:\Users\linghit\.jdks\jbr-17.0.14\bin\java.exe" -cp "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain assembleDebug --console=plain`（要新包加 `clean`，约 2m20s）。必须 `dangerouslyDisableSandbox`；**不要管道 tail**（沙箱失败会假成功），只以 APK 时间戳验真。`cmd //c gradlew.bat` 假执行、`bash gradlew` 被拦、PATH 无 java。
- 装机：Android Studio 模拟器 `emulator-5554`（非逍遥）。adb=`C:/Users/linghit/AppData/Local/Android/Sdk/platform-tools/adb.exe`。⚠️ 逍遥 MEmu 的 adb 会抢 5037 端口 → 遇转圈 `adb kill-server` 再用 SDK adb `start-server`。`dumpsys package com.linghit.gdmusic | grep versionCode|versionName` 对齐 → `adb -s emulator-5554 install -r -d -t --no-streaming`。
- 远程 github.com/chillaxor/music.git (main, public 含 jar)。用户 `git reset --hard origin/main` 是刻意对齐，**别从 reflog 复活**。fetch 可能假成功先 `ls-remote`；push 用 `git -c http.sslBackend=openssl -c http.version=HTTP/1.1 push origin main`，curl 502 会假报 up-to-date，**必须 `git ls-remote origin HEAD` 验真**。
- ⚠️ 同文件多条 Edit 禁止并行（并发写静默丢），改完 grep 复核。`.gitignore` 只放行本文件，每日日志/私人数据继续忽略。
- ⚠️ **11-24 模拟器 DNS 全挂正解＝走宿主代理，不是飞行模式**。现象 ping `unknown host`、logcat `DnsResolver ETIMEDOUT`；真因宿主 UDP53 被限但 `curl` 走 `https_proxy=http://127.0.0.1:53468` 正常。解法 `adb -s emulator-5554 shell settings put global http_proxy 10.0.2.2:53468`（代理地址从 `env|grep https_proxy` 取），OkHttp 默认读系统代理→App 全网恢复。验证 `printf "CONNECT m.douban.com:443 HTTP/1.1\r\nHost: m.douban.com:443\r\n\r\n" | toybox nc 10.0.2.2 53468` → `200 Connection Established`。重启模拟器无效，必须配代理。

## 2. 视频播放器（改前必读，细节 11-05/09/16/24 日志）
- ⚠️ **11-24 原生影视详情页内嵌播放+全屏返回修黑屏**。播放内核抽基类 `PlayerHostActivity`（git mv 自 NativePlayerActivity），薄壳 `NativePlayerActivity : PlayerHostActivity()`；钩子 `hostLayoutRes`/`hostNeedsPlayIntent`/`hostFinishOnClose`/`onInlineSpaceChanged`/`onHostBackPressed`/`onHostReady`/`isPlayerVisible`。播放层抽 `res/layout/view_player_overlay.xml` 两 Activity 都 include（**id 不变**）。`NativeVideoActivity` 详情页上半部 16:9（230dp）内嵌停靠+点选集 `startVideo(...,inline=true)`。
  - 返回键三层铁律：全屏→收回上半部继续播；再按→关详情页停播；独立播放器→关播必须 `finish()`。
  - 两黑屏根因：① 页面覆写 `onBackPressed()` 绕过 `OnBackPressedDispatcher`（**页面禁止覆写**，改用 `onHostBackPressed()`）；② 独立播放器 close 后没 finish（`hostFinishOnClose` 独立 true/详情 false）。
  - Manifest `NativeVideoActivity` configChanges 必含 `smallestScreenSize|screenLayout`。
- `VideoResolver` 优先级：爬虫源 webSniff(jx=0)→resolveViaParses→sniffStatic；直连站 sniffStatic→webSniff。切集先判直链 `isPlayableUrl()`→`safeEval(__resolveDirectAt(i,b64))`→JS sniff→`Android.onEpResolved()`→`finishSwitch()`；`pendingResolveIdx` 丢过期回传。爬虫源走 `__playSpiderAt`。
- 控制条覆写 `exo_player_control_view.xml`；进度条定位**只能覆写 dimen `exo_styled_progress_margin_bottom`**。片头尾跳过+`VideoSkipStore` 按详情存，`skipEndArmed` 须武装。定案去黑底+进度条贴配置行：dimen 进度条42dp/exo_bottom_bar marginBottom90dp/exo_minimal_controls92dp/skipCfgRow4dp；内嵌起播 minimal 会把 exo_progress bottomMargin 置0 → `VideoActivity.onCreate` 用 `addOnPreDrawListener` 纠正；exo_time 提 `<merge>` 顶层。全屏键 videoFull 右下 marginEnd8dp/marginBottom4dp。
- 倍数：skipCfgRow 第4 chip `speedBtn` 点它 `PopupMenu(this,speedBtn,Gravity.END)`（不要 AlertDialog）。档 `[0.5,0.75,1,1.25,1.5,2,3]`；`ensurePlayer()` 和 `playMediaUrl()` 都重设 playbackSpeed；长按临时3×松手回档，ACTION_UP 先置 `longPressActive=false`。
- 下载已实现：`VideoActivity.downloadM3u8()`（约2533行）拉索引→检测加密→并发下 ts→`ffmpegConcatRemux()` 用 `FFmpegKit` concat 无损封装 MP4，log tag `VidDl`；AES-128 不支持。依赖 `ffmpeg-kit-min:6.0`（+15MB，arm64 单 ABI）。
- 小窗 `isCompactInline=inlineMode&&!inlineExpanded`；`applyPlayerLayout()` 末尾 `updatePipButton()`；进小窗前 `clearInlineDock()`。耳机断开停播已做；⚠️ **AudioFocus 全项目 0 命中**。
- 默认2条订阅（`VideoActivity.DEFAULT_SOURCES` v3）：`盒子迷多仓=https://盒子迷.top/禁止贩卖`(91站)、`盒子迷动漫仓=https://盒子迷.top/摸鱼`(301带尾斜杠,108站)。UA 必须 `okhttp/3.15.0`；`SOURCES_VER=3` 仅用户没动过时替换。影视仓 moyu 填 /禁止贩卖、dd 才是摸鱼。
- 11-09 内置 `assets/tvbox/` 42文件/9.95MiB；`SpiderEngine.ensureJar()` 内置优先、`httpGet` 支持 `asset://`、`video.html` 在线失败回退 `builtinSubFor()`、`collectSpiderSites()` 用站点 `s.jar`(47站) 豁免 SPIDER_DENY。APK 现约60MB。播放链路：保留 parses/flags/rules 到 `STATE.srcCfg[i]`、`ensureBuiltInLoaders()` 建类表、`resolveViaParses()`、`playResolvedEx+curHeaders→playMediaUrl` 全量注入（过滤 HOP_BY_HOP，403去Referer）。
- ⚠️ **看片首页推荐＝豆瓣多维度榜单**（`video.html` 单文件）：`#chips` 11榜签，`getEx` 带 UA+iPhone+Referer=m.douban.com 请求 `m.douban.com/rexxar/api/v2/subject_collection/{id}/items?start=0&count=21`，取 `.title/.rating.value/.card_subtitle`，localStorage `gd_douban_chart_v3` 缓存带 pic 海报。点条目=剧名填 #kw+doSearch() 全网搜索，播放下仍走影视仓。`STATE.homeMode='douban'` 总开关，`'cms'` 回影视仓分类。海报取 `pic.normal`，豆瓣图床无 Referer 返回 418 → `VideoActivity` WebViewClient `shouldInterceptRequest` 拦 `doubanio.com/view/photo/` 用 OkHttp 补 Referer=m.douban.com+iPhone UA 包成 WebResourceResponse（tag `VidDoubanImg`）。左右滑切榜 `setupDoubanSwipe` 绑 #grid，>60px 横向为主<900ms。
  - **11-24 离线独立改造（定案）**：缓存 `gd_douban_chart_v3` 改**长期有效**（去掉 day 判定），只有联网成功才覆盖。新增 `assets/tvbox/douban_fallback.json`（44KB/11榜）内置快照；`loadDouban` 三段式＝缓存命中即渲染+后台刷新 / miss 先快照秒显（`loadFallbackChart` 走 asset://）/ `refreshDoubanInBg` 后台拉在线成功才覆盖失败保留。**boot 里豆瓣渲染已从 `selectSource().then()` 摘出独立并行**（订阅源超时不再拖首屏）；selectSource 只服务点条目后的全网搜索。快照 sub 需做 ` / `→` · `+52字截断与在线一致。根因备忘：用户报「看不到豆瓣」其实是模拟器代理端口 53468 失效（宿主 https_proxy 会在 49797 等端口间变）→ 全程 `failed to connect to /10.0.2.2` → 海报全裂+缓存 miss 时空；豆瓣接口本身正常。详见 11-24 日志。
- **未闭环**：flags(33)/ijk/hosts/doh/playerType 只读未消费；.js/.py 源未接 QuickJS/Chaquopy；金牌 hkybqufgh.com TLS失败/骚火 shdy2.com 522；全网99源搜索未在UI跑通；悬浮球 Android10+ 边缘手势 ACTION_CANCEL（推测），根治=主球撑扇形大窗+FLAG_NOT_TOUCH_MODAL。

## 3. UI 版式 / 主题
- 首页 `activity_main.xml` 暗色玻璃拟态+每日一图+白字；顶栏→玻璃页签→NestedScrollView{问候行→2排双列信息卡92dp→shortcutRow FlowLayout perRow=6}→playerBar 钉底。**固定入口10个**（`MainActivity.fixedShortcuts`：记账/看片/原生影视/小说/看MD/收藏/播客/下载/问一句/水印；前4项收编网格前4格不可拖删；路由全表 `ShortcutRouter`，另有 voice/rail/reminder/ai/settings/记账子页。「看片」=WebView版 VideoActivity，「原生影视」=NativeVideoActivity 完全独立）。⚠️ `dimOverlay` 是活代码（每日一图渐变压暗，loadDailyImage 传给 StyleEngine.applyWallpaper）。
- 玻璃卡 `bg_liquid_glass` 三层：亮边#A8→#33→#08FFFFFF / 本体#301C1A18（调透只动这层，#30近裸白字下限）/高光，8处共用。药丸 `bg_pill_glass`/`bg_pill_purple`/`bg_pill_white`。`bg_glass_card.xml` 11-18已删。
- 主题12套纯色+渐变，`ThemePrefs.PACKS`+`theme_idx`；`applyTheme(this)` 早于 setContentView；渐变在 MainActivity.applyThemeColors() 画。用户不要暗色/形状/质感维度。
- 快捷位拖动：拖动中只 translation+highlightSlot 预览，松口才改序；`HomeShortcutStore.Item` 比 id()；超 touchSlop 才算 dragging。launcher=用户圆形书法徽章（tools/make_launcher_icon.py）。

## 4. 其他定案 / 接口
- 加水印保留、去水印彻底删；`ImagePrep` 仍在用不可删。读书板块已回滚。链接页只允许手动配置链接唤起外部App（清单未执行）。搜索tab上榜只做发现层，播放下载走 music-api.gdstudio.xyz；**11-16起数据源=QQ音乐榜**（`QQChartApi`/`QQChartStore`，8榜，纯文字双行无封面，`fcg_v8_toplist_cp.fcg` 取 songlist[].data.songname+singer[].name；旧网易云已删）。榜单卡 `GridLayoutManager(this,2)` 两列一屏10首，chartList 固定300dp内部滚；文字色走 `?attr/gdTextPrimary`/`gdTextSecondary`（浅色主题硬编码白字会看不清）；歌名歌手同色12/14sp；搜索后收起，chartToggle 展开或切榜签展开。
- 每日一诗折叠52dp/展开整首+译文+分享图+收藏，`PoemStore` 按天缓存。记账：还款一律「转账」不计收支、禁止收入记到负债账户、三处收支查询统一「日/月/年范围∩筛选条件」、顶部4卡2×2；已有净资产快照/年度总结/JSON备份/桌面歌词浮层。
- 免key接口（11-04实测）✅ 今日诗词 v2.jinrishici.com｜open-meteo+geocoding｜必应 HPImageArchive｜网易云榜单 music.163.com/api/playlist/detail?id= (UA+Referer；热歌3778678/飙升19723756/新歌3779629/原创2884035/ACG71385702；榜已换QQ，此接口仅歌词用)+api/song/lyric｜hitokoto｜MyMemory｜radio-browser｜豆瓣rexxar图书系。❌ 网易云个性化推荐404｜gushi.ci｜apiopen.top变SPA｜Open Library超时｜Google Books配额0｜豆瓣movie_hot400。纪律：内容型需求先 curl 实测再写进推荐。
- 11-18 瘦身已执行：删 tensorflow-lite+LaMaInpaint/InpaintEngine/BrushView（去水印残骸，零调用）+bg_glass_card.xml，省3.8MB。build.gradle.kts「端侧图像修复」注释是忘改残留。11-18 现状：LyricActivity/VideoHistoryActivity/NewsActivity 都已存在注册；versionCode=20/versionName=2.0.4；已加小说/播客/MD查看器/高铁Rail/提醒(农历节气)/端侧AI问答/Vosk离线语音/DLNA投屏/金价持仓；ffmpeg-kit-min已引入；快捷位target集中ShortcutRouter。

## 5. 每次新功能/修复后固定收尾流程（强制，用户 2026-11-04 原话）
**改完代码不算完，必须走完 ①~⑦ 才回报。**
① 连模拟器：每条 Bash 重新 `export MSYS_NO_PATHCONV=1`；`ADB="/c/Users/linghit/AppData/Local/Android/Sdk/platform-tools/adb.exe"`、`DEV="127.0.0.1:21503"`、`"$ADB" devices`。adb 不在PATH；机型 NX809J Android9；所有adb带 `-s "$DEV"`，pull 目标写 C:/...。
② 构建：增量 assembleDebug，要新包 clean assembleDebug；构建前 git status --short。
③ 装机+内容校验：install -r -d→dumpsys 对版本→Python zipfile 读 APK 内 assets/*.html/dex 关键字。资源/dimen 改动用 aapt2 dump resources/xmltree。
④ 实测验收（能自动验都验）：冒烟 force-stop+am start .SplashActivity→dumpsys window grep mCurrentFocus→logcat 扫 FATAL EXCEPTION。WebView 逻辑 Node vm 抽函数单测；多数据态用 CDP：debug 包开 setWebContentsDebuggingEnabled→/proc/net/unix 找 socket→adb forward tcp:9222 localabstract:→http://127.0.0.1:9222/json/list 取 ws→Python 手写 WS 调 Runtime.evaluate（脚本 C:/Users/linghit/AppData/Local/Temp/gd_cdp_eval.py）。只改内存不调 save()，force-stop 即恢复。布局 uiautomator dump --compressed + pull + 正则解析 bounds（dumpsys display 带 FLAG_SECURE 截图恒纯白，别折腾截图）。交互 logcat -c→input tap/swipe→logcat -d -t 500 grep；报错自己上模拟器复现。视频真播 file:///sdcard 会 EACCES→push /data/local/tmp/+run-as cp 到 files/。
⑤ 归档交付：cp app-debug.apk "从前慢-vX-说明-MMDD-HHMM.apk"→present_files→追加当日日志。
⑥ 推送：git status 核清单→**只 add 本次相关文件，绝不 add -A**→commit→push origin main→ls-remote 验真。含个人数据文件不放行。
⑦ 汇报三段式：改了什么/验到什么（贴实测数据坐标日志原文）/没验到什么（如实标注）。
