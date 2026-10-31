# Miband-OplusBridge 项目记忆（git 镜像版）

> **本文件是 `.workbuddy/memory/MEMORY.md` 的 git 托管镜像。** 换设备 clone 本仓库后，
> 把本文件**整文件复制**到项目工作区的 `.workbuddy/memory/MEMORY.md`，WorkBuddy 新会话就会自动注入这些经验。
> 详细逐日排查见 `.workbuddy/memory/YYYY-MM-DD.md`（未进 git，需随工作区搬运或重建）。
>
> 开发流程/工具链见同目录 `DEV.md`；本文件聚焦**记忆、版本决策、方法护栏**。

## 1. 定位
- 桥接小米手环步数/卡路里/睡眠/天气/通知到 OPPO 健康(`com.heytap.health`)。参考机：华为 GT3 + 一加 Ace5Pro(ColorOS)。包 `io.github.miam1ku.mibandoplusbridge`，模块名 H2O。
- 版本号铁律：git tag `snapshot/vNNN` ↔ build `versionCode=NNN`（用 `-PhotReloadVersionCode=NNN` 构建）。**必须 > 已装版本**，否则降级拒绝 + LSPosed 热重载不触发。

## 2. 逐版本修复决策表（重点版本；其余见 git tag）
| 版本 | 修了什么 | 关键坑/决策 |
|---|---|---|
| v185 | 基准 tag | 上游 `f7f38eb`(1.7.7)+cherry-pick `b1a53f9`/`bb9df8e` 起算 |
| v188 | 步数同步 OK（用户确认此版正常） | 当时手机步数贡献≈0，未暴露双重计数 |
| v195 | 稳定发布版 | **Watch3 配对 NPE 已潜伏此版**（HiChain Lite 路径） |
| v197 | 双卡根治 | `normalizeMac()`(大写冒号再 MD5) 与健康卡同键合一；`legacyWrongId()` 清旧键 |
| v214 | 底部步数卡修复 | 🔥 `getTotalSteps()` 返回 **int**，塞 Long→ClassCastException 崩溃，须用 Integer |
| v216 | 步数+卡路里 home/detail 对齐 | 冷启动静态变量=-1 → 改磁盘持久化 `watchCaloriesNow()`/`watchStepsNow()` |
| v223 | 独立"消耗"卡片 3083→52 | 读层 `readSportHealthData` 是 RxJava Observable，须 `doOnNext` 动态代理改，且**排除 goal setter** |
| v224 | 卡路里往日保护 + 双卡瞬态澄清 | `getDate()` 比较只覆盖今天那行；双卡=安装瞬态(未发 MODULE_UPDATED 广播) |
| v225 | 配对 NPE(Lite key) + 睡眠未来日钳制 | 见 §4 / §5 |

## 3. 步数/卡路里 5 条数据路径 Playbook（修一处≠修全部）
1. sport 缓存 `querySportData`/`queryTodaySportData`（外卡 calorie）
2. `SportDataStat.getTotalSteps()` 经 `main.viewmodel`(底部步数卡) 与 `daily.model`(顶部大号+每日活动详情)
3. `SportDataStat.getTotalCalories()` 经 `daily.model`(每日活动详情消耗)
4. 直接 `readSportHealthData` 读表 1002（独立"消耗"卡片 → `ConsumptionHistoryActivity`，RxJava Observable，须 `doOnNext` 动态代理改，且排除 goal setter）
5. 首页步数真正数据源 = `getTotalSteps()` 跨设备求和（手机+手表=双重计数）；覆盖判定 `main.viewmodel` 或 `daily.model` 且为今天那行。
- 🔥 setResult 类型坑：`getTotalSteps()` 返回 int，塞 Long→ClassCastException 崩溃。
- 验收纪律：`CACHE_READ` 只证覆盖触发，证不了界面用了它；**必须截图+算术**（手表值变 Δ 看界面同步 Δ）。

## 4. 配对认证（适配新华为表必踩，v225 关键坑）
- 华为协商两种认证：**full HiChain PAKE**(`authType=0x6`，GT3 走这) 与 **HiChain Lite**(`authType=0x1`，Watch3 等走这)。
- Lite 路径雷区（`GetAuthRequest`）：HMAC key 必须用 `getSecretKey()`（MAC 派生，`startHiChainLite` 保证非 null）。原代码取 `getPinCode()` 在 Lite 分支得 null → `digestResponse(null)` 返回 null → `ByteBuffer.wrap(null)` 抛 **"Attempt to get length of null array"**。GT3 不进此路径永远测不到；Watch3 等 Lite 设备才触发。**适配新华为表必须在 Lite 路径真机闭环。**
- 结论：修一次所有走 Lite 的表都受益——这是"能力驱动"而非"机型分支"的证据。

## 5. 睡眠日期（v225 钳制）
- `OHealthSleepPlan.sleepDate` 用「wake 本地 hour≥20:00 翻次日」分日规则（20:00–20:00 窗口）+ 时区换算。正常夜眠归当天/昨天；白天碎片睡眠(woke≥20:00) 或手表/手机时区错位会显示未来日（如今天 10/30 显示 10/31）。
- v225 加防御钳制：算出的 date > 今天本地日时回退 fall(入睡) 本地日。正常夜眠(date≤今天) 完全不变。

## 6. 设备空间双卡（v197 根治）
- 欢太健康 GT3 卡 `device_id=MD5("54:52:84:9F:D1:3D" 大写冒号)=defa361518d5bdd0f83cf67ce371f26e`。`normalizeMac()` 同键合一；`legacyWrongId()` 清旧键防双卡。
- 双卡"复发"=安装瞬态：全新安装后未发 `MODULE_UPDATED` 广播(或没跑完~25s)前短暂 2 张卡，广播后并成 1 张。验证 `device_cards.db` GT3 主键 `defa3615` 始终 1 行。

## 7. 避免拆东墙补西墙的 6 条护栏（方法论文献）
修测试者的 bug 容易破坏已稳定态（v214–v224 已亲历打地鼠）。根因是"无单一真相源 + 无回归护栏"。硬规矩：
1. **单一真相源**：定义 `BridgeMetrics` 适配器，所有 hook 从它读正确值，而非各自算/覆盖；把 N 条路径收敛成 1 条。
2. **离线回归夹具**：给定 fixture（手机步数、手表步数、日期），断言 5 条路径输出一致；每次发版前跑。
3. **分支纪律**：稳定版发一版定格；修 bug 走 `fix/<问题>` 分支，夹具过关 + GT3 参考机验证后才合；继续打 `snapshot/vNNN` tag。
4. **声明式规则替代改共享守卫**：用"日期保护""调用方包名白名单"这类加法规则，不碰共享状态。
5. **设备能力隔离**：新表修复（如 Lite 路径）绝不能改 GT3 协商路径；用协商结果分流，不用全局 flag。
6. **日志 Playbook 先查后动**：动手前先用算术/logcat 复现，结论写进 Playbook，别每次从头排查。

## 8. 构建铁律
- `./build.sh <vc>`（相对路径，Windows Git Bash）。`testDebugUnitTest` 离线必失败，验证以实机为准。
- 已装包 versionCode：`adb shell dumpsys package io.github.miam1ku.mibandoplusbridge | grep versionCode`。

## 9. 排障工具箱
- 注入判据：logcat `TAG=OplusBandBridge`。装机 `install -r` → `am broadcast io.github.miam1ku.mibandoplusbridge.MODULE_UPDATED` → 等~25s → `force-stop com.heytap.health`（及桥接进程 `am start` 重启）。
- 关键日志点：`SDS_TOTAL_STEPS`(步数覆盖) / `HOST_READ_CAL_PATCH`(卡路里读层补) / `OHEALTH_SLEEP_BEGIN`+`OHEALTH_SLEEP_DAY_*`(睡眠分日) / `MODULE`(注入)。
- 健康主库 SQLCipher 加密(root 读不了)，验证走 hook 回读；`adb logcat -d` 卡死用 `-t N`。
