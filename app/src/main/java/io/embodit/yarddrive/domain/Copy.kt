package io.embodit.yarddrive.domain

/** One string in both languages. */
data class Tr(val zh: String, val en: String) {
    fun of(zhLang: Boolean): String = if (zhLang) zh else en
}

private fun t(zh: String, en: String) = Tr(zh, en)

/**
 * Every sentence the user reads (design copy + content.tone, copied word for word; sentences the design does not cover
 * follow content.tone: short, spoken, one thing per sentence, numbers and body parts instead of "incorrect").
 */
object Copy {
    val appTitle = t("倒车入库", "Yard Drive")
    val tagline = t("从你的眼睛看点位", "Reference points, seen from your own eyes")
    val sit = t("坐好了", "I'm seated")
    val sitHint = t("找把椅子坐好，看正前方。", "Grab a chair, sit down, look straight ahead.")
    val eyeHint = t("要用眼动，看你看了哪面镜子。不算分。", "Eye tracking just records which mirror you looked at. It never affects your score.")
    val disclaimer = t("练习用，不是驾考系统，不计学时。评判按 GA 1026-2017 整理，以当地考场为准。", "Practice only. Not an exam system, no training hours logged. Scoring follows GA 1026-2017; your test centre's rules apply.")
    val outcomes = mapOf(
        Outcome.PASS to t("过了", "Nailed it"),
        Outcome.OUT to t("没过：车身出线", "Failed: body over a line"),
        Outcome.NOTIN to t("没过：没进库", "Failed: not in the bay"),
        Outcome.TIMEOUT to t("没过：超时", "Failed: out of time"),
        Outcome.ORDER to t("没过：顺序不对", "Failed: wrong order"),
        Outcome.STOP to t("没过：中途停车", "Failed: stopped mid-way"),
    )
    val stop2s = t("中途停车", "Stopped mid-way")
    val early = t("早 %d cm", "%d cm early")
    val late = t("晚 %d cm", "%d cm late")
    val lineTouched = t("压线了：%s压%s", "Line touched: %s over %s")
    val backToPoint = t("回到上一点位", "Back to last point")
    val segDone = t("%s 搞定 · 下一段：%s，切 %s", "%s done · next: %s, shift to %s")
    val gearLocked = t("停下来再换挡", "Stop before you shift")
    val handMode = t("右手捏住方向盘转。点「走」车就动，再点停。", "Pinch the wheel with your right hand. Tap Go to move, tap again to stop.")
    val stepMode = t("步进：按一下走 0.3 m", "Step mode: one press moves 0.3 m")
    val exitConfirm = t("退出倒车入库？", "Quit Yard Drive?")
    val eyeOff = t("眼动没开，「看了哪里」按头的方向记。不影响分数。", "Eye tracking is off, so 'where you looked' uses head direction. Scores are unaffected.")
    val seatNote = t("座椅 %s · 左镜壳下沿对着眼前 %s m · 引擎盖盖住 %s m 以内", "Seat %s · left mirror edge meets the ground %s m ahead · the bonnet hides the next %s m")
    val chantDelta = t("照口诀会%s %d cm", "The chant would be %d cm %s here")
    val advice = t("右打死老是早：控制线到镜壳下面再打，别提前。", "Your right full lock keeps coming early: wait till the line is under the mirror.")
    val mockFail = t("车身出线，考试不合格", "Body over the line. Test failed")
    val freeStart = t("这次起点偏了，先看左窗控制线在哪。", "Start's a bit off this time. Find the control line in the left window first.")

    /** Coach cues per look-point: big line, small line (design copy.cues; Ⅲ / Ⅳ mirrored in the same voice). */
    val cues: Map<String, Pair<Tr, Tr>> = mapOf(
        "LP2" to (t("右打死", "Full lock right") to t("控制线到镜壳下面了", "Control line's under the mirror")),
        "LP3" to (t("看右镜", "Right mirror") to t("库角和车身留一拳", "Keep a fist between corner and body")),
        "LP3near" to (t("回半圈", "Ease off half a turn") to t("库角有点近，车尾会往中间挪", "Corner's close. Tail drifts to the middle")),
        "LP3again" to (t("再打死", "Full lock again") to t("车尾挪好了，打到底", "Tail's over. All the way now")),
        "LP3late" to (t("这把进不去", "Not this time") to t("打晚了，左后角会压库角", "Too late. Rear left will clip the corner")),
        "LP4" to (t("回正", "Straighten") to t("车身和库边线平了", "Body's parallel to the bay line")),
        "LP5" to (t("停", "Stop") to t("进了。前沿 35 cm，刚好", "You're in. Nose 35 cm past the line")),
        "LP6" to (t("左打死", "Full lock left") to t("再晚半米口诀才到，别等它", "The chant comes half a metre late. Don't wait")),
        "LP7" to (t("回正", "Straighten") to t("车正了", "Car's straight")),
        "LP8" to (t("停", "Stop") to t("镜子和控制线平了，前轮过线了", "Mirror's level with the line. Front wheels are past")),
        "LP9" to (t("左打死", "Full lock left") to t("控制线到右镜壳下面了", "Control line's under the right mirror")),
        "LP10" to (t("看左镜", "Left mirror") to t("库角和车身留一拳", "Keep a fist between corner and body")),
        "LP10near" to (t("回半圈", "Ease off half a turn") to t("库角有点近，车尾会往中间挪", "Corner's close. Tail drifts to the middle")),
        "LP10again" to (t("再打死", "Full lock again") to t("车尾挪好了，打到底", "Tail's over. All the way now")),
        "LP10late" to (t("这把进不去", "Not this time") to t("打晚了，右后角会压库角", "Too late. Rear right will clip the corner")),
        "LP11" to (t("回正", "Straighten") to t("车身和库边线平了", "Body's parallel to the bay line")),
        "LP12" to (t("右打死", "Full lock right") to t("再晚半米口诀才到，别等它", "The chant comes half a metre late. Don't wait")),
    )

    /** What to do before the point, per segment phase (P2 column 2 line 1). */
    val taskBeforeLock = mapOf(
        SegId.I to t("直退，看左镜", "Straight back, watch the left mirror"),
        SegId.II to t("直行，到点左打死", "Straight on, full lock left at the point"),
        SegId.III to t("直退，看右镜", "Straight back, watch the right mirror"),
        SegId.IV to t("直行，到点右打死", "Straight on, full lock right at the point"),
    )
    val taskLocked = mapOf(
        SegId.I to t("保持打死，看右镜", "Hold full lock, watch the right mirror"),
        SegId.II to t("保持打死，车正了就回正", "Hold full lock, straighten when the car's straight"),
        SegId.III to t("保持打死，看左镜", "Hold full lock, watch the left mirror"),
        SegId.IV to t("保持打死，车正了就回正", "Hold full lock, straighten when the car's straight"),
    )
    val taskStraight = mapOf(
        SegId.I to t("直退进库，看前沿", "Straight back in, mind the nose"),
        SegId.II to t("直行，看左窗控制线", "Straight on, watch the control line on the left"),
        SegId.III to t("直退进库，看前沿", "Straight back in, mind the nose"),
        SegId.IV to t("直行，看右窗控制线", "Straight on, watch the control line on the right"),
    )
    val ready = t("切 %s，按住 A 就走", "Shift to %s, hold A to go")
    val readyHand = t("切 %s，点「走」", "Shift to %s, tap Go")
    val shiftFirst = t("先切 %s", "Shift to %s first")
    val wrongGearSub = t("挡位不对。按 A 车也会按现在的挡走", "Wrong gear. A still moves the car in this gear")
    val stopped = t("停着。再按 A 就走", "Stopped. Hold A to go on")
    val stoppedHand = t("停着。点「走」继续", "Stopped. Tap Go to carry on")
    val autoStopped = t("到点了，车自己停了", "You're at the point. Car stopped itself")
    val autoStoppedSub = t("看好了再按 A 继续", "Have a good look, then hold A to go on")
    val settling = t("停稳了 ✓", "Settled ✓")
    val settlingSub = t("别动，1 秒就算这段", "Hold still. One second and it counts")
    val toGo = t("还有 %.1f m", "%.1f m to go")
    val lessonRingNote = t("课程里停车只记不扣", "Lesson rules: stops are noted, not scored")
    val finished = t("做完了", "That's the set")
    val finishedSub = t("用时 %s", "%s")
    val lost = t("追踪丢了，车停了", "Lost tracking. Car's stopped")
    val lostSub = t("不算中途停车。恢复后原地继续", "Doesn't count as a mid-stop. Carries on when it's back")
    val byHead = t("看了哪里：按头部记录", "Looks: by head direction")
    val stepChip = t("分解", "Step by step")
    val easyChip = t("宽松", "Easy")
    val freeChip = t("第 %d / %d 次 · 难度 %d · 种子 %d", "Run %d / %d · level %d · seed %d")
    val notIn = t("车头还在库外 %d cm，再往里倒一点", "Nose still %d cm outside. Back in a bit more")

    // ---- P2 keys
    val fullL = t("左打死", "Full L")
    val centre = t("回正", "Centre")
    val fullR = t("右打死", "Full R")
    val rd = t("R / D", "R / D")
    val step = t("步进", "Step")
    val stepOnce = t("走一步", "One step")
    val go = t("走", "Go")
    val stop = t("停", "Stop")
    val pause = t("暂停", "Pause")
    val mute = t("静音", "Mute")
    val unmute = t("开声音", "Sound on")
    val topOnly = t("只看俯视镜", "Top view only")
    val skip = t("跳过", "Skip")
    val restart = t("重来这次", "Restart run")
    val carryOn = t("继续练（不计成绩）", "Carry on (unscored)")
    val endReplay = t("结束回放", "End replay")
    val prev = t("上一点位", "Previous")
    val next = t("下一点位", "Next")
    val seatChange = t("换座椅高度", "Change seat")
    val home = t("主页", "Home")
    val backHome = t("回主页", "Home")
    val back = t("返回", "Back")
    val gearR = t("倒车", "Reverse")
    val gearD = t("前进", "Drive")
    val parked = t("停着", "parked")
    val stepSpeed = t("步进 0.3 m", "Step 0.3 m")
    val topView = t("俯视镜 · 车头朝上", "Top view · nose up")
    val cornerGap = t("库角", "Corner gap")
    val topOff = t("俯视镜已关", "Top view off")
    val topExam = t("俯视镜（考试关）", "Top view (off in test)")
    val demo = t("示范", "Demo")
    val replay = t("回放", "Replay")
    val laneTitle = t("通道 · 宽 2.3", "Lane · 2.3 wide")
    val laneTask = t("先点「右打死」看圈数到「打死」，再「回正」；然后切 R 倒 6 m 别压线", "Tap Full R and watch the hub hit full lock, then Centre; then R and 6 m back without touching a line")
    val laneTask2 = t("切 D，开回起点", "Shift to D and drive back to the start")
    val tourSteps = listOf(t("① 看镜子", "① Look at the mirrors"), t("② 认线", "② Meet the lines"), t("③ 五个点位", "③ Five points"))
    val tourLook = t("目光落到就亮；后窗看不到也能跳过", "Lights up when you look; skip the rear window if you can't turn that far")
    val tourBlocks = listOf(t("左镜", "Left"), t("右镜", "Right"), t("内镜", "Inside"), t("后窗", "Rear window"))
    val skipRear = t("跳过后窗", "Skip rear")
    val nextStep = t("下一步", "Next")
    val tourLines = listOf(
        t("黄线是库：左边线、右边线、库底线。", "Yellow is the bay: left line, right line, back line."),
        t("白虚线是库前线，两边的白线是它的延长线。", "The dashed white line is the bay front; the white lines either side extend it."),
        t("控制线：车头刚过它就是起点。", "Control line: just past it with the nose is your start."),
        t("最远那条白线是道路边线，别压。", "The far white line is the road edge. Stay off it."),
        t("黄黑的杆是库角，练车场才有，考场只有线。", "The yellow-black poles mark the corners. Practice yards have them; the test has only lines."),
    )

    // ---- P1
    val lessons = t("课程", "Lessons")
    val lessonsSub = t("8 课由简到难，哪一课都能点", "8 lessons, easy to hard, pick any")
    val library = t("点位库", "Look-points")
    val librarySub = t("12 个点位，传送过去从你的眼睛看", "12 points, jump there and look")
    val libraryTitleSub = t("选一张，车会停到那个点位；你坐着看", "Pick one. The car jumps there; you just look")
    val mock = t("模拟考试", "Mock test")
    val mockSub = t("三分半，计时评分，随时能考", "3½ min, timed and scored, any time")
    val free = t("自由练习", "Free practice")
    val freeSub = t("起点每次都偏一点，一直能练", "Start's a bit off every time. Endless")
    val nextLesson = t("下一课：%s（%d / 8）", "Next: %s (%d / 8)")
    val firstLesson = t("从第 1 课开始：先坐好，别急着动", "Start with lesson 1: Sit down first")
    val allLessons = t("8 课都过了，挑一课再练", "All 8 done. Pick one to practise")
    val scorecard = t("成绩单", "Scorecard")
    val settings = t("设置", "Settings")
    val turnOn = t("去开启", "Turn on")
    val groups = listOf(t("上手", "GET SET"), t("分解", "STEP BY STEP"), t("整套", "FULL RUN"))
    val passedChip = t("过关", "Done")
    val nextChip = t("下一课", "Next")
    val demoAgain = t("再看示范", "Demo again")
    val libGroups = listOf(t("右倒库", "REVERSE IN, RIGHT"), t("左出库", "PULL OUT, LEFT"), t("左倒库 · 右出库", "LEFT BAY · PULL OUT RIGHT"))
    val seatHeight = t("座椅高度", "Seat height")
    val seatHeightSub = t("眼离地 1.12 / 1.20 / 1.28 m，之后设置里也能改", "Eye height 1.12 / 1.20 / 1.28 m; also in Settings")
    val seats = listOf(t("低", "Low"), t("中", "Mid"), t("高", "High"))
    val loading = t("场地摆好就来", "Setting up the yard")
    val unavailable = t("这个应用要在全空间模式里用。车库在你身后和镜子里，透视模式下看不到。", "Yard Drive needs Full Space. The bay is behind you and in the mirrors; it can't show in passthrough.")
    val quit = t("退出", "Quit")
    val quitApp = t("退出应用", "Quit app")
    val cancel = t("取消", "Cancel")
    val paused = t("暂停了", "Paused")
    val pausedSub = t("计时停了，这段不算中途停车", "Timer frozen. This doesn't count as a mid-stop")
    val resume = t("继续", "Continue")
    val goAgain = t("再来一次", "Go again")
    val nextLessonBtn = t("过关 → 下一课", "Passed → next lesson")
    val redoLesson = t("重来本课", "Redo lesson")
    val lessonList = t("课程列表", "Lessons")
    val anotherGroup = t("再来一组", "Another set of 5")
    val watchReplay = t("看回放", "Replay")
    val resultTabs = listOf(t("结果", "Result"), t("点位", "Points"), t("轨迹", "Path"))
    val trialOf = t("第 %d / %d 次", "Try %d / %d")
    val stopsLine = t("中途停车 ×%d −%d · 用时 %s", "Stopped mid-way ×%d −%d · %s")
    val timeLine = t("用时 %s", "Time %s")
    val unscoredLine = t("这次不计成绩", "This one's unscored")
    val pointCol = t("点位", "Point")
    val earlyLateCol = t("早 / 晚", "Early / late")
    val gapsLine = t("库角最小间隙 右 %s · 左 %s　入库前沿 %s", "Min corner gap R %s · L %s　Nose %s past the line")
    val bandsLine = t("绿 ≤ 15 cm · 橙 ≤ 30 · 红 > 30", "green ≤ 15 cm · amber ≤ 30 · red > 30")
    val pathLegend = t("白线 = 你的轨迹 · 青色虚线 = 理想 · 红点 = 压线处", "white = yours · cyan = ideal · red = line touch")
    val noHits = t("这次没有点位记录", "No look-points recorded this time")
    val sourceLine = t("评判按 GA 1026-2017 倒车入库条目整理（中途停车 > 2 s 扣 5 分 / 次；出线按线中心算是本应用口径）；以当地考场为准。", "Scored to GA 1026-2017 bay-park rules (stop > 2 s = −5 each; line-centre rule is this app's call). Your test centre's rules apply.")
    val groupLine = t("这组 5 次：过了 %d 次，平均 %d 分", "This set of 5: %d passed, average %d")
    val tourResult = t("看过的点位 %d / 5 · 镜子 %d / 4", "Points seen %d / 5 · mirrors %d / 4")
    val laneResult = t("压线 %d 次 · 打死 %s · 回正 %s", "Lines touched %d · full lock %s · centred %s")
    val yes = t("做过了", "done")
    val no = t("还没有", "not yet")
    val scoreTitleSub = seatNote
    val modeCol = t("模式", "Mode")
    val runsCol = t("次数", "Runs")
    val passCol = t("最近 10 次合格率", "Pass rate (last 10)")
    val avgCol = t("平均用时", "Avg time")
    val stopsCol = t("中途停车", "Mid-stops")
    val mostCommon = t("最常犯：%s，中位数%s %d cm。", "Most common: %s, median %d cm %s.")
    val nothingYet = t("还没有成绩。先去练一把。", "No runs yet. Go have a go.")
    val medianGaps = t("库角最小间隙中位数：右 %s · 左 %s", "Median min corner gap: R %s · L %s")
    val clearScores = t("清除成绩", "Clear scores")
    val clearAsk = t("清掉全部成绩？", "Clear every score?")
    val clear = t("清除", "Clear")
    val asksAgain = t("（会再问一次）", "(asks once more)")
    val freePick = t("挑个难度", "Pick a level")
    val freePickSub = t("5 次一组，默认考试规则", "Runs of 5, test rules by default")
    val diffs = listOf(
        t("难度 1 · 起点偏一点点", "Level 1 · start off by a hair"),
        t("难度 2 · 偏 20 cm 以内，歪 2.5°", "Level 2 · up to 20 cm off, 2.5° crooked"),
        t("难度 3 · 偏 30 cm，歪 4°，座椅也会变", "Level 3 · 30 cm off, 4° crooked, seat may change"),
    )
    val seatHintFree = t("这次教练把座椅调%s了", "The coach set the seat %s this time")
    val tourTitle = t("先坐好，别急着动", "Sit down first, don't touch anything")
    val tourSub = t("课程 1 · 口诀是别人的，眼睛是你的", "Lesson 1 · The chant is theirs. The eyes are yours")
    val tourBody = t("这一课车不动。先调座椅高度，看引擎盖盖住的地面怎么前后移；再依次看四面：左镜、右镜、内镜、回头看后窗。", "The car stays put this lesson. Set the seat and watch the ground the bonnet hides move. Then look at all four: left mirror, right mirror, inside mirror, over your shoulder.")
    val start = t("开始", "Start")
    val skipLesson = t("跳过这课", "Skip")
    val about = t("关于", "About")
    val aboutBody = t(
        "倒车入库 v%s。练习用，不是驾考系统，不计学时。场地尺寸与评判按 GA 1026-2017《机动车驾驶人考试内容和方法》倒车入库条目整理：库长 = 车长 + 0.7，库宽 = 车宽 + 0.6，车道宽 1.5 × 车长；车身出线、倒库不入、不按顺序不合格；中途停车超过 2 秒每次扣 5 分；超过 210 秒不合格。线按中心线算、顺序判定 0.5 m 是本应用口径。点位真值由这辆车（4.50 × 1.70，轴距 2.60）的几何算出，口诀只作对照。以当地考场为准。字体 Noto Sans / Noto Sans SC（SIL OFL 1.1），图标 Material Symbols（Apache-2.0）。",
        "Yard Drive v%s. Practice only: not an exam system, no training hours. Yard sizes and scoring follow the bay-park item of GA 1026-2017: bay = car length + 0.7 by car width + 0.6, lane 1.5 × car length; a body over a line, not in the bay or the wrong order fails; each stop over 2 s costs 5 points; over 210 s fails. Judging lines by their centre and the 0.5 m order rule are this app's call. The reference points come from this car's geometry (4.50 × 1.70, wheelbase 2.60); chants are only for comparison. Your test centre's rules apply. Fonts Noto Sans / Noto Sans SC (SIL OFL 1.1), icons Material Symbols (Apache-2.0).",
    )
    val open = t("打开", "Open")

    // ---- settings
    val pagePractice = t("练习", "Practice")
    val pageSystem = t("系统", "System")
    val speed = t("车速", "Speed")
    val startGap = t("起点间距", "Start gap")
    val startGapSub = t("车身到库前线延长线", "Body to bay-line extension")
    val coachCues = t("教练提示", "Coach cues")
    val topViewSet = t("俯视镜", "Top view")
    val polesSet = t("库角杆", "Corner poles")
    val polesSub = t("课程 7–8 与考试关", "Off in lessons 7–8 and test")
    val comfort = t("晕动减轻", "Comfort vignette")
    val mirrorAdj = t("镜子调节", "Mirror angle")
    val mirrorSub = t("外偏 %d° · 俯仰 %d°", "Out %d° · down %d°")
    val strictSet = t("严格", "Strict")
    val strictSub = t("中途停车即不合格（有的考场这么判）", "Any mid-way stop fails (some centres do this)")
    val coachHand = t("教练手", "Coach's hand")
    val coachHandSub = t("示范时方向盘上有只手带你转", "A ghost hand turns the wheel in demos")
    val language = t("语言", "Language")
    val input = t("输入", "Input")
    val inputs = listOf(t("手柄", "Controllers"), t("手势", "Hands"))
    val handThrottle = t("手势油门", "Hand throttle")
    val handThrottleSub = t("仅手势模式", "Hand mode only")
    val throttles = listOf(t("点按走 / 停", "Tap Go"), t("左手捏合", "Hold pinch"))
    val gazeDot = t("显示目光", "Show gaze dot")
    val gazeDotSub = t("点位库里自动开", "On in look-point library")
    val volume = t("主音量", "Master volume")
    val volumeSub = t("低 / 中 / 高三档", "Low / mid / high")
    val reseat = t("回正", "Re-seat")
    val reseatSub = t("按现在的头位置重新摆车", "Re-place the car at your head")
    val clearSub = t("会再问一次", "Asks once more")
    val aboutSub = t("v%s · 免责与出处", "v%s · Disclaimer & sources")

    // ---- lessons (design content.lessons)
    data class Lesson(val n: Int, val name: Tr, val slogan: Tr, val trials: Int)

    val lessonList8 = listOf(
        Lesson(1, t("先坐好，别急着动", "Sit down first, don't touch anything"), t("口诀是别人的，眼睛是你的", "The chant is theirs. The eyes are yours"), 1),
        Lesson(2, t("方向盘在你手里", "The wheel is yours"), t("一圈半到底，手记住", "One and a half turns, let your hands remember"), 1),
        Lesson(3, t("库角出来了", "Here comes the corner"), t("看右镜，留一拳", "Right mirror, leave a fist"), 2),
        Lesson(4, t("出库，别压库角", "Out you go, mind the corner"), t("引擎盖盖住线，再打", "Wait till the bonnet hides the line"), 2),
        Lesson(5, t("换个方向再来", "Same thing, other side"), t("右镜压线左打死", "Right mirror on the line, full lock left"), 2),
        Lesson(6, t("一气做完", "All four in one go"), t("不停车，别着急", "Keep rolling, no rush"), 1),
        Lesson(7, t("自己来", "On your own"), t("不看提示，只看镜子", "No hints, just mirrors"), 1),
        Lesson(8, t("考一次", "Take the test"), t("三分半，一把过", "Three and a half minutes, one clean run"), 1),
    )

    // ---- look-points: name, what to do, where to look, the fixed third line when the chant does not depend on the seat
    data class LpText(val name: Tr, val doIt: Tr, val look: Tr, val third: Tr?)

    val lp: Map<String, LpText> = mapOf(
        "LP1" to LpText(t("起点对位", "Line up at start"), t("做什么：车身离库前线一米八，车头刚过控制线", "Do: 1.8 m off the bay line, nose just past the control line"), t("看哪里：左窗 + 前方", "Look: left window + ahead"), t("这辆车上：控制线在眼前 1 m 横过车头", "This car: the control line crosses 1 m ahead of your eye")),
        "LP2" to LpText(t("右打死", "Full lock right"), t("做什么：控制线到左镜壳下面了，右打死", "Do: when the line's under the left shell, full lock right"), t("看哪里：左镜壳 + 窗外地面", "Look: left shell + the ground outside"), null),
        "LP3" to LpText(t("右镜看库角", "Corner in right mirror"), t("做什么：保持打死，库角和车身留一拳", "Do: hold full lock, a fist between corner and body"), t("看哪里：右镜", "Look: right mirror"), t("这辆车上：镜旁读数就是那一拳有多宽", "This car: the reading by the mirror is how wide that fist is")),
        "LP4" to LpText(t("回正", "Straighten"), t("做什么：车身和库边线平了，回正", "Do: when the body's parallel to the bay line, straighten"), t("看哪里：左镜（右镜辅助）", "Look: left mirror (right helps)"), t("这辆车上：两条线上下一样宽就是平了", "This car: the two lines the same distance apart top to bottom")),
        "LP5" to LpText(t("入库停车", "Stop in bay"), t("做什么：前沿进库 35 cm，停", "Do: nose 35 cm past the line, stop"), t("看哪里：左镜 + 后窗", "Look: left mirror + rear window"), null),
        "LP6" to LpText(t("出库左打死", "Full lock left (out)"), t("做什么：直行到点，左打死", "Do: straight on to the point, full lock left"), t("看哪里：前方（引擎盖前沿与道路边线）", "Look: ahead (bonnet edge and road edge)"), null),
        "LP7" to LpText(t("出库回正", "Straighten (out)"), t("做什么：车身正了就回正", "Do: straighten when the car's straight"), t("看哪里：前方 + 左镜", "Look: ahead + left mirror"), t("这辆车上：引擎盖两个角和道路边线一样远", "This car: both bonnet corners the same distance from the road edge")),
        "LP8" to LpText(t("控制线停车", "Stop at control line"), t("做什么：前轮过控制线 30 cm，停", "Do: front wheels 30 cm past the control line, stop"), t("看哪里：左窗（镜壳与地面控制线）", "Look: left window (shell and the control line)"), null),
        "LP9" to LpText(t("左打死（左倒库）", "Full lock left (left bay)"), t("做什么：控制线到右镜壳下面了，左打死", "Do: when the line's under the right shell, full lock left"), t("看哪里：右镜", "Look: right mirror"), null),
        "LP10" to LpText(t("左镜看库角", "Corner in left mirror"), t("做什么：保持打死，库角留一拳", "Do: hold full lock, leave a fist at the corner"), t("看哪里：左镜", "Look: left mirror"), t("这辆车上：库角从镜子外边滑进来", "This car: the corner slides in from the outer edge of the mirror")),
        "LP11" to LpText(t("回正（左倒库）", "Straighten (left bay)"), t("做什么：右镜里车身和库边线平了，回正", "Do: when the right mirror shows the body parallel, straighten"), t("看哪里：右镜", "Look: right mirror"), t("这辆车上：两条线上下一样宽就是平了", "This car: the two lines the same distance apart top to bottom")),
        "LP12" to LpText(t("出库右打死（右出库）", "Full lock right (out)"), t("做什么：直行到点，右打死", "Do: straight on to the point, full lock right"), t("看哪里：前方", "Look: ahead"), null),
    )

    val chantEarly = t("早", "early")
    val chantLate = t("晚", "late")
    val chantSpotOn = t("，刚好", ". Spot on")
    val chantThisCar = t("这辆车上：", "This car: ")
    val chantDeep = t("这辆车上：照口诀会停深 %d cm", "This car: the chant parks you %d cm too deep")
    val chantPast = t("这辆车上：口诀准，前轮过线 %.2f m", "This car: the chant is right, front wheels %.2f m past")
    val mrdChant = t("口诀 %s %d cm", "chant %d cm %s")
    val corner = t("库角", "Corner")
    val turns = t("圈数", "Turns")
    val hubCentred = t("回正", "Centred")
    val hubLeft = t("左 %s 圈", "L %s turns")
    val hubRight = t("右 %s 圈", "R %s turns")
    val hubLock = t("打死", "Full lock")
    val hubWheel = t("方向盘", "Wheel")
    val unknownLook = t("其他", "Other")
}
