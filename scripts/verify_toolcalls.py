"""核对华为包内对本地工具类 (GB / StringUtils) 的调用是否与实际提供的方法匹配。

Step 3 把 GB 框架剥掉后，GB / StringUtils 只保留了协议层真正用到的几个方法。
原代码里对这些类的调用点有几十处，逐个人工核对容易漏 —— 这里从「方法声明」与
「调用点」两侧提取签名做集合比对，报出所有无法解析的调用。
"""

import pathlib
import re
import sys

SOURCE_ROOT = pathlib.Path(__file__).resolve().parents[1] / (
    "app/src/main/java/io/github/miam1ku/mibandoplusbridge/protocol/huawei"
)
HUAWEI = SOURCE_ROOT
ROOT = SOURCE_ROOT

# 本项目实际提供的工具类：类名 -> 声明过的方法名（含重载，按名字索引）
PROVIDERS = {
    "GB": [
        "hexStringToByteArray", "bytesToHex", "hexdump", "hexToBytes", "formatRssi",
    ],
    "StringUtils": [
        "bytesToHex", "hexToBytes",
    ],
}

problems = []

# 1) 收集声明（含 static 方法）
for cls, declared in PROVIDERS.items():
    path = HUAWEI / "core" / f"{cls}.java"
    if not path.is_file():
        problems.append(f"工具类缺失: core/{cls}.java")
        continue
    text = path.read_text(encoding="utf-8")
    found = set(re.findall(r"public\s+static\s+[\w\[\]<>,.\s?]+\s+(\w+)\s*\(", text))
    missing = set(declared) - found
    if missing:
        problems.append(f"core/{cls}.java: 声明表中列了但文件里没有 {sorted(missing)}")

# 2) 扫描所有调用点
call_re = re.compile(r"\b(GB|StringUtils)\.(\w+)\s*\(")
call_counts = {}
for path in sorted(HUAWEI.rglob("*.java")):
    if path.name in ("GB.java", "StringUtils.java"):
        continue
    rel = path.relative_to(HUAWEI)
    text = path.read_text(encoding="utf-8")
    # 去掉注释，避免注释里的示例代码误报
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    for cls, method in call_re.findall(text):
        call_counts[(cls, method)] = call_counts.get((cls, method), 0) + 1
        if method not in PROVIDERS.get(cls, []):
            problems.append(f"{rel}: 调用了不存在的 {cls}.{method}()")

print("=== 工具类调用统计 ===")
for (cls, method), n in sorted(call_counts.items(), key=lambda kv: -kv[1]):
    flag = "" if method in PROVIDERS.get(cls, []) else "   <== 缺失"
    print(f"  {cls}.{method}()  x{n}{flag}")

print(f"\ntotal call sites: {sum(call_counts.values())}, distinct: {len(call_counts)}")

if problems:
    print(f"\n{len(problems)} problem(s):")
    for p in problems:
        print("  -", p)
    sys.exit(1)

print("\nall tool-class call sites resolve")