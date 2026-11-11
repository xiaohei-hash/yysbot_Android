package com.yys.bot.service

import com.yys.bot.util.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opencv.core.Mat

/**
 * 业务状态机，严格对齐 Python 版 fast3.2.py：
 *  - 协战检测（_refuse_xsfy，3 秒冷却）
 *  - 死循环熔断（同一操作连续 6 次 → 拖拽则状态恢复，其它则停止）
 *  - K28：进入探索 → 找怪/战斗 → 纸人 → 宝藏
 *  - 结界突破：1~9 关、断线重连、第 9 关 QW、阶段奖励、返回
 *  - K28 保底导航（i_off/fanhui1/fanhui → home → long_sure → dl）
 *  - 截屏状态恢复（26 条状态表 + 通用兜底）
 *  
 * 更新：使用 TemplateMatcher.findAll 批量查找，减少截帧次数
 */
class StateMachine(
    private val screenCapture: ScreenCapture,
    private var matcher: TemplateMatcher,
    private val accessibility: AccessibilityHelper
) {

    companion object {
        private const val TIMEOUT_MS = 90_000L
        private const val MAX_REPEAT = 6
        private const val XSFY_COOLDOWN_MS = 3_000L
        private const val END_WAIT_MS = 500L
        private const val MAX_JJ_RETRY = 5
        private const val FAIL_DRAGS = 3
        private const val FALLBACK_TOTAL_MS = 240_000L
        private const val MAX_FALLBACK_FAIL = 5
        private const val DL_RATIO_X = 1457.0 / 1920.0
        private const val DL_RATIO_Y = 461.0 / 1080.0

        // ===== 结界第 9 关 QW 节奏（毫秒）=====
        /** 第 9 关点击 crash 后到第一次按 Q 的间隔。 */
        private const val JJ_CRASH_TO_Q_MS = 3_000L
        /** 非第 9 关点击 crash 后的战斗动画等待。 */
        private const val JJ_CRASH_WAIT_MS = 4_000L
        private const val JJ_CRASH_WAIT_EXTRA_MS = 2_000L
        /** 每轮 Q→W 的间隔。 */
        private const val JJ_Q_TO_W_MS = 1_000L
        /** 每轮 W 后、等待再次挑战按钮前的间隔。 */
        private const val JJ_W_TO_WAIT_MS = 3_000L
        /** 点击"再次挑战"后的间隔。 */
        private const val JJ_AGAIN_WAIT_MS = 2_000L

        /** 点击探索后等待章节页面出现的最长时间（原 15 秒太长）。 */
        private const val TANSUO_ENTRY_WAIT_MS = 5_000L
        /** 检测章节是否出现的轮询间隔。 */
        private const val TANSUO_POLL_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var recoveryRequested = false

    private var qwTimes = 8
    private var fallbackFailCount = 0
    private var debugShotCount = 0
    private var lastXsfyCheck = 0L
    private var screenW = 0
    private var screenH = 0

    private val recentActions = ArrayDeque<Pair<String, Boolean>>()

    val isRunning: Boolean get() = running

    fun updateMatcher(newMatcher: TemplateMatcher) {
        matcher = newMatcher
    }

    fun start(times: Int, luns: Int, qw: Int) {
        if (running) {
            LogBus.log("脚本已在运行中")
            return
        }
        running = true
        qwTimes = qw
        fallbackFailCount = 0
        debugShotCount = 0
        recentActions.clear()
        LogBus.log("脚本启动：循环=$times，结界间隔=$luns，QW轮数=$qw")
        job = scope.launch {
            try {
                runK28(times, luns)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.log("主循环异常: ${e.message}")
            } finally {
                running = false
                LogBus.log("脚本已停止")
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        job?.cancel()
        job = null
    }

    private fun logAction(action: String, ok: Boolean) {
        LogBus.log("$action${if (ok) " 成功" else " 失败"}")

        recentActions.addLast(action to ok)
        while (recentActions.size > MAX_REPEAT) recentActions.removeFirst()

        if (recentActions.size == MAX_REPEAT && recentActions.distinct().size == 1) {
            if (action == "拖拽找怪") {
                LogBus.log("[RECOVERY] 连续${MAX_REPEAT}次拖拽找怪，启动图像状态恢复...")
                recoveryRequested = true
                recentActions.clear()
            } else {
                LogBus.log("[ANTI-LOOP] 连续${MAX_REPEAT}次相同操作，判定死循环，停止脚本")
                stop()
            }
        }
    }

    private suspend fun frame(): Mat? {
        val f = screenCapture.getLatestFrame() ?: return null
        if (screenW == 0) {
            screenW = f.width()
            screenH = f.height()
        }
        return f
    }

    private suspend fun refuseXsfy() {
        val now = System.currentTimeMillis()
        if (now - lastXsfyCheck < XSFY_COOLDOWN_MS) return
        lastXsfyCheck = now

        val xsfy = find("xsfy", checkXsfy = false) ?: return
        LogBus.log("[协战] 检测到协战邀请 @ (${xsfy.x},${xsfy.y})")
        val refuse = find("yijujue", checkXsfy = false) ?: find("decline", checkXsfy = false)
        if (refuse != null) {
            tap(refuse.x, refuse.y)
            logAction("拒绝协战邀请", true)
            delay(1000)
        } else {
            LogBus.log("[协战] 未找到拒绝按钮，点击空白处关闭")
            tap(50, 50)
            delay(500)
        }
    }

    private suspend fun find(name: String, checkXsfy: Boolean = true): TmMatchResult? {
        if (checkXsfy) refuseXsfy()

        val f = frame() ?: run {
            delay(300)
            return null
        }
        return try {
            matcher.find(f, name)
        } finally {
            f.release()
        }
    }

    private suspend fun findCenter(name: String): TmMatchResult? {
        refuseXsfy()
        val f = frame() ?: return null
        return try {
            matcher.findCenter(f, name)
        } finally {
            f.release()
        }
    }

    private suspend fun tap(x: Int, y: Int) {
        accessibility.tap(x, y)
    }

    private suspend fun click(name: String, waitMs: Long = 500): Boolean {
        val r = find(name) ?: return false
        tap(r.x, r.y)
        if (waitMs > 0) delay(waitMs)
        return true
    }

    private suspend fun clickCenter(name: String, waitMs: Long = 500): Boolean {
        val r = findCenter(name) ?: return false
        tap(r.x, r.y)
        LogBus.log("[点击中央] $name @ (${r.x},${r.y})")
        if (waitMs > 0) delay(waitMs)
        return true
    }

    private suspend fun clickScreenCenter() {
        val f = frame() ?: return
        val cx = f.width() / 2
        val cy = f.height() / 2
        f.release()
        tap(cx, cy)
    }

    private fun dlFallbackX(): Int = (screenW * DL_RATIO_X).toInt()
    private fun dlFallbackY(): Int = (screenH * DL_RATIO_Y).toInt()

    private suspend fun runK28(times: Int, luns: Int) {
        var cycleCnt = 0
        var fallbackFailCnt = 0

        for (i in 0 until times) {
            if (!running) return
            LogBus.log("========== 第 ${i + 1}/$times 次循环 ==========")

            if (luns > 0 && (i + 1) % luns == 0) {
                cycleCnt++
                LogBus.log("--- 第${cycleCnt}轮${luns}次已满，插入结界突破 ---")
                doJjBreak()
                if (!running) return
                LogBus.log("--- 结界突破完成，继续K28 ---")
            }

            val enterStart = System.currentTimeMillis()
            while (running && System.currentTimeMillis() - enterStart < TIMEOUT_MS) {
                val tansuo = find("i_tansuo")
                if (tansuo != null) {
                    tap(tansuo.x, tansuo.y)
                    logAction("点击 i_tansuo", true)
                    val checkStart = System.currentTimeMillis()
                    var entered = false
                    while (running && System.currentTimeMillis() - checkStart < TANSUO_ENTRY_WAIT_MS) {
                        if (find("i_zhandou") != null || find("i_boss") != null) {
                            entered = true
                            break
                        }
                        delay(TANSUO_POLL_MS)
                    }
                    if (entered) break
                    logAction("点击探索后 ${TANSUO_ENTRY_WAIT_MS / 1000} 秒内未检测到章节，重新点击", false)
                    continue
                }
                if (click("i_28", 1000)) {
                    logAction("点击 i_28", true)
                    continue
                }
                delay(500)
            }

            var bossFound = false
            var backHome = false
            var searchStart = System.currentTimeMillis()
            var dragFailCnt = 0

            while (running && !bossFound &&
                System.currentTimeMillis() - searchStart < TIMEOUT_MS * 2
            ) {
                if (recoveryRequested) {
                    LogBus.log("检测到拖拽循环，先尝试保底导航...")
                    if (k28Fallback()) {
                        recoveryRequested = false
                        fallbackFailCnt = 0
                        backHome = true
                        break
                    }
                    LogBus.log("保底导航失败，进入截屏状态恢复...")
                    fallbackFailCnt++
                    if (fallbackFailCnt >= MAX_FALLBACK_FAIL) {
                        LogBus.log("连续$fallbackFailCnt 次保底导航未回到 i_28，停止脚本")
                        stop()
                        return
                    }
                    if (recoverState()) {
                        searchStart = System.currentTimeMillis()
                        continue
                    }
                    LogBus.log("状态恢复失败，终止脚本")
                    stop()
                    return
                }

                if (click("i_boss", 500)) {
                    bossFound = true
                    dragFailCnt = 0
                    logAction("点击 i_boss", true)
                    break
                }

                if (click("i_zhandou", 500)) {
                    logAction("点击 i_zhandou", true)
                    dragFailCnt = 0
                    delay(6300)
                    clickScreenCenter()
                    delay(300)
                    clickScreenCenter()
                    delay(300)

                    val fightStart = System.currentTimeMillis()
                    while (running && System.currentTimeMillis() - fightStart < TIMEOUT_MS) {
                        val end = find("i_end")
                        if (end != null) {
                            delay(END_WAIT_MS)
                            tap(end.x, end.y)
                            logAction("点击 i_end(战斗)", true)
                            break
                        }
                        delay(2000)
                        if (find("i_zhandou") != null) {
                            click("i_zhandou", 1000)
                        }
                    }
                    searchStart = System.currentTimeMillis()
                    continue
                }

                val f = frame()
                if (f != null) {
                    val cx = f.width() / 2
                    val cy = f.height() / 2
                    f.release()
                    val dx = -(500 + (0..300).random())
                    accessibility.swipe(cx, cy, cx + dx, cy, 500)
                }
                logAction("拖拽找怪", true)
                delay(500)

                dragFailCnt++
                if (dragFailCnt >= FAIL_DRAGS) {
                    LogBus.log("连续$dragFailCnt 次未找到 i_boss/i_zhandou，启动保底导航...")
                    if (k28Fallback()) {
                        fallbackFailCnt = 0
                        backHome = true
                        break
                    }
                    LogBus.log("保底导航失败，转入截屏状态恢复...")
                    fallbackFailCnt++
                    if (fallbackFailCnt >= MAX_FALLBACK_FAIL) {
                        LogBus.log("连续$fallbackFailCnt 次保底导航未回到 i_28，停止脚本")
                        stop()
                        return
                    }
                    if (recoverState()) {
                        dragFailCnt = 0
                        searchStart = System.currentTimeMillis()
                        continue
                    }
                    LogBus.log("状态恢复失败，终止脚本")
                    stop()
                    return
                }
            }

            if (backHome) continue

            if (bossFound) {
                delay(6500)
                clickScreenCenter()
                delay(300)
                clickScreenCenter()
                delay(300)
                val bossStart = System.currentTimeMillis()
                while (running && System.currentTimeMillis() - bossStart < TIMEOUT_MS) {
                    val end = find("i_end")
                    if (end != null) {
                        delay(END_WAIT_MS)
                        tap(end.x, end.y)
                        logAction("点击 i_end(BOSS)", true)
                        break
                    }
                    delay(500)
                }
            }

            for (k in 1..4) {
                if (!running) break
                val paperStart = System.currentTimeMillis()
                var clicked = false
                while (running && System.currentTimeMillis() - paperStart < 3_000) {
                    val paper = find("i_paper")
                    if (paper != null) {
                        tap(paper.x, paper.y)
                        delay(300)
                        logAction("第$k 次纸人", true)
                        val f = frame()
                        if (f != null) {
                            tap(f.width() / 2, f.height() / 2 + 300)
                            f.release()
                        }
                        delay(500)
                        clicked = true
                        break
                    }
                    delay(200)
                }
                if (!clicked && k >= 3) break
            }

            if (clickCenter("i_treasure", 500)) {
                delay(2000)
                val treasureStart = System.currentTimeMillis()
                while (running && System.currentTimeMillis() - treasureStart < TIMEOUT_MS) {
                    val end = find("i_end")
                    if (end != null) {
                        delay(END_WAIT_MS)
                        tap(end.x, end.y)
                        logAction("点击 i_end(宝藏)", true)
                        break
                    }
                    delay(1000)
                }
            }
        }
    }

    private suspend fun checkDisonline(): Boolean {
        val pos = find("disonline") ?: return false
        logAction("检测到断线提示 disonline", false)
        delay(500)
        if (click("i_sure", 1000)) {
            logAction("点击 i_sure 确认断线，准备重新开始结界突破", true)
        } else {
            logAction("未找到 i_sure，尝试点击断线提示区域", false)
            tap(pos.x, pos.y)
            delay(1000)
        }
        delay(3000)
        return true
    }

    private suspend fun doJjBreak() {
        var jjRetry = 0
        while (running && jjRetry < MAX_JJ_RETRY) {
            jjRetry++
            LogBus.log("结界突破 第$jjRetry/$MAX_JJ_RETRY 次尝试")
            delay(2000)

            if (find("i_jj") == null) {
                logAction("未找到 i_jj，home/dl 往复寻找直到出现 i_28", false)
                var roundCnt = 0
                var dlX = -1
                var dlY = -1

                while (running) {
                    roundCnt++
                    if (roundCnt > 10) {
                        logAction("home/dl 往复超过10轮仍未出现 i_28，放弃本次结界突破", false)
                        return
                    }
                    if (click("home", 2000)) {
                        logAction("点击 home 图标(第$roundCnt 轮)", true)
                    }
                    val dl = find("dl")
                    if (dl != null) {
                        dlX = dl.x
                        dlY = dl.y
                        tap(dlX, dlY)
                        delay(2000)
                        logAction("点击 dl(第$roundCnt 轮)", true)
                    } else {
                        // 图片没搜到 → 优先用模板配置里保存的固定坐标/范围
                        val fixedDl = matcher.fixedFallback("dl")
                        if (fixedDl != null) {
                            dlX = fixedDl.x
                            dlY = fixedDl.y
                            tap(dlX, dlY)
                            delay(2000)
                            logAction("未搜到 dl 图片，点击模板固定坐标($dlX,$dlY)(第$roundCnt 轮)", true)
                        } else {
                            if (dlX < 0) {
                                dlX = dlFallbackX()
                                dlY = dlFallbackY()
                            }
                            tap(dlX, dlY)
                            delay(2000)
                            logAction("未搜到 dl 图片且未设固定坐标，点击比例位置($dlX,$dlY)(第$roundCnt 轮)", false)
                        }
                    }

                    if (find("i_28") != null) {
                        logAction("已出现 i_28，回到原本流程", true)
                        break
                    }
                    if (find("i_jj") != null) {
                        logAction("已出现 i_jj，回到原本流程", true)
                        break
                    }
                    if (roundCnt % 4 == 0) {
                        logAction("home/dl 往复4轮未出现 i_28，触发图像状态恢复...", false)
                        if (!recoverState()) {
                            logAction("状态恢复失败，放弃本次结界突破", false)
                            return
                        }
                    }
                    logAction("home/dl 往复一轮未出现 i_28（第$roundCnt 轮）", false)
                    delay(5000)
                }
            }

            if (checkDisonline()) continue

            click("i_jj", 1000)
            logAction("进入结界突破", true)
            delay(2000)

            var restart = false
            for (level in 1..9) {
                if (!running) return

                if (checkDisonline()) {
                    restart = true
                    break
                }

                if (!click("key_$level", 0)) {
                    logAction("缺少模板 key_$level.png，无法点击关卡按键", false)
                }
                logAction("JJ 第$level 关", true)
                delay(2000)

                if (checkDisonline()) {
                    restart = true
                    break
                }

                val crash = find("crash")
                if (crash != null) {
                    tap(crash.x, crash.y)
                    logAction("点击 crash", true)
                    if (level == 9) {
                        // 第 9 关：crash 后 3 秒即按 Q
                        delay(JJ_CRASH_TO_Q_MS)
                    } else {
                        delay(JJ_CRASH_WAIT_MS)
                        delay(JJ_CRASH_WAIT_EXTRA_MS)
                    }
                } else {
                    logAction("搜索 crash 失败", false)
                    if (checkDisonline()) {
                        restart = true
                        break
                    }
                    continue
                }

                if (level == 9) {
                    for (round in 0 until qwTimes) {
                        if (!running) break
                        // 第 1 轮已在 crash 后等过，后续轮次间隔 1 秒
                        if (round > 0) delay(1_000)
                        if (!click("key_q", 0)) logAction("缺少模板 key_q.png", false)
                        logAction("JJ 点击 q", true)
                        delay(JJ_Q_TO_W_MS)
                        if (!click("key_w", 0)) logAction("缺少模板 key_w.png", false)
                        logAction("JJ 点击 w", true)
                        delay(JJ_W_TO_WAIT_MS)

                        val waitStart = System.currentTimeMillis()
                        while (running && find("i_again") == null &&
                            System.currentTimeMillis() - waitStart < 30_000
                        ) {
                            if (checkDisonline()) break
                            delay(1000)
                        }
                        if (!running) break
                        if (checkDisonline()) break
                        click("i_again", 1000)
                        logAction("again", true)
                        delay(JJ_AGAIN_WAIT_MS)
                    }
                }

                if (checkDisonline()) {
                    restart = true
                    break
                }

                delay(4000)

                val endStart = System.currentTimeMillis()
                while (running) {
                    if (checkDisonline()) {
                        restart = true
                        break
                    }
                    if (System.currentTimeMillis() - endStart > 90_000) {
                        logAction("等待 i_end 超时", false)
                        var recovered = false
                        while (running) {
                            if (checkDisonline()) {
                                restart = true
                                recovered = true
                                break
                            }
                            val e = find("i_end")
                            if (e != null) {
                                tap(e.x, e.y)
                                delay(700)
                                logAction("点击end", true)
                            }
                            delay(END_WAIT_MS)
                            val f1 = find("fanhui1")
                            if (f1 != null) {
                                tap(f1.x, f1.y)
                                delay(700)
                                logAction("点击fanhui1", true)
                            }
                            delay(END_WAIT_MS)
                            val fh = find("fanhui")
                            if (fh != null) {
                                tap(fh.x, fh.y)
                                delay(1500)
                                logAction("点击fanhui", true)
                                recovered = true
                                break
                            }
                            if (find("i_28") != null) {
                                recovered = true
                                break
                            }
                        }
                        if (recovered) return
                        break
                    }

                    val end = find("i_end")
                    if (end != null) {
                        delay(END_WAIT_MS)
                        tap(end.x, end.y)
                        delay(3000)
                        delay(1000)
                        logAction("JJ 点击 i_end", true)
                        break
                    }
                }

                if (restart) break

                if (level % 3 == 0) {
                    val stageStart = System.currentTimeMillis()
                    while (running && System.currentTimeMillis() - stageStart < 10_000) {
                        if (checkDisonline()) {
                            restart = true
                            break
                        }
                        val end = find("i_end")
                        if (end != null) {
                            delay(END_WAIT_MS)
                            tap(end.x, end.y)
                            delay(1000)
                            logAction("JJ 阶段奖励", true)
                            delay(2000)
                            break
                        }
                        delay(300)
                    }
                }
            }

            if (restart) continue

            delay(2000)
            val backStart = System.currentTimeMillis()
            while (running && System.currentTimeMillis() - backStart < 30_000) {
                if (checkDisonline()) {
                    restart = true
                    break
                }
                val back = find("fanhui")
                if (back != null) {
                    tap(back.x, back.y)
                    delay(1000)
                    logAction("JJ 返回 K28", true)
                    return
                }
                delay(1000)
            }
            if (restart) continue
            logAction("JJ 返回失败", false)
            return
        }

        LogBus.log("结界突破断线重试已达上限($MAX_JJ_RETRY 次)，强制退出结界...")
        for (i in 0 until 3) {
            if (click("fanhui1", 1000) || click("fanhui", 1000)) {
                logAction("已通过 fanhui 退出结界，恢复K28流程", true)
                return
            }
            delay(1000)
        }
        logAction("无法退出结界，放弃本次结界突破", false)
    }

    private suspend fun k28Fallback(): Boolean {
        LogBus.log("开始 K28 保底导航")
        val deadline = System.currentTimeMillis() + FALLBACK_TOTAL_MS
        var dlX = -1
        var dlY = -1

        while (running && System.currentTimeMillis() < deadline) {
            val home28 = find("i_28")
            if (home28 != null) {
                logAction("保底导航已回到 i_28", true)
                return true
            }

            var clicked = false
            for (name in listOf("i_off", "fanhui1", "fanhui")) {
                if (!running) return false
                val pos = find(name)
                if (pos != null) {
                    tap(pos.x, pos.y)
                    delay(2000)
                    logAction("保底导航点击 $name", true)
                    delay(4000)
                    clicked = true
                    break
                }
            }
            if (clicked) continue

            if (click("home", 2000)) {
                logAction("保底导航点击 home", true)
            }
            delay(4000)

            val longSure = find("long_sure")
            if (longSure != null) {
                tap(longSure.x, longSure.y)
                delay(2000)
                logAction("保底导航点击 long_sure", true)
                delay(4000)
            }

            val dl = find("dl")
            if (dl != null) {
                dlX = dl.x
                dlY = dl.y
                tap(dlX, dlY)
                delay(2000)
                logAction("保底导航点击 dl", true)
            } else {
                // 图片没搜到 → 优先用模板配置里保存的固定坐标/范围
                val fixedDl = matcher.fixedFallback("dl")
                if (fixedDl != null) {
                    dlX = fixedDl.x
                    dlY = fixedDl.y
                    tap(dlX, dlY)
                    delay(2000)
                    logAction("未搜到 dl 图片，点击模板固定坐标($dlX,$dlY)", true)
                } else {
                    if (dlX < 0) {
                        dlX = dlFallbackX()
                        dlY = dlFallbackY()
                    }
                    tap(dlX, dlY)
                    delay(2000)
                    logAction("未搜到 dl 图片且未设固定坐标，点击比例位置($dlX,$dlY)", false)
                }
            }
            delay(4000)
        }
        return find("i_28") != null
    }

    private fun saveDebugFrame(tag: String) {
        debugShotCount++
        LogBus.log("[DEBUG] 调试截图计数: $debugShotCount/10 ($tag)")
        if (debugShotCount >= 10) {
            LogBus.log("[SHUTDOWN] debug_screenshots 已达 $debugShotCount 次，脚本停止")
            stop()
        }
        screenCapture.dumpDebug("recover_$tag")
    }

    private suspend fun recoverState(): Boolean {
        logAction("===== 状态恢复开始 =====", true)
        saveDebugFrame("before_recover")

        val f = frame() ?: return false
        val matches = try {
            matcher.detectAll(f)
        } finally {
            f.release()
        }

        if (matches.isEmpty()) {
            logAction("状态恢复失败 - 未匹配到任何已知图像", false)
            saveDebugFrame("no_match")
            return false
        }

        val matchMap = linkedMapOf<String, TmMatchResult>()
        for (m in matches) {
            val old = matchMap[m.name]
            if (old == null || m.confidence > old.confidence) matchMap[m.name] = m
        }
        LogBus.log("共匹配到 ${matchMap.size} 种图像（总计${matches.size}个匹配）")
        matchMap.entries.take(10).forEach { (name, m) ->
            LogBus.log("  → $name @ (${m.x},${m.y}) 置信度 ${"%.0f".format(m.confidence * 100)}%")
        }

        fun has(name: String) = matchMap.containsKey(name)

        suspend fun clickMatch(name: String, waitMs: Long): Boolean {
            val m = matchMap[name] ?: return false
            tap(m.x, m.y)
            if (waitMs > 0) delay(waitMs)
            return true
        }

        val order = listOf(
            "disonline_sure", "disonline", "i_sure", "i_off", "offtp",
            "gao", "zudui", "xsfy", "30man1", "30man2",
            "i_end", "i_paper", "i_treasure", "i_again",
            "i_boss", "i_zhandou", "fanhui", "fanhui1", "fanhui2", "i_back",
            "i_tansuo", "i_tou", "i_28", "i_jj", "yuhun", "easy-znq"
        )

        for (name in order) {
            if (!has(name)) continue
            val ok = when (name) {
                "gao" -> {
                    clickScreenCenter()
                    delay(1000)
                    true
                }
                "zudui" -> {
                    clickMatch("i_off", 500) || clickMatch("offtp", 500) ||
                        run {
                            val w = screenW.takeIf { it > 0 } ?: 1920
                            val h = screenH.takeIf { it > 0 } ?: 1080
                            tap(w / 2, h - 100)
                            delay(500)
                            true
                        }
                }
                "xsfy" -> {
                    clickMatch("yijujue", 500) || clickMatch("decline", 500) ||
                        run {
                            tap(50, 50)
                            delay(500)
                            true
                        }
                }
                "30man1", "30man2" -> {
                    clickMatch("i_off", 500) || run {
                        val w = screenW.takeIf { it > 0 } ?: 1920
                        tap(w - 50, 50)
                        delay(500)
                        true
                    }
                }
                else -> clickMatch(name, 1200)
            }

            if (ok) {
                recoveryRequested = false
                recentActions.clear()
                logAction("===== 状态恢复完成 - 已从 [$name] 继续流程 =====", true)
                return true
            } else {
                logAction("恢复操作 [$name] 返回失败，尝试下一个匹配...", false)
            }
        }

        logAction("匹配到的图像无对应处理逻辑，尝试通用恢复...", false)
        for (m in matches) {
            tap(m.x, m.y)
            delay(1000)
        }
        recoveryRequested = false
        recentActions.clear()
        logAction("通用恢复完成（已点击所有匹配元素）", true)
        saveDebugFrame("after_generic")
        return true
    }
}