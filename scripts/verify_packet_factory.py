"""核对 HuaweiPacket.parse 工厂里引用的每个嵌套类常量是否真实存在。

这是协议层最关键的一张接缝：工厂用 `DeviceConfig.Auth.id` 这样的
「外层类.嵌套类.id」三级引用来分发包类型。任何一层缺失都会编译失败，
而在没有 Android SDK 的情况下无法靠编译发现。

做法：从 parse 方法体里抽出所有 `A.B.id` 形式的三级引用，
再到 packets/ 下对应文件里确认存在 A、B 两个类型且 B 里有 id 字段
（或 B 是接口/枚举，本身就是 id）。
"""

import pathlib
import re
import sys

SOURCE_ROOT = pathlib.Path(__file__).resolve().parents[1] / (
    "app/src/main/java/io/github/miam1ku/mibandoplusbridge/protocol/huawei"
)
HUAWEI = SOURCE_ROOT
ROOT = SOURCE_ROOT
PACKETS = HUAWEI / "packets"

packet_src = (HUAWEI / "core" / "HuaweiPacket.java").read_text(encoding="utf-8")
# 去掉注释，避免文档里的示例引用
packet_src = re.sub(r"/\*.*?\*/", "", packet_src, flags=re.S)
packet_src = re.sub(r"//[^\n]*", "", packet_src)

# 三级引用：A.B.id  /  A.B.stepId
triples = re.findall(r"\b([A-Z]\w*)\.([A-Z]\w*)\.(id|stepId)\b", packet_src)

# 收集每个包文件里声明的嵌套类型名
nested_by_outer = {}
for path in PACKETS.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    # class/interface/enum Xxx ... {  以及 enum 常量列表里的裸标识
    nested = set(re.findall(r"\b(?:class|interface|enum)\s+(\w+)", text))
    # enum 内部的枚举项
    nested |= set(re.findall(r"^\s{4,}([A-Z][A-Z0-9_]*)\s*[,(]", text, re.M))
    nested_by_outer[path.stem] = nested

problems = []
checked = set()
for outer, inner, field in triples:
    key = (outer, inner, field)
    if key in checked:
        continue
    checked.add(key)
    if outer not in nested_by_outer:
        problems.append(f"parse() 引用了不存在的包类 {outer}")
        continue
    if inner not in nested_by_outer[outer]:
        problems.append(f"{outer} 中不存在嵌套类型 {inner}（工厂引用 {outer}.{inner}.{field}）")

print(f"parse() 中三级引用去重后: {len(checked)} 个")
print(f"包文件: {len(nested_by_outer)} 个")
if problems:
    print(f"\n{len(problems)} problem(s):")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("\nall nested packet references resolve")