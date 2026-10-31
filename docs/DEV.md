# Miband-OPlusBridge 开发手册（DEV.md）

> 本文件是跨设备开发的单一背景源。换设备 clone 本仓库即得完整上下文，无需重新踩坑。
> 详细逐版本历史见 git tag `snapshot/vNNN` 与提交信息；本文件只沉淀**架构常量、坑位与 Playbook**。

## 1. 项目定位
- 桥接小米手环 → 华为手表 → OPPO 健康（`com.heytap.health`）。参考机：华为 GT3 + 一加 Ace5Pro(ColorOS)。
- 包名 `io.github.miam1ku.mibandoplusbridge`。模块名 H2O。
- 上游：本地 `f7f38eb`(1.7.7) + cherry-pick `b1a53f9`/`bb9df8e`；**不合全量上游**（上游 UI 重构/小米导密钥等 23 个提交与 GT3 线无关，且零风险验证过）。

## 2. 目录与工具链（相对路径，跨设备一致）
```
work/
  bridge/            # git 仓库根（本仓库）
    build.sh         # 跨平台构建入口（相对路径，参数化 versionCode）
    init.local.gradle# 注入阿里云镜像
    docs/DEV.md      # 本文件
  toolchain/         # 仓库外、被 gitignore；需各设备自备或云盘同步
    jdk-17.0.20.1+1/
    gradle-8.13/
    keystore/debug.keystore   # 已例外进 git（debug 密钥，密码 android）
```
**新设备准备**：`git clone` → 把 toolchain 放到 `work/toolchain/`（云盘压缩包或 `setup` 下载）→ `./build.sh <vc>`。

## 3. 构建
```bash
./build.sh 226            # 等价于下面这条（Windows Git Bash）
# JAVA_HOME=.../toolchain/jdk-17.0.20.1+1 OPLUSBAND_KEYSTORE=.../toolchain/keystore/debug.keystore \
# OPLUSBAND_STORE_PASSWORD=android OPLUSBAND_KEY_ALIAS=androiddebugkey OPLUSBAND_KEY_PASSWORD=android \
# .../gradle-8.13/bin/gradle.bat -I init.local.gradle :app:assembleDebug -PhotReloadVersionCode=226
```
- **版本号铁律**：git tag `snapshot/vNNN` ↔ `versionCode=NNN`，用 `-PhotReloadVersionCode=NNN` 构建。**必须 > 已装版本**，否则 `adb install` 降级拒绝 + LSPosed 热重载不触发。
- 已装包的 versionCode 查：`adb shell dumpsys package io.github.miam1ku.mibandoplusbridge | grep versionCode`。
- `testDebugUnitTest` 离线必失败，验证以**实机**为准。

## 4. 华为 GT3 协议要点（关键坑）
- **0x07 通道严禁并发**（无声挂死）：所有 0x07 拉取串行 + 周期兜底（5min tick / 15min 新鲜度 / 90s watchdog）。
- **睡眠**：0x0C/0x0D 两阶段；整夜走 0x2C(`sleep_state.bin`)。链：`HuaweiSleepImporter` → `health-outbox`(deviceId=`huaweigt3_哈希`) → `OHealthSleepWriter`。
- **天气**：手表主动 0x0F/0x04（判据 tlv `0x7f==0x186AA`）；手机推链 7 步顺序不可改，三张能力位图必须动态填（GT3: Support=0xff/Extended=0xf/SunMoon=0x3）；`fixupWeather` 日出日落不在当天必须归零。
- **通知**：0x02/0x02 查上限 500B；微信 0x03 / QQ 0x0B / 短信 0x02 / 来电 0x01 / generic 0x7F。电量 0x08。
- **蓝牙恢复**：force-stop 后 RFCOMM 卡死 → `svc bluetooth disable/enable`。

## 5. 配对认证（适配新华为表必踩）
- 华为协商两种认证：**full HiChain PAKE**(`authType=0x6`，GT3 走这) 与 **HiChain Lite**(`authType=0x1`，Watch3 等走这)。
- **Lite 路径雷区**（`GetAuthRequest`）：`createRequest` 的 HMAC key 必须用 `getSecretKey()`（MAC 派生，`startHiChainLite` 保证非 null）。原代码取 `getPinCode()` 在 Lite 分支得 null → `digestResponse(null)` 返回 null → `ByteBuffer.wrap(null)` 抛 **"Attempt to get length of null array"**。GT3 不进此路径永远测不到；Watch3 等 Lite 设备才触发。**适配新华为表必须在 Lite 路径真机闭环。**

## 6. 步数/卡路里 5 条数据路径 Playbook（修一处≠修全部）
1. sport 缓存 `querySportData`/`queryTodaySportData`（外卡 calorie）
2. `SportDataStat.getTotalSteps()` 经 `main.viewmodel`(底部步数卡) 与 `daily.model`(顶部大号+每日活动详情)
3. `SportDataStat.getTotalCalories()` 经 `daily.model`(每日活动详情消耗)
4. 直接 `readSportHealthData` 读表 1002（独立"消耗"卡片 → `ConsumptionHistoryActivity`，RxJava Observable，须 `doOnNext` 动态代理改，且排除 goal setter）
5. 首页步数真正数据源 = `getTotalSteps()` 跨设备求和（手机+手表=双重计数）；覆盖判定 `main.viewmodel` 或 `daily.model` 且为今天那行。
- 🔥 **setResult 类型坑**：`getTotalSteps()` 返回 int，塞 Long → `ClassCastException` 崩溃。
- **验收纪律**：`CACHE_READ` 只证覆盖触发，证不了界面用了它；必须截图 + 算术（手表值变 Δ 看界面同步 Δ）。

## 7. 睡眠日期（v225 钳制）
- `OHealthSleepPlan.sleepDate` 用「wake 本地 hour≥20:00 翻次日」分日规则（20:00–20:00 窗口）+ 时区换算。正常夜眠归当天/昨天；白天碎片睡眠(woke≥20:00) 或手表/手机时区错位会显示未来日。
- v225 加防御钳制：算出的 date > 今天本地日时回退 fall(入睡) 本地日。正常夜眠(date≤今天) 完全不变。

## 8. 设备空间双卡（v197 根治）
- 欢太健康 GT3 卡 `device_id=MD5("54:52:84:9F:D1:3D" 大写冒号)=defa361518d5bdd0f83cf67ce371f26e`。桥接对外主键必须 `normalizeMac()`(大写冒号再 MD5) 才能与健康卡同键合一；内部主键 `huaweigt3_` 仍留 band-state。`legacyWrongId()` 清理旧键防双卡。
- **双卡"复发"实为安装瞬态**：全新安装后未发 `MODULE_UPDATED` 广播(或没跑完~25s)前短暂 2 张卡，广播后并成 1 张。验证：`device_cards.db` GT3 主键 `defa3615` 始终 1 行。

## 9. 排障工具箱
- 注入判据：logcat `TAG=OplusBandBridge`。装机后 `install -r` → `am broadcast io.github.miam1ku.mibandoplusbridge.MODULE_UPDATED` → 等~25s → `force-stop com.heytap.health`（及桥接进程 `am start` 重启）。
- `adb logcat -d` 卡死用 `-t N`。健康 DB 加密(SQLCipher) 则走 hook 回读。
- 关键日志点：`SDS_TOTAL_STEPS`(步数覆盖) / `HOST_READ_CAL_PATCH`(卡路里读层补) / `OHEALTH_SLEEP_BEGIN`+`OHEALTH_SLEEP_DAY_*`(睡眠分日) / `MODULE`(注入)。

## 10. 上游策略
- 只 cherry-pick 需要的上游 commit（如 `b1a53f9`/`bb9df8e`），**不 merge 上游 main**（会带入与 GT3 无关的 UI 重构等 23 个提交）。
- 远程开发分支用 `dev/h2o`（纯本地开发线），不要推 `origin/main`（混着上游线，会 non-fast-forward 拒绝）。
