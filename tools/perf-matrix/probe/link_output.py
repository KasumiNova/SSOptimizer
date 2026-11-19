"""将独立轮次目录关联至游戏 common API 写入的文件。"""
import argparse
from pathlib import Path
import uuid
from tempfile import NamedTemporaryFile
import shutil


def collect(output):
    view = output / "frames.csv"
    if not view.is_symlink():
        if view.is_file():
            return
        raise ValueError(f"帧数据链接缺失：{view}")
    first = view.resolve(strict=True)
    if not first.name.endswith("_frames.data"):
        raise ValueError(f"帧文件名称不匹配：{first}")
    prefix = first.name.removesuffix("frames.data")
    count = int(first.with_name(prefix + "parts.data").read_text(encoding="utf-8"))
    if not 1 <= count <= 4:
        raise ValueError(f"帧数据分块数异常：{count}")
    if count == 1:
        return
    sources = [first] + [first.with_name(f"{prefix}frames_{i}.data") for i in range(1, count)]
    if any(not path.is_file() for path in sources):
        raise ValueError("帧数据分块缺失")
    with NamedTemporaryFile(dir=output, delete=False) as destination:
        for path in sources:
            with path.open("rb") as source:
                shutil.copyfileobj(source, destination)
    Path(destination.name).replace(view)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--collect", type=Path)
    parser.add_argument("common", type=Path, nargs="?")
    parser.add_argument("output", type=Path, nargs="?")
    args = parser.parse_args()
    if args.collect is not None:
        if args.common is not None or args.output is not None:
            parser.error("--collect 只接受输出目录")
        collect(args.collect)
        return
    if args.common is None or args.output is None:
        parser.error("请指定游戏 common 目录及本轮输出目录")
    common = args.common.resolve()
    output = args.output.resolve()
    common.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex
    names = {"events.tsv": "events", "frames.csv": "frames", "metadata.tsv": "metadata",
             "roster.tsv": "roster", "failure.txt": "failure"}
    if any((output / name).exists() or (output / name).is_symlink() for name in names):
        parser.error("输出目录已关联其他轮次")
    for name, key in names.items():
        (output / name).symlink_to(common / ("sso_perf_" + token + "_" + key + ".data"))
    print(token)


if __name__ == "__main__":
    main()
