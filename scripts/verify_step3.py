"""华为协议层的静态校验。

Gradle 无法运行（本机无 JDK / Android SDK），因此这里做四类纯 Python 可完成的检查：

1. 符号一致性：华为代码里出现的 GB 框架符号（GBDevice、DeviceSupport、
   GBApplication、nodomain.* 等）必须已清零。
2. 内部引用闭合：本包内互相 import 的类必须存在。
3. 语法平衡：去掉注释与字符串后，括号 / 花括号必须平衡。
4. 字段重复声明：同一类内同名字段声明两次即报错（语法合法，只有编译才炸）。

不检查类型正确性 —— 那需要 android.jar 与 Gradle。
"""

import pathlib
import re
import sys

SOURCE_ROOT = pathlib.Path(__file__).resolve().parents[1] / (
    "app/src/main/java/io/github/miam1ku/mibandoplusbridge/protocol/huawei"
)
HUAWEI = SOURCE_ROOT
# java 源码根，用于生成报错时的相对路径
ROOT = SOURCE_ROOT.parents[4]

# GB 框架符号，出现在华为代码里即为未清理干净
# (pattern, 说明) —— 用词边界匹配，避免误伤华为自有命名（DeviceSupportType、GBDeviceMusic）
FORBIDDEN = [
    (r"nodomain\.freeyourgadget", "GB 包引用"),
    (r"\bGBDevice\b(?!Music)", "GB 设备模型"),
    (r"\bGBApplication\b", "GB 应用单例"),
    (r"\bDeviceSupport\b(?!Type)", "GB 设备支持基类"),
    (r"\bBleNamesResolver\b", "GB BLE 名称解析"),
    (r"\bDeviceCoordinator\b", "GB 设备协调器"),
    (r"\bAbstractBluetoothDeviceSupport\b", "GB 蓝牙基类"),
    (r"\bOperationStatus\b", "GB 操作状态"),
    (r"org\.slf4j", "slf4j（应由 HuaweiLogger 门面替代）"),
]

# 华为代码里引用到的、必须在本包内存在的类（简名 -> 所在子包）
EXPECTED = {
    "HuaweiBrTransport": "br",
    "HuaweiTransactionBuilder": "br",
    "HuaweiResponseManager": "br",
    "HuaweiSocketAction": "btbr",
    "HuaweiPlainAction": "btbr",
    "HuaweiWriteAction": "btbr",
    "HuaweiSleepAction": "btbr",
    "HuaweiFunctionAction": "btbr",
    "HuaweiSocketCallback": "btbr",
    "HuaweiTransaction": "btbr",
    "Request": "requests",
    "HuaweiSupportProvider": "service",
    "HuaweiDataSyncManager": "service",
    "HuaweiDeviceCapabilities": "service",
    "HuaweiDeviceType": "core",
    "HuaweiPacket": "core",
    "HuaweiTLV": "core",
    "HuaweiCrypto": "core",
    "HuaweiConstants": "core",
    "GB": "core",
    # Step 4：握手初始化链
    "HuaweiIdentityStore": "service",
    "GetLinkParamsRequest": "requests",
    "GetDeviceStatusRequest": "requests",
    "GetBondParamsRequest": "requests",
    "GetAuthRequest": "requests",
    "GetHiChainRequest": "requests",
    "GetExpandCapabilityRequest": "requests",
}

problems = []


def strip_code(text):
    """去掉注释与字符串字面量，避免其中的括号干扰平衡检查。

    同时把行数保持不变（用换行占位），这样后面按行号定位仍然对得上。
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text[i:i + 3] == '"""':
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(_blank(text[i:end]))
            i = end
            continue
        if c in ('"', "'"):
            quote = c
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == quote:
                    j += 1
                    break
                j += 1
            out.append(_blank(text[i:j]))
            i = j
            continue
        if text[i:i + 2] == "//":
            end = text.find("\n", i)
            end = n if end < 0 else end
            out.append(_blank(text[i:end]))
            i = end
            continue
        if text[i:i + 2] == "/*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append(_blank(text[i:end]))
            i = end
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _blank(fragment):
    """把一段文本替换成等长空白，但保留换行以维持行号。"""
    return "".join("\n" if ch == "\n" else " " for ch in fragment)


def class_intervals(code):
    """返回每个 class/interface/enum 的 (起始行, 结束行, 类名)。

    先按行扫描找出类型声明所在行，再从该行往后配对花括号。
    """
    lines = code.split("\n")
    result = []
    for idx, line in enumerate(lines, 1):
        m = re.search(
            r"\b(?:class|interface|enum|record)\s+([A-Za-z_]\w*)", line
        )
        if not m:
            continue
        # 排除匿名类（new X() { ... }）与 new 表达式里的关键字误命中
        if re.search(r"\bnew\s+$", line[:m.start()].rstrip()):
            continue
        depth = 0
        started = False
        for j in range(idx - 1, len(lines)):
            depth += lines[j].count("{")
            if "{" in lines[j]:
                started = True
            depth -= lines[j].count("}")
            if started and depth <= 0:
                result.append((idx, j + 1, m.group(1)))
                break
    return result


def field_declarations(text):
    """产出 (行号, 字段名)，只认带访问修饰符的成员字段。

    局部变量不带修饰符，会被自然排除。
    """
    for line_no, line in enumerate(text.split("\n"), 1):
        stripped = line.strip()
        m = re.match(
            r"(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?"
            r"[A-Za-z_][\w.$<>\[\],?\s]*?\s([A-Za-z_]\w*)\s*(?:=|;)",
            stripped,
        )
        if m:
            yield line_no, m.group(1)


java_files = sorted(HUAWEI.rglob("*.java"))
if not java_files:
    print("no java files found under", HUAWEI)
    sys.exit(1)

for path in java_files:
    rel = path.relative_to(HUAWEI)
    text = path.read_text(encoding="utf-8")
    code = strip_code(text)

    for pattern, label in FORBIDDEN:
        if re.search(pattern, code):
            problems.append(f"{rel}: 未清理的 {label} ({pattern})")

    for open_c, close_c in (("{", "}"), ("(", ")")):
        if code.count(open_c) != code.count(close_c):
            problems.append(
                f"{rel}: {open_c}{close_c} 不平衡 "
                f"({code.count(open_c)} vs {code.count(close_c)})"
            )

    if not re.search(r"^package\s+io\.github\.miam1ku", text, re.M):
        problems.append(f"{rel}: package 声明缺失或异常")

    # 字段重复声明。GB 的包类普遍含大量嵌套类，同名字段跨类合法，
    # 所以先把每个字段归属到"包含它的最内层 class"，只在同一 class 内判重复。
    intervals = class_intervals(code)
    scope_fields = {}
    for line_no, name in field_declarations(text):
        owner = min(
            (iv for iv in intervals if iv[0] <= line_no <= iv[1]),
            key=lambda iv: iv[1] - iv[0],
            default=None,
        )
        if owner is None:
            continue
        # 用区间起点而非类名做 key —— 华为包类里多个嵌套类常同名（Response/Block），
        # 用类名会把它们误并成同一个作用域。
        scope_fields.setdefault((owner[0], name), []).append((owner[2], line_no))
    for (scope_start, name), hits in scope_fields.items():
        if len(hits) > 1:
            scope_name = hits[0][0]
            where = ", ".join(f"L{n}" for _, n in hits)
            problems.append(
                f"{rel}: 字段 {name} 在类 {scope_name} 内重复声明 ({where})"
            )

# 校验 EXPECTED 里列出的类确实存在
for name, pkg in EXPECTED.items():
    target = HUAWEI / pkg / f"{name}.java"
    if not target.is_file():
        problems.append(f"missing class: {name} (expected {pkg}/{name}.java)")

# 本包内 import 但正文未再引用的类 —— 移植过程中删代码容易留下残 import。
# 不影响编译，但会掩盖「这个类其实已经不用了」的事实。
for path in java_files:
    rel = path.relative_to(HUAWEI)
    text = path.read_text(encoding="utf-8")
    body = re.sub(r"^import .*$", "", text, flags=re.M)
    for match in re.finditer(r"^import\s+([\w.]+);", text, re.M):
        fqcn = match.group(1)
        if not fqcn.startswith("io.github.miam1ku.mibandoplusbridge.protocol.huawei"):
            continue
        simple = fqcn.rsplit(".", 1)[1]
        if not re.search(r"\b" + re.escape(simple) + r"\b", body):
            problems.append(f"{rel}: 未使用的 import {fqcn}")

print(f"scanned {len(java_files)} java files under protocol/huawei")

# 交叉引用：本包内 import 的兄弟类是否存在。
# 嵌套类（如 HuaweiPacket.ParamsProvider）只需其外层类文件存在。
declared = {p.stem for p in java_files}
for path in java_files:
    rel = path.relative_to(ROOT)
    text = path.read_text(encoding="utf-8")
    for match in re.finditer(
        r"^import\s+(io\.github\.miam1ku\.mibandoplusbridge\.protocol\.huawei\.[\w.]+);",
        text,
        re.M,
    ):
        fqcn = match.group(1)
        parts = fqcn.split("huawei.", 1)[1].split(".")
        # 末段是类名；嵌套类的 import 末段是内层名，外层名在倒数第二段。
        names = {parts[-1]}
        if len(parts) > 1:
            names.add(parts[-2])
        if not names & declared:
            problems.append(f"{rel}: import 指向不存在的类 {fqcn}")

# ---------------------------------------------------------------------------
# 第三方依赖必须已在 app/build.gradle 声明。
#
# 这类错误最容易漏：import org.apache.commons.lang3.ArrayUtils 语法完全合法、
# 括号也平衡、包内引用也闭合，只有 javac 才会报 "package does not exist"。
# 本机跑不了 Gradle，所以这里直接比对 build.gradle 的依赖清单。
#
# 只针对「项目内确实没有源码」的包 —— 桥接项目自己实现了 GB 的 protobuf，
# 那些是本仓库源码，不算第三方依赖。
# ---------------------------------------------------------------------------
GRADLE = SOURCE_ROOT.parents[5] / "app/build.gradle"
gradle_text = GRADLE.read_text(encoding="utf-8") if GRADLE.is_file() else ""

# 允许清单里出现的第三方前缀。JDK / Android SDK / androidx / 本项目产物
# 不需要 Gradle 依赖声明。
ALLOWED_PREFIXES = (
    "java.", "javax.", "android.", "androidx.", "kotlin.",
    "io.github.miam1ku.",
    "nodomain.freeyourgadget.",  # 构建期由 xiaomi.proto 生成
    "org.json.",                 # Android 平台自带
)
# build.gradle 里出现即视为已声明（同时覆盖 compileOnly / implementation /
# testImplementation 等所有 configuration）
declared_tokens = set(re.findall(r"[A-Za-z0-9_.\-]+", gradle_text))

third_party = {}
for path in java_files:
    rel = path.relative_to(ROOT)
    text = path.read_text(encoding="utf-8")
    for match in re.finditer(r"^import\s+(?:static\s+)?([\w.]+);", text, re.M):
        fqcn = match.group(1)
        if fqcn.startswith(ALLOWED_PREFIXES):
            continue
        group = fqcn.rsplit(".", 1)[0]
        if group.startswith("io.github.miam1ku"):
            continue
        # 取 group 的前两段作为依赖坐标（如 org.apache.commons.lang3 ->
        # commons-lang3 是它自己的坐标，映射表在下面）
        third_party.setdefault(fqcn, set()).add(str(rel))

# 第三方 group -> build.gradle 里应出现的坐标 token
GROUP_COORD = {
    "org.apache.commons.lang3": "commons-lang3",
    "org.apache.commons.codec": "commons-codec",
    "org.slf4j": "slf4j",
    "com.google.protobuf": "protobuf",
    "org.bouncycastle": "bcprov",
    "org.bouncycastle": "bcprov",
}
for fqcn, users in sorted(third_party.items()):
    group = fqcn.rsplit(".", 1)[0]
    coord = GROUP_COORD.get(group)
    if coord is None:
        problems.append(
            f"{sorted(users)[0]}: 未知第三方包 {group}（未在映射表中，"
            f"请人工确认 build.gradle 是否声明）"
        )
        continue
    if coord not in declared_tokens:
        where = ", ".join(sorted(users))
        problems.append(
            f"{where}: 引用 {group}.*，但 app/build.gradle 未声明 {coord} 依赖"
        )

if problems:
    print(f"\n{len(problems)} problem(s):")
    for p in problems:
        print("  -", p)
    sys.exit(1)

print("all checks passed")
