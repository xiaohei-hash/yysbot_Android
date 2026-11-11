# YysBot（Android 版）

把 `fast3.2.py` / `free_fast1.0.py` 的阴阳师自动化逻辑移植到手机端原生 APK：
**免 Root**，用 `AccessibilityService` 注入点击/滑动，用 `MediaProjection` 截图，
用 **OpenCV** 做多尺度模板匹配，前台服务常驻 + 悬浮窗日志。

## 功能

- K28 探索主循环（找怪拖拽、打怪、i_end 结算）
- 结界突破：1~9 关、断线检测重连（最多 5 次）、第 9 关 QW 循环
- 拖拽死循环检测 → K28 保底导航（i_off/fanhui/home/dl → i_28）
- 状态恢复：全模板扫描 + 优先级动作表
- 悬浮窗日志：可拖拽、清空、停止、关闭
- 首次启动自动把 `assets/templates` 中的 35 张模板复制到私有目录

## 目录

```
app/src/main/java/com/yys/bot/
├── MainActivity.kt              权限引导 + 参数设置 + 启动
├── YysBotApplication.kt         启动时安装内置模板
├── util/
│   ├── LogBus.kt                进程内日志总线
│   ├── MatExt.kt                Bitmap <-> Mat
│   └── KeyEventCodes.kt         按键常量
└── service/
    ├── AccessibilityHelper.kt   点击/滑动（dispatchGesture）
    ├── ScreenCapture.kt         MediaProjection 截图
    ├── TemplateMatcher.kt       OpenCV 多尺度模板匹配
    ├── StateMachine.kt          K28 / 结界 / 保底 / 恢复
    ├── BotService.kt            前台服务
    ├── LogOverlayService.kt     悬浮窗日志
    ├── ConfigManager.kt         配置 + 模板导入（SAF）
    └── AssetTemplateInstaller.kt
```

## 构建

```bat
gradlew.bat :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

> 本机已验证：Gradle 8.14.3 + AGP 8.5.2 + Kotlin 2.0.0 + JDK 24 构建通过。

## 安装与使用

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

1. 打开 App，按提示到 **设置 → 无障碍 → YysBot** 开启服务
2. 点「选择 templates 文件夹」可追加/替换模板（内置模板已自动安装）
3. 填循环次数、结界间隔、QW 轮数，点「启动脚本」
4. 依次允许：悬浮窗、忽略电池优化、通知、屏幕录制
5. 切回游戏即可，日志显示在悬浮窗中

## 悬浮窗日志操作

| 按钮 | 作用 |
|---|---|
| **收起 / 展开** | 收起后只剩一条标题栏（44dp），再点恢复 400dp 全日志 |
| **清空** | 清掉当前文本，但脚本继续跑 |
| **停止** | 停掉 BotService（脚本），悬浮窗保留 |
| **关闭** | 关掉悬浮窗整窗 |

窗口尺寸固定 `360dp × 400dp`（窄屏自动压到屏幕宽度 88%），日志只保留最近 **300 行**，
所以不会再出现「越用越大」的问题；标题栏可拖动。


| fast3.2.py | APK 实现 | 状态 |
|---|---|---|
| `_refuse_xsfy()` 协战检测（3s 冷却） | `refuseXsfy()`，每次 find 前调用 | ✅ 一致 |
| `log()` 连续 6 次相同操作熔断 | `logAction()`，拖拽→恢复，其它→停止 | ✅ 一致 |
| `_find_original` 1/3 边距内随机点击 | `TemplateMatcher.find()` | ✅ 一致 |
| `find_center` / `click_center` | `findCenter()` / `clickCenter()` | ✅ 一致 |
| `detect_current_state` 多尺度 0.5~1.0 | `TemplateMatcher.detectAll()` | ✅ 一致 |
| `run_k28` 进探索 / 找怪 / BOSS / 纸人×4 / 宝藏 | `runK28()` | ✅ 一致 |
| `_k28_fallback` i_off→fanhui1→fanhui→home→long_sure→dl | `k28Fallback()`（dl 兜底坐标按分辨率折算） | ✅ 一致 |
| `do_jj_break` 1~9 关 / 断线 / QW / 阶段奖励 / 返回 | `doJjBreak()` | ✅ 一致 |
| `recover_state` 26 条状态表 + 通用兜底 | `recoverState()` | ✅ 一致 |
| `_save_debug_screenshot` 累计 10 次 | `saveDebugFrame()` | ⚠️ 只停止脚本，不关机 |
| `kb.tap("1"~"9"/"q"/"w")` 键盘注入 | 改为点击 `key_*.png` 模板 | ⚠️ 平台限制，需自备模板 |
| 模式选择：`tp*3`、`爬塔`、`御魂`、`挑战`、`连点器` | 未实现 | ❌ 目前只有 K28 + 结界突破 |

> 需要哪个模式（爬塔 / 御魂 / 挑战 / 连点器）告诉我，按 `run_pata` / `run_yuhun` / `run_tiaozhan` / `run_autoclicker` 原逻辑补进去。


AccessibilityService **只能注入 BACK/HOME/RECENTS**，不能注入普通按键。
因此结界突破里的「按 1~9 选关、Q/W 技能」需要把游戏内虚拟按键截图保存为：

```
key_1.png ... key_9.png
key_q.png
key_w.png
```

放入 `assets/templates/` 后重新打包，或用 App 内「选择模板文件夹」导入到私有目录。
缺少这些模板时脚本会记录「缺少模板 key_x.png」并继续，不会崩溃。

## ⚠️ 模拟器「应用分身 / 多屏幕」是致命前提

`MediaProjection` **只能镜像默认屏幕（display 0）**。
部分模拟器（MuMu 12、雷电多开等）会把**每个应用放进独立 display**，例如实测：

```
Display #3  : 阴阳师   com.netease.onmyoji.Client
Display #5  : YysBot   com.yys.bot/.MainActivity
Display #0  : 启动器（MediaProjection 只能看到这一块）
```

这种情况下脚本截到的永远是空屏或自己的界面，模板必然全部「未匹配」，
悬浮窗也贴不到游戏上——**不是模板问题，也不是权限问题**。

判断方法：启动脚本后看悬浮窗日志，会出现

```
截图屏幕=0，前台其他应用屏幕=3
⚠️ 游戏在屏幕 3，截图只能看到屏幕 0
⚠️ 模拟器开了「应用分身/多屏」。请关闭分身，让游戏与 YysBot 同屏后再启动。
```

解决办法（任选其一）：

1. 在模拟器设置中心关闭「应用分身 / 多屏 / 多标签窗口 / 独立窗口」，
   让阴阳师与 YysBot 运行在同一屏幕（桌面上点图标直接打开，不要用多开标签页）。
2. 换单屏模拟器（Android 7~9 的老版本模拟器通常不隔离 display）。
3. 直接用真机（推荐，行为最标准）。

验证是否修好：重启脚本后，日志里应出现
`匹配到 i_28 (0.9x) @ (x,y)` 这类信息，且悬浮窗能盖在游戏画面上。

## 启动流程说明

点「启动脚本」后：请求录屏 → 启动前台服务 → 自动拉起「游戏包名」里填的应用 → 回到后台。
所以界面里的**游戏包名**要填对（默认 `com.tencent.tmgp.yys.zqb`），
否则脚本会停在自己的界面上空转。
