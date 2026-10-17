"""华为协议栈移植的全量回归。

按「便宜到贵」的顺序跑七项检查，任一失败即退出：

1. javac_check.py           —— 真实编译（javac + android.jar API 36）
2. verify_hexdump.py        —— GB.hexdump 行为向量（密码学输入，必须逐字节一致）
3. verify_hichain_crypto.py —— HiChain 的 HKDF 是否符合 RFC 5869 向量
4. verify_step3.py          —— 语法平衡 / GB 符号残留 / import 闭合 / 重复字段
5. verify_packet_factory.py —— parse() 里的嵌套包引用是否都存在
6. verify_toolcalls.py      —— GB/StringUtils 工具类调用是否可解析
7. verify_methods.py        —— 实例方法调用（含继承链）与嵌套类引用是否闭合

为什么 javac 排在第一
--------------------
前六项里有五项是"结构检查"—— 它们能抓改签名、删方法、错包引用、
遗留 GB 依赖、密码学实现偏移，但**抓不到类型错误**。

真实案例：Step 2 我手写的 `GB.hexdump` 用了"空格分隔 + 小写"，而 GB 原版是
"无分隔符 + 大写"。这个偏差：
  - 语法合法、类型正确、编译通过；
  - 六项结构检查全部 PASS；
  - 只有真正跑到 HiChain Step3/Step4 时手表才会拒绝。
真实案例：`HuaweiConstants` 里 `BASE_UUID` 被写在类体外（`private static final`
出现在 class 声明之前）。这是 javac 的第一条错误，全部静态检查都看不见。

工具链
------
本机原本无 JDK 无 Android SDK。现已装在 `work/toolchain/`（便携版，
不改系统环境）：

    toolchain/jdk-17.0.20.1+1/          Temurin JDK 17（gh-proxy 镜像）
    toolchain/sdk/platforms/android-36/  platform 36
    toolchain/sdk/build-tools/35.0.0/    build-tools 35
    toolchain/annotation-1.5.0.jar       androidx.annotation（最后一个含 Java 类的版本）

仍然不做的事：`javac_check.py` 只编译 `protocol/huawei` 包，不编译 hook/notify/
service 层，也不做 dex/AAPT 打包。要验证整包能打出 APK，仍需执行
`./gradlew :app:assembleDebug`。
"""

import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
PY = sys.executable

CHECKS = [
    ("javac 真实编译", "javac_check.py"),
    ("GB.hexdump 行为向量", "verify_hexdump.py"),
    ("HiChain HKDF 向量", "verify_hichain_crypto.py"),
    ("语法与依赖闭合", "verify_step3.py"),
    ("包工厂嵌套引用", "verify_packet_factory.py"),
    ("工具类调用点", "verify_toolcalls.py"),
    ("实例方法与嵌套类", "verify_methods.py"),
]

failures = []
for label, script in CHECKS:
    result = subprocess.run(
        [PY, str(HERE / script)],
        capture_output=True, text=True, cwd=str(HERE),
        encoding="utf-8", errors="replace",
    )
    ok = result.returncode == 0
    print(f"[{'PASS' if ok else 'FAIL'}] {label} ({script})")
    if not ok:
        failures.append((label, (result.stdout or "") + (result.stderr or "")))

if failures:
    print("\n" + "=" * 60)
    for label, output in failures:
        print(f"\n--- {label} ---\n{output}")
    sys.exit(1)

print("\n" + "=" * 60)
print("全部通过：华为包 javac 编译 0 error，六项结构检查与两组密码学向量均 PASS")
print("未覆盖：hook/notify/service 层未编译，整包 APK 未验证"
      "（需 ./gradlew :app:assembleDebug）")
