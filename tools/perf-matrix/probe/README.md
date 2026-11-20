# GraphicsLib 战斗帧探针

在 Linux 主机用 JDK 25 执行 `bash build.sh <Windows英文RC8的starsector-core> <隔离构建目录>`。每组使用独立的游戏安装副本，把生成的 `sso_perf_probe` 模组放入各自的 `mods/`，并在游戏启动器中启用。独立版通过 `--game` 选择该组游戏目录，这决定实际使用的模组、设置和存档位置。三组使用同一个 JAR、同一 GraphicsLib 版本、相同的舰队内容模组及顺序。游戏 JAR 和任务资源来自已有游戏安装，不随探针分发。运行期间只启用本探针作为任务驱动，保持战斗场景输入空闲。

每次启动前执行 `export SSO_PERF_RUN=$(python3 link_output.py <本轮游戏存档目录>/common <本轮外部输出目录>)`。正式 runner 应先单独检查 Python 命令的退出状态，再导出 token。未设置 `SSO_PERF_RUN` 时探针停用。游戏必须持有窗口焦点。原版启动器的进入游戏动作由各平台 runner 执行；进入标题后探针通过游戏原有 `missionAccepted` 入口打开 `gl_benchmark`，由已注册的 GraphicsLib 脚本创建战斗。探针用游戏 API 保存文件；每轮输出链接到本轮 token 对应的 `.data` 文件。归档时复制链接指向的文件内容。

Windows RC8 的 `TitleScreenState.render` 在偏移 452 调用战斗地图的 UI 插件回调；首次回调且标题面板存在时输出 `TITLE_READY`。`CombatState.traverse` 在偏移 4260 每次正常战斗渲染调用同一 UI 插件列表一次；`com.fs.starfarer.title.Object.L$Oo` 对每个已注册插件调用一次 `renderInUICoords`。程序记录该回调的单调时间间隔，反映游戏渲染循环的帧率，GPU 完成时间及桌面合成器实际呈现时间须另行测量。

原版任务部署后，探针确认双方有存活的非战机舰船、模拟时钟已推进、窗口激活且未暂停，随后预热 20 秒，再采样 40 秒。采样阶段失去焦点、暂停、打开对话框、战斗结束或离开固定 DP 时间范围会导致失败。启动器、没有回调的游戏卡死及整个进程树由主机 runner 设置超时并清理。

| 输出 | 内容 |
|---|---|
| `events.tsv` | 无表头；事件、JVM 纳秒时钟、Unix 毫秒时间。事件依次为 `TITLE_READY`、`WARMUP_BEGIN`、`SAMPLE_BEGIN`、`SAMPLE_END`、`DONE`。 |
| `frames.csv` | `frame,end_ns,dt_ns`，帧号从 1 开始，首帧起点等于 `SAMPLE_BEGIN`，末帧终点等于 `SAMPLE_END`。采样期间仅记录整数，结束后写文件。 |
| `metadata.tsv` | 游戏版本、Java 版本、实际 GL renderer/vendor、分辨率、游戏 API 返回的启用模组顺序及各自版本。 |
| `roster.tsv` | 双方初始完整舰队及顺序、首次实际部署的非战机舰型及 DP。必须比较实际舰队记录；同一 mission ID 不保证不同组合产生相同阵容。 |
| `failure.txt` | 探针失败时的异常，另记 `FAILED` 并以 3 退出。成功以 0 退出。 |

`SAMPLE_BEGIN` 的两个时钟值在采样开始时保存在内存，随 `SAMPLE_END` 一起写出，以免磁盘写入影响首个计分帧。外部 CPU 采样必须覆盖整个游戏进程生命周期，按事件自带的 Unix 毫秒值截取窗口。Windows JVM 在 Wine 中的 `System.nanoTime()` 与主机单调时钟可能采用不同起点。GraphicsLib 在两分钟后才开始动态调整 DP；探针在进入任务后 110 秒仍未完成时报告失败。

游戏退出后，校验 `DONE` 并执行 `python3 link_output.py --collect <本轮外部输出目录>`。超过游戏单文件大小限制的帧记录按完整行分块；此命令检查分块齐全，再生成完整的 `frames.csv`。单块文件继续使用原链接。
