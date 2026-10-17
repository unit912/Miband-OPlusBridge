"""华为包内「方法级」引用审计。

语法校验（括号平衡、import 存在）不足以发现类型层面的断裂，例如：
provider 返回了 HuaweiDeviceCapabilities，但调用方还按 HuaweiState 用方法。
这里做一个针对性检查：找出所有「接收者是本包某个类实例」的调用，
逐个核对被调方法是否在该类的源码里被声明过（允许继承自 Object 的常见方法）。

覆盖范围有意保守 —— 只检查形如 `x.method(` 且 x 是已知类实例变量的调用，
无法做完整类型推导。目标是抓出移植过程中改签名/改类导致的硬错误。
"""

import pathlib
import re
import sys

SOURCE_ROOT = pathlib.Path(__file__).resolve().parents[1] / (
    "app/src/main/java/io/github/miam1ku/mibandoplusbridge/protocol/huawei"
)
HUAWEI = SOURCE_ROOT
ROOT = SOURCE_ROOT

files = sorted(HUAWEI.rglob("*.java"))


def strip(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r'"(?:[^"\\]|\\.)*"', '""', text)
    return text


# 每个类声明了哪些方法名
methods = {}
# 每个类里由 new / 字段声明产生的实例变量名 -> 类型
var_types = {}
# extends 关系：子类 -> 父类
extends_map = {}

for path in files:
    text = strip(path.read_text(encoding="utf-8"))
    cls = path.stem
    methods.setdefault(cls, set())
    methods[cls] |= set(re.findall(r"[\w<>\[\],.\s]+\s+(\w+)\s*\(", text))
    # 局部变量/字段的显式类型
    for name, type_name in re.findall(r"\b([A-Z]\w*)\s+(\w+)\s*[=;,)]", text):
        var_types.setdefault(cls, {})[name] = type_name
    # final 字段带初始化 new Xxx(
    for name, type_name in re.findall(r"(\w+)\s*=\s*new\s+([A-Z]\w*)\s*\(", text):
        var_types.setdefault(cls, {})[name] = type_name
    # extends 关系（本包的类都是单继承）
    ext = re.search(r"\bclass\s+" + re.escape(cls) + r"\s+extends\s+(\w+)", text)
    if ext:
        extends_map[cls] = ext.group(1)

OBJECT_METHODS = {
    "toString", "hashCode", "equals", "getClass", "notify", "notifyAll", "wait",
    "clone", "finalize",
}

# 基类及其可继承的方法。审计不做完整的类型推导，遇到这些基类上的方法
# 一律放行 —— 它们的声明在别的文件里（继承而来）。
INHERITED = {
    "Request": {"doPerform", "handleResponse", "handleNext", "nextRequest",
                 "stopChain", "getName", "setFinalizeReq", "getContext",
                 "autoRemoveFromResponseHandler", "setupTimeoutUntilNext",
                 "onTransportClosed"},
}

problems = []
seen_calls = 0

for path in files:
    text = strip(path.read_text(encoding="utf-8"))
    cls = path.stem
    vars_here = var_types.get(cls, {})
    for var, called in re.findall(r"\b(\w+)\.(\w+)\s*\(", text):
        type_name = vars_here.get(var)
        if type_name is None or type_name not in methods:
            continue
        seen_calls += 1
        if called in OBJECT_METHODS or called in methods[type_name]:
            continue
        if called in INHERITED.get(type_name, set()):
            continue
        # 沿 extends 链找祖先上声明的方法
        ancestor = type_name
        for _ in range(5):
            ancestor = extends_map.get(ancestor)
            if not ancestor:
                break
            if called in methods.get(ancestor, set()):
                break
        else:
            ancestor = None
        if ancestor and ancestor in methods and called in methods[ancestor]:
            continue
        problems.append(
            f"{path.relative_to(HUAWEI)}: {var} ({type_name}) 调用了未声明的方法 {called}()"
        )

print(f"跟踪到的实例方法调用: {seen_calls}")
print(f"声明的方法名（含各类型）: {sum(len(v) for v in methods.values())}")

# ---------------------------------------------------------------------------
# 嵌套类引用闭合。
#
# 方法级审计抓不到「引用了一个不存在的嵌套类」。华为协议代码里这种情况尤其
# 容易出现，因为请求子类常以简名直接 `throw new ResponseTypeMismatchException(...)`
# —— 它是 Request 的静态嵌套类，被继承作用域解析后无需写 `Request.` 前缀。
# 所以这里同时检查两种形式：
#   1. 限定形式  `Request.Foo`
#   2. 非限定形式 `Foo`（new Foo / catch(Foo ...) / throws Foo）
# ---------------------------------------------------------------------------

def collect_nested(text, outer):
    """返回该文件中 outer 之外声明的所有嵌套类名。"""
    found = set()
    for match in re.finditer(
        r"\b(?:public|protected|private|static|final|abstract|sealed|non-sealed|\s)*"
        r"(?:class|interface|enum|record)\s+([A-Za-z_]\w*)",
        text,
    ):
        name = match.group(1)
        if name != outer:
            found.add(name)
    return found


nested_declared = {}   # 外层类简名 -> {内部类简名, ...}
top_level = set()
imports_by_file = {}

for path in files:
    raw = path.read_text(encoding="utf-8")
    outer = path.stem
    top_level.add(outer)
    inner = collect_nested(raw, outer)
    if inner:
        nested_declared.setdefault(outer, set()).update(inner)
    imports_by_file[path] = set(
        re.findall(r"^import\s+[\w.]*?([A-Z]\w*)\s*;", raw, re.M)
    )

# JDK / Android 里高频出现的类型，它们不是本包定义的，不参与闭合检查。
# 用后缀而非枚举来放行：Exception 家族统一放行，集合/字符串等按需列举。
JDK_EXACT = {
    "Object", "Thread", "Math", "System", "String", "StringBuilder", "StringBuffer",
    "Integer", "Long", "Boolean", "Byte", "Short", "Character", "Double", "Float",
    "Math", "Arrays", "Collections", "Objects", "Optional", "Random", "Locale",
    "Pattern", "Matcher", "UUID", "ByteBuffer", "BigInteger", "BigDecimal",
    "ArrayList", "LinkedList", "HashMap", "TreeMap", "List", "Map", "Set", "HashSet",
    "Context", "View", "Intent", "Bundle", "Uri", "TextUtils", "Log",
    "Handler", "Looper", "Build", "Toast", "Environment", "DisplayMetrics",
    "JSONObject", "JSONArray", "Throwable",
}


def is_jdk_type(name):
    if name in JDK_EXACT:
        return True
    # 所有标准异常家族：xxxException / xxxError
    if name.endswith("Exception") or name.endswith("Error"):
        return True
    return False


# 所有已知的「可解析类型」：顶层类 + 任意类的嵌套类 + JDK 常用类型
all_known_types = set(top_level)
for inner_set in nested_declared.values():
    all_known_types |= inner_set

nested_problems = []
for path in files:
    text = strip(path.read_text(encoding="utf-8"))
    imported = imports_by_file[path]

    # 形式 1：限定引用 Outer.Inner
    for outer, inner in re.findall(r"\b([A-Z]\w*)\.([A-Z]\w*)\b", text):
        if outer not in top_level:
            continue
        if inner in nested_declared.get(outer, set()):
            continue
        if inner.isupper() or inner[:1].islower():
            continue
        nested_problems.append(
            f"{path.relative_to(HUAWEI)}: 引用 {outer}.{inner}，"
            f"但 {outer} 中没有嵌套类 {inner}"
        )

    # 形式 2：非限定引用（new Foo / catch (Foo / throws Foo）
    for name in re.findall(r"\bnew\s+([A-Z]\w*)\s*[(<]", text):
        if name in all_known_types or name in imported or is_jdk_type(name):
            continue
        nested_problems.append(
            f"{path.relative_to(HUAWEI)}: new {name}(...) 指向未知类型"
            f"（既非本包顶层类，也非任何类的嵌套类）"
        )
    for name in re.findall(r"\bcatch\s*\(\s*([A-Z]\w*)", text):
        if name in all_known_types or name in imported or is_jdk_type(name):
            continue
        nested_problems.append(
            f"{path.relative_to(HUAWEI)}: catch ({name} ...) 指向未知异常类型"
        )
    for name in re.findall(r"\bthrows\s+([A-Z]\w*)", text):
        if name in all_known_types or name in imported or is_jdk_type(name):
            continue
        nested_problems.append(
            f"{path.relative_to(HUAWEI)}: throws {name} 指向未知异常类型"
        )

problems.extend(nested_problems)

# 输出 unresolved 的去重视图
uniq = sorted(set(problems))
if uniq:
    print(f"\n{len(problems)} 处调用（{len(uniq)} 种）需要人工确认：")
    for p in uniq:
        print("  -", p)
    sys.exit(1)

print("\nno unresolved instance-method calls")