"""华为协议栈 + Step5 抽象层的真实编译验证。

为什么要有这个脚本
------------------
前四步我用「静态检查」代替编译验证，代价是：在 6 项检查全部 PASS 的情况下，
代码有 15 处编译不过（详见 outputs/Step5编译打通与抽象层报告.md）。

为什么不用 gradle 直接验证
---------------------------
gradle 首次运行要下载 AGP、依赖、protobuf 产物，慢且依赖网络。但这里只想回答
一个问题 —— 类型是否自洽。javac + android.jar 就够，且能报精确行号。
gradle 的完整验证（:app:assembleDebug）仍然保留，两者互补。

classpath 的三个 jar
--------------------
  android.jar            Android 平台 API（API 36）
  annotation-1.5.0.jar   androidx.annotation —— 1.6.0+ 是纯 Kotlin 元数据
                         artifact，jar 里没有 Java 类，用了会报
                         "zip END header not found"
  protobuf-javalite      xiaomi.proto 生成物依赖的运行时

protobuf 生成物
---------------
`nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto` 不是源码，
是构建期从 `app/src/main/proto/xiaomi.proto` 生成的。Step5 抽象层的
`XiaomiSqCommand` / `XiaomiLiveSession` 引用它，所以本脚本会自己跑一次 protoc
生成到临时目录并加入编译单元。protoc 与 protobuf-javalite 从 gradle 缓存里找。

自检：javac 退出 0 时必须真的产出 .class
-----------------------------------------
Java 17 已移除 `-bootclasspath`。带着它跑 javac 会静默退出 0 且不产出任何 class，
看起来"编译通过"实际什么都没编译 —— 这个坑本脚本第一版就踩了。
"""

import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
BRIDGE = HERE.parent
WORK = BRIDGE.parent
TOOLCHAIN = WORK / "toolchain"
GRADLE_HOME = WORK / "gradle-home"

HUAWEI = BRIDGE / (
    "app/src/main/java/io/github/miam1ku/mibandoplusbridge/protocol/huawei"
)
JAVA_SRC = BRIDGE / "app/src/main/java"
ANDROID_JAR = TOOLCHAIN / "sdk/platforms/android-36/android.jar"
ANNOTATION_JAR = TOOLCHAIN / "annotation-1.5.0.jar"
# protobuf 完整版（不是 protobuf-javalite）。生成物 XiaomiProto 继承
# com.google.protobuf.GeneratedMessage —— 那是**完整版**的类，javalite 里没有
# （javalite 只有 GeneratedMessageLite）。所以要编译生成物，必须用 protobuf-java。
PROTOBUF_JAVA_JAR = TOOLCHAIN / "protobuf-java-4.32.0.jar"
PROTOBUF_URL = (
    "https://maven.aliyun.com/repository/public/com/google/protobuf/"
    "protobuf-java/4.32.0/protobuf-java-4.32.0.jar"
)
JDK = TOOLCHAIN / "jdk-17.0.20.1+1"
OUT = HERE / "_javac_out"

ANNOTATION_URL = (
    "https://dl.google.com/dl/android/maven2/androidx/annotation/"
    "annotation/1.5.0/annotation-1.5.0.jar"
)

# 华为包引用的、位于 protocol/huawei 之外的 bridge 源文件
EXTRA_SOURCES = [
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/data/HuaweiLogger.java",
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/data/SessionLog.java",
]

# Step 5 抽象层。与华为包互相调用（LiveSession 引 HuaweiSqCommand，
# HuaweiSqCommand 引 HuaweiPacket），必须一起进编译单元 ——
# 只编译 protocol/huawei 会漏掉它们对华为包的调用错误，反之亦然。
ABSTRACTION_SOURCES = [
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/protocol/SqCommand.java",
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/protocol/LiveSession.java",
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/protocol/XiaomiSqCommand.java",
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/protocol/XiaomiLiveSession.java",
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/protocol/HuaweiSqCommand.java",
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/protocol/HuaweiLiveSession.java",
]

# LiveCommandQueue 被 XiaomiLiveSession 引用；它自己又引用 BandNotificationCommand
#（classify() 里判断来电：BandNotificationCommand.isCall(post)），
# 所以那个文件也必须进编译单元 —— javac 不会自动去找未显式列出的同目录文件。
QUEUE_SOURCES = [
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/protocol/LiveCommandQueue.java",
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/protocol/BandNotificationCommand.java",
    JAVA_SRC / "io/github/miam1ku/mibandoplusbridge/protocol/NotificationIconPixels.java",
]


def fail(msg: str) -> None:
    print(f"[FATAL] {msg}")
    sys.exit(2)


def _find_one(pattern: str, roots: list[pathlib.Path]):
    for root in roots:
        if not root.is_dir():
            continue
        for hit in root.rglob(pattern):
            return hit
    return None


def ensure_protobuf_jar() -> None:
    """protobuf-java（完整版）。缺失时从阿里云 public 仓库拉。"""
    if PROTOBUF_JAVA_JAR.is_file() and PROTOBUF_JAVA_JAR.stat().st_size > 500_000:
        return
    PROTOBUF_JAVA_JAR.unlink(missing_ok=True)
    print("downloading protobuf-java 4.32.0 ...")
    subprocess.run(
        ["curl", "-sL", "-m", "300", "-o", str(PROTOBUF_JAVA_JAR), PROTOBUF_URL],
        capture_output=True,
    )
    if not PROTOBUF_JAVA_JAR.is_file()             or PROTOBUF_JAVA_JAR.stat().st_size < 500_000:
        PROTOBUF_JAVA_JAR.unlink(missing_ok=True)
        fail("protobuf-java 下载失败")


def ensure_annotation_jar() -> None:
    if ANNOTATION_JAR.is_file() and ANNOTATION_JAR.stat().st_size > 10_000:
        return
    ANNOTATION_JAR.unlink(missing_ok=True)
    print("downloading androidx.annotation:1.5.0 ...")
    subprocess.run(
        ["curl", "-sL", "-m", "180", "-o", str(ANNOTATION_JAR), ANNOTATION_URL],
        capture_output=True,
    )
    if not ANNOTATION_JAR.is_file() or ANNOTATION_JAR.stat().st_size < 10_000:
        ANNOTATION_JAR.unlink(missing_ok=True)
        fail("androidx.annotation 下载失败或不是有效 jar")


def generate_protobuf(into: pathlib.Path):
    """跑 protoc 生成 XiaomiProto。返回 (生成的文件列表, protobuf-javalite jar)。"""
    proto = BRIDGE / "app/src/main/proto/xiaomi.proto"
    if not proto.is_file():
        return [], None

    caches = [GRADLE_HOME / "caches", pathlib.Path.home() / ".gradle/caches"]
    protoc = _find_one("protoc-*-windows-x86_64.exe", caches)
    javalite = PROTOBUF_JAVA_JAR if PROTOBUF_JAVA_JAR.is_file() else None
    if protoc is None:
        print("note: 未找到 protoc，跳过 protobuf 生成"
              "（小米侧抽象类会报 package does not exist）")
        return [], javalite

    gen = into / "proto-gen"
    gen.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(
        [str(protoc), f"--proto_path={proto.parent}",
         f"--java_out={gen}", str(proto)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if r.returncode != 0:
        print("note: protoc 执行失败：", (r.stderr or "").strip()[:200])
        return [], javalite
    produced = sorted(gen.rglob("*.java"))
    print(f"protoc generated {len(produced)} file(s) from xiaomi.proto")
    return produced, javalite


def report(text: str) -> None:
    errors = [l for l in text.splitlines() if ": error:" in l]
    print(f"\n{len(errors)} error(s)\n")
    for line in errors:
        print("  ", line)
    by_file: dict[str, int] = {}
    for line in errors:
        m = re.match(r"^(.*?\.java):(\d+): error:", line)
        if m:
            by_file[m.group(1)] = by_file.get(m.group(1), 0) + 1
    if by_file:
        print("\n按文件汇总：")
        for f in sorted(by_file, key=lambda k: -by_file[k]):
            short = f.replace(str(BRIDGE).replace("\\", "/") + "/", "")
            print(f"  {by_file[f]:>3}  {short}")


def main() -> int:
    if not ANDROID_JAR.is_file():
        fail(f"缺少 android.jar: {ANDROID_JAR}")
    if not (JDK / "bin/javac.exe").is_file():
        fail(f"缺少 javac: {JDK}")
    ensure_annotation_jar()
    ensure_protobuf_jar()

    with tempfile.TemporaryDirectory() as td:
        proto_sources, javalite = generate_protobuf(pathlib.Path(td))

        if OUT.exists():
            shutil.rmtree(OUT)
        OUT.mkdir(parents=True)

        sources = (
            sorted(HUAWEI.rglob("*.java"))
            + [p for p in EXTRA_SOURCES + ABSTRACTION_SOURCES + QUEUE_SOURCES
               if p.is_file()]
            + proto_sources
        )
        cp_parts = [str(ANDROID_JAR), str(ANNOTATION_JAR)]
        if javalite:
            cp_parts.append(str(javalite))  # protobuf-java（完整版）

        print(f"\ncompiling {len(sources)} java files "
              f"(protocol/huawei + Step5 抽象层"
              f"{' + protobuf' if proto_sources else ''})")
        print("target: Java 17, -proc:none\n")

        cmd = [
            str(JDK / "bin/javac.exe"),
            # 强制英文诊断，否则 javac 输出中文，报告里的 ": error:" 匹配不到
            "-J-Duser.language=en",
            "-J-Duser.country=US",
            "-nowarn", "-proc:none",
            "-encoding", "UTF-8",
            "-source", "17", "-target", "17",
            "-classpath", ";".join(cp_parts),
            "-d", str(OUT),
            *[str(p) for p in sources],
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace")

    produced = len(list(OUT.rglob("*.class"))) if OUT.exists() else 0
    text = (proc.stderr or "") + (proc.stdout or "")

    if proc.returncode == 0 and produced == 0:
        print("[FATAL] javac 退出 0 但没有产出 .class —— 编译是空转，不算通过。")
        print("        检查是否误用了 -bootclasspath（Java 17 已移除）")
        return 2

    if proc.returncode != 0 or ": error:" in text:
        report(text)
        return 1

    nested = len([c for c in OUT.rglob("*.class") if "$" in c.name])
    print(f"[OK] javac 无错误，产出 {produced} 个 .class（含 {nested} 个嵌套类）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
