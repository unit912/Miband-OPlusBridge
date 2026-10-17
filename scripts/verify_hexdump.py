"""GB.hexdump 的行为向量测试。

为什么必须有这个测试
--------------------
`HuaweiCrypto.computeDigestHiChainLite` 把 `GB.hexdump(key)` 的输出直接
`getBytes()` 后送进 HKDF。也就是说 hexdump 的输出**就是密码学输入**。

我在 Step 2 手写 GB.hexdump 时写成了"空格分隔 + 小写 hex"，而 GB 原版是
"无分隔符 + 大写 hex"。这类偏差：

  - 语法合法、类型正确、编译通过；
  - 静态检查（符号闭合、引用存在、字段重复）全部 PASS；
  - 只有真正跑到 HiChain Step3/Step4 时手表才会拒绝。

所以它必须有一组独立的、与实现无关的期望值向量。

期望值直接取自 GB 源码 semantics（`GB.HEX_CHARS = "0123456789ABCDEF"`，
`hexdump(buffer, offset, length)` 与 `hexdump(buffer)`），而不是
"跑一遍我的实现看输出什么" —— 后者只会固化已有的错误。
"""

import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
BRIDGE = HERE.parent
TOOLCHAIN = BRIDGE.parent / "toolchain"
GB_JAVA = BRIDGE / (
    "app/src/main/java/io/github/miam1ku/mibandoplusbridge/protocol/huawei/core/GB.java"
)
JDK = TOOLCHAIN / "jdk-17.0.20.1+1"

def compile_and_run(td: pathlib.Path) -> tuple[bool, str, str]:
    gen = td / "GbVector.java"
    gen.write_text(HARNESS, encoding="utf-8")
    cp = GB_JAVA.parent
    out = td / "out"
    out.mkdir(exist_ok=True)
    c = subprocess.run(
        [str(JDK / "bin/javac.exe"), "-J-Duser.language=en", "-proc:none", "-encoding", "UTF-8",
         "-d", str(out), str(gen), str(GB_JAVA)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if c.returncode != 0:
        return False, "", c.stderr.strip()[:600]
    r = subprocess.run(
        [str(JDK / "bin/java.exe"), "-cp", str(out),
         "io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.GbVector"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return r.returncode == 0, r.stdout.strip(), r.stderr.strip()[:600]


HARNESS = """
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.core;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class GbVector {
    static int fail = 0;

    static void check(String label, byte[] data, int off, int len, String want) {
        String got = GB.hexdump(data, off, len);
        if (!want.equals(got)) {
            System.out.println("FAIL " + label
                + "\\n  want: " + want + "\\n  got : " + got);
            fail++;
        } else {
            System.out.println("ok   " + label + "  -> " + got);
        }
    }

    static void checkOne(String label, byte[] data, String want) {
        String got = GB.hexdump(data);
        if (!want.equals(got)) {
            System.out.println("FAIL " + label
                + "\\n  want: " + want + "\\n  got : " + got);
            fail++;
        } else {
            System.out.println("ok   " + label + "  -> " + got);
        }
    }

    public static void main(String[] args) {
        check("全零 4 字节", new byte[]{0,0,0,0}, 0, 4, "00000000");
        check("单字节 0x0f", new byte[]{0x0F}, 0, 1, "0F");
        check("单字节 0xff", new byte[]{(byte)0xFF}, 0, 1, "FF");
        check("多字节大写", new byte[]{(byte)0xDE,(byte)0xAD,(byte)0xBE,(byte)0xEF},
              0, 4, "DEADBEEF");
        check("含 0x0a/0x0b/0x0c", new byte[]{0x0A,0x0B,0x0C}, 0, 3, "0A0B0C");
        check("offset 跳过前缀", new byte[]{(byte)0xAA,(byte)0xBB,(byte)0xCC,(byte)0xDD},
              2, 2, "CCDD");
        check("length=-1 到末尾", new byte[]{0x11,0x22,0x33,0x44}, 1, -1, "223344");
        check("HiChain 典型 16 字节",
              new byte[]{0x01,0x23,0x45,0x67,(byte)0x89,(byte)0xAB,(byte)0xCD,(byte)0xEF,
                         (byte)0xFE,(byte)0xDC,(byte)0xBA,(byte)0x98,0x76,0x54,0x32,0x10},
              0, -1, "0123456789ABCDEFFEDCBA9876543210");

        checkOne("单参全量", new byte[]{0x01,0x02,(byte)0xAB}, "0102AB");
        checkOne("单参空数组", new byte[0], "");
        checkOne("单参 null", null, "(null)");

        if (fail > 0) {
            System.out.println(fail + " case(s) FAILED");
            System.exit(1);
        }
        System.out.println("all hexdump vectors passed");
    }
}
"""


def main() -> int:
    if not GB_JAVA.is_file():
        print(f"[FATAL] 找不到 {GB_JAVA}")
        return 2
    with tempfile.TemporaryDirectory() as td:
        ok, out, err = compile_and_run(pathlib.Path(td))
    print(out)
    if err:
        print(err, file=sys.stderr)
    if not ok:
        print("\nhexdump 向量测试失败")
        return 1
    print("\n[OK] GB.hexdump 行为与 GB 原版一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
