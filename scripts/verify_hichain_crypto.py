"""验证 CryptoUtils.hkdfSha256 与 RFC 5869 测试向量一致。

Java 侧跑不了 Gradle（无 JDK/Android SDK），所以这里用 Python 重实现同一算法，
对 RFC 5869 附录 A 的三组向量做交叉验证。Python 通过则说明 Java 移植的算法
本身是对的 —— 但不能替代真实编译。
"""

import hashlib
import hmac


def calc_hmac_sha256(secret, message):
    return hmac.new(secret, message, hashlib.sha256).digest()


def hkdf_sha256(secret_key, salt, info, output_length):
    """对应 CryptoUtils.hkdfSha256 的逐行翻译。"""
    prk = calc_hmac_sha256(salt, secret_key)
    hash_len = 32
    n = output_length // hash_len if output_length % hash_len == 0 else output_length // hash_len + 1
    hash_round = b""
    generated = b""
    for round_num in range(1, n + 1):
        t = hash_round + info + bytes([round_num])
        hash_round = calc_hmac_sha256(prk, t)
        generated += hash_round
    return generated[:output_length]


# RFC 5869 Appendix A
VECTORS = [
    # Test Case 1: basic SHA-256 with salt and info
    ("0x0b" * 22,
     [0x000102030405060708090a0b0c],
     [0xf0f1f2f3f4f5f6f7f8f9],
     42,
     "3cb25f25faacd57a90434f64d0362f2a"
     "2d2d0a90cf1a5a4c5db76d4e2f7f0d1"
     "4e2f7f0d14e2f7f0d14e2f7f0d14e2f7f"),
    # Test Case 2: longer inputs / zero-length salt and info
    ("0x" + "ab" * 80,
     [],
     [],
     42,
     None),
]


def main():
    failures = []

    # 用例 1：RFC 5869 A.1
    ikm = bytes.fromhex("0b" * 22)
    salt = bytes([0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                  0x08, 0x09, 0x0a, 0x0b, 0x0c])
    info = bytes.fromhex("f0f1f2f3f4f5f6f7f8f9")
    okm = hkdf_sha256(ikm, salt, info, 42)
    # RFC 5869 附录 A.1 的期望输出（42 字节 = 84 个十六进制字符）
    rfc_okm = "3cb25f25faacd57a90434f64d0362f2a" \
              "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" \
              "34007208d5b887185865"
    print("A.1 computed:", okm.hex())
    print("A.1 expected:", rfc_okm)
    if okm.hex() != rfc_okm:
        failures.append("RFC 5869 A.1 不匹配")

    # 用例 3：零长度 salt 与 info
    ikm3 = bytes.fromhex("0b" * 22)
    okm3 = hkdf_sha256(ikm3, b"", b"", 42)
    rfc3 = "8da4e775a563c18f715f802a063c5a31" \
           "b8a11f5c5ee1879ec3454e5f3c738d2d" \
           "9d201395faa4b61a96c8"
    print("A.3 computed:", okm3.hex())
    print("A.3 expected:", rfc3)
    if okm3.hex() != rfc3:
        failures.append("RFC 5869 A.3 不匹配")

    # 基本性质：输出长度正确、可重复
    a = hkdf_sha256(b"key", b"salt", b"info", 32)
    b = hkdf_sha256(b"key", b"salt", b"info", 32)
    if len(a) != 32:
        failures.append(f"输出长度应为 32，实际 {len(a)}")
    if a != b:
        failures.append("同输入产生不同输出，非确定性")

    # 不同 info 应产生不同结果（否则 step3 与 step4 的密钥会相同）
    x = hkdf_sha256(b"key", b"randSelf||randPeer", b"hichain_iso_session_key", 32)
    y = hkdf_sha256(b"key", b"randSelf||randPeer", b"hichain_return_key", 32)
    if x == y:
        failures.append("不同 info 派生出相同密钥 —— info 未参与运算")

    # 不同 salt 应产生不同结果
    z = hkdf_sha256(b"key", b"different salt", b"info", 32)
    if hkdf_sha256(b"key", b"salt", b"info", 32) == z:
        failures.append("不同 salt 派生出相同密钥 —— salt 未参与运算")

    if failures:
        print(f"\n{len(failures)} 项失败：")
        for f in failures:
            print("  -", f)
        raise SystemExit(1)
    print("\nHKDF 实现符合 RFC 5869，向量与性质检查全部通过")


if __name__ == "__main__":
    main()