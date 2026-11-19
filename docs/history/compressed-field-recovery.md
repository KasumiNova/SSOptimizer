# 旧版战报字段的离线恢复

部分早期 SSOptimizer 版本将 Detailed Combat Results 战报的 UTF-8 XML 写成标准 Zstandard 帧；该模组自身的读取器使用 `Inflater`，读取 RFC 1950 zlib。两种压缩结果都通过同一字母表写成无填充、无换行的字符串。卸载旧优化后，原读取器可能报告战报损坏。请先保留整个存档的原始备份，再在另一份副本上操作，避免后续保存覆盖仍可恢复的字段。

## 从字段副本恢复

1. 在存档副本中，用能够读取该存档容器的工具，手动提取持久数据 `CombatAnalytics_CombatResults_V4` 对应的**字符串值**。将该值单独写入 `field.txt`，保留原始字符；文件不得包含字段名、引号、转义表示、BOM、空格缩进或末尾换行。不要把整个战役文件传给转换器。
2. 在项目根目录运行以下命令。需要带 `compression.zstd` 的 Python 3.14 或更高版本，输出目录所在的文件系统须支持硬链接。以下为 POSIX shell 语法：

   ```sh
   alphabet=$(python3 -c 'print("".join(map(chr, range(64, 127))) + "?")')
   python3 tools/transcode_frame.py --alphabet "$alphabet" field.txt recovered.txt
   ```

   Windows PowerShell 使用：

   ```powershell
   $alphabet = -join ((64..126 + 63) | ForEach-Object { [char]$_ })
   py -3.14 tools/transcode_frame.py --alphabet $alphabet field.txt recovered.txt
   ```

3. 成功后，`recovered.txt` 是同一字母表表示的单个 zlib 字段。工具已解压输出并逐字节核对原文，会打印原文 SHA-256。只在另一份测试存档中用该值替换所提取的字段，通过原版读取器打开并确认历史战报数量和内容；保留原始备份。丢失或已覆盖的字段需要从更早的备份取回。

## 输入边界

[离线转换器](../../tools/transcode_frame.py) 不读取游戏安装、用户目录或模组清单，不按路径、扩展名、键名或压缩魔数搜索存档。文本模式的字母表始终由调用者提供。上述历史编码按每组 6 位从高到低排列，值 0 至 62 映射到 ASCII 64 至 126，值 63 映射到 `?`。它与 RFC 4648 的默认字母表不同。

对于用户已明确提取、且目标读取器确实需要 zlib 的原始单帧，可执行 `python3 tools/transcode_frame.py --raw frame.zst result.zlib`。标准帧头 `28 B5 2F FD` 只能识别压缩算法；其他用途的 Zstandard 文件应保留原格式。工具拒绝已有 zlib、串接的帧、尾随字节、不完整帧、需要外部字典的数据、无效文本编码和超限数据。输入（含文本外层）、解压后数据和 Zstandard 窗口各限 64 MiB。输出路径必须尚不存在；文件、硬链接和符号链接一律不覆盖。生成的 zlib 流使用 9 级压缩，保留解压后的字节；不同压缩库版本的压缩字节可能不同。
