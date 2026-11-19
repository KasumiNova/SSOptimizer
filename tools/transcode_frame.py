#!/usr/bin/env python3
"""将一个明确选取的 Zstandard 帧转为 zlib；需要 Python 3.14+。"""

import argparse
import base64
import hashlib
import os
from pathlib import Path
import stat
import sys
import tempfile
import zlib

try:
    from compression import zstd
except ImportError:
    sys.exit("需要带 compression.zstd 的 Python 3.14 或更高版本")


STANDARD = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
LIMIT = 64 * 1024 * 1024


def alphabet_bytes(value):
    alphabet = value.encode("ascii")
    if len(alphabet) != 64 or len(set(alphabet)) != 64 or not all(33 <= c <= 126 for c in alphabet):
        raise ValueError("字母表必须包含 64 个互异的可见 ASCII 字符")
    return alphabet


def encode_field(data, alphabet):
    return base64.b64encode(data).rstrip(b"=").translate(bytes.maketrans(STANDARD, alphabet))


def decode_field(text, alphabet):
    if not text or set(text).difference(alphabet):
        raise ValueError("必须提供单个无填充、无换行字段，所有字符必须属于指定字母表")
    encoded = text.translate(bytes.maketrans(alphabet, STANDARD))
    data = base64.b64decode(encoded + b"=" * (-len(encoded) % 4), validate=True)
    if encode_field(data, alphabet) != text:
        raise ValueError("字段尾部含非零填充位")
    return data


def transcode(frame, limit=LIMIT):
    if not frame.startswith(b"\x28\xb5\x2f\xfd"):
        raise ValueError("输入必须是一个标准 Zstandard 帧")
    info = zstd.get_frame_info(frame)
    if info.dictionary_id or (info.decompressed_size is not None and info.decompressed_size > limit):
        raise ValueError("帧声明了字典或解压大小超过限制")
    decoder = zstd.ZstdDecompressor(options={zstd.DecompressionParameter.window_log_max: 26})
    raw = decoder.decompress(frame, max_length=limit + 1)
    if len(raw) > limit or not decoder.eof or decoder.unused_data:
        raise ValueError("帧不完整、带有尾随数据，或解压大小超过限制")
    result = zlib.compress(raw, level=9)
    if zlib.decompress(result) != raw:
        raise ValueError("输出 zlib 解压校验失败")
    return result, hashlib.sha256(raw).hexdigest()


def read_selected(path):
    if path == "-":
        data = sys.stdin.buffer.read(LIMIT + 1)
    else:
        flags = os.O_RDONLY | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_BINARY", 0)
        with os.fdopen(os.open(path, flags), "rb") as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise ValueError("输入必须是普通文件，或显式指定 - 读取标准输入")
            data = stream.read(LIMIT + 1)
    if len(data) > LIMIT:
        raise ValueError("输入超过 64 MiB")
    return data


def main():
    parser = argparse.ArgumentParser(description="将一个明确选取的 Zstandard 帧转为 zlib。请先确认目标读取器要求 zlib。")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--raw", action="store_true", help="读取和输出原始压缩字节")
    mode.add_argument("--alphabet", metavar="CHARS", help="输入和输出均使用指定字母表的无填充 Base64")
    parser.add_argument("input", help="单个帧或已提取的字段；- 表示标准输入")
    parser.add_argument("output", type=Path, help="必须是全新的文件路径")
    args = parser.parse_args()
    try:
        if os.path.lexists(args.output):
            raise FileExistsError(f"输出已存在：{args.output}")
        alphabet = alphabet_bytes(args.alphabet) if args.alphabet is not None else None
        data = read_selected(args.input)
        frame = decode_field(data, alphabet) if alphabet else data
        result, digest = transcode(frame)
        output = encode_field(result, alphabet) if alphabet else result
        with tempfile.NamedTemporaryFile(dir=args.output.parent, prefix=".transcode-", delete_on_close=False) as tmp:
            tmp.write(output)
            tmp.close()
            # 硬链接在目标已存在时会失败，避免检查后覆盖其他进程刚创建的文件。
            os.link(tmp.name, args.output)
        print(f"已写入 {args.output}；解压内容 SHA-256：{digest}")
    except (OSError, ValueError, zstd.ZstdError, zlib.error) as exc:
        parser.exit(1, f"转换失败：{exc}\n")


if __name__ == "__main__":
    main()
