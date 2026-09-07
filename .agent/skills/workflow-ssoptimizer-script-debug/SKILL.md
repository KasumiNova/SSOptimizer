---
name: workflow-ssoptimizer-script-debug
description: SSOptimizer 游戏内调试脚本工作流（sso-debug HTTP RPC）。需要在运行中的游戏内动态编译执行 Java 脚本、读写游戏状态、做秒级调试迭代时加载。覆盖启用方式、token 鉴权、script_compile/script_invoke/script_list/debug_stats 端点、threadMode 选择准则与常见配方。
---

# 游戏内调试脚本工作流（sso-debug）

SSOptimizer 内建调试服务：游戏进程内的 localhost HTTP JSON-RPC 端点，支持把 Java 源码
动态编译进游戏 JVM 并执行。迭代周期从「改代码+重启游戏（分钟级）」降到「改脚本+curl（秒级）」。

## 启用

调试服务默认关闭。启动游戏时加 JVM 属性（smoke 通道示例）：

```bash
JAVA_TOOL_OPTIONS="-Dssoptimizer.debug.enabled=true" bash tools/smoke_test_game_launch.sh <gameDir> 300 game
```

启动后服务监听 `127.0.0.1:8471`（`-Dssoptimizer.debug.port` 可改，冲突自动递增 8471–8480）。
鉴权 token 写在 `<gameDir>/ssoptimizer-debug-output/debug-token`（0600），请求头携带：

```bash
TOKEN=$(cat /mnt/store/Games/Starsector098-linux/ssoptimizer-debug-output/debug-token)
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"ping","id":1}' http://127.0.0.1:8471/rpc
```

## RPC 端点

| 方法 | params | 返回 |
| --- | --- | --- |
| `ping` / `version` | - | 存活性 / 版本信息 |
| `debug_stats` | - | `{drainCount, scripts}` — drainCount 持续增长说明游戏主循环在跑 |
| `script_compile` | `scriptId, className, source` | `{scriptId, className, version, compiledAt}` |
| `script_invoke` | `scriptId, args?, threadMode?` | 脚本返回值（JSON） |
| `script_list` | - | 已注册脚本句柄列表 |
| `hotswap` | `className, source` | L2 方法体热替换（见 workflow-ssoptimizer-hotreload） |

## 脚本契约

- `className` 是源码中主类全限定名（公开类，决定源文件名，必须与实际类名一致）；
- 主类必须 `implements github.kasuminova.ssoptimizer.api.debug.DebugScript` 且有公开无参构造；
- 入口：`Object invoke(DebugContext ctx, Map<String,Object> args)`；
- 返回值必须 JSON 可序列化（字符串/数值/Map/List），否则返回 -32603；
- `args` 来自 JSON 对象，数值统一为 Double；
- `ctx.gameClassLoader()` 加载游戏/模组类（运行时为 named 命名，与 named jar 一致）；
  `ctx.log(msg)` 写游戏日志；`ctx.service(Class)` 解析 ServiceRegistry 跨域服务（未注册返回 null）。

## threadMode 选择准则

- **默认 `new`**（省略 threadMode）：脚本在专用虚拟线程执行，不阻塞游戏主循环。
  适合只读采样、日志 dump、遥测分析等无副作用场景。
- **`main`**：投递到游戏主线程下一帧执行，与游戏逻辑串行。**读写游戏业务状态
  （实体、战役数据、UI 组件）必须用 main**，否则有竞态。
- main 模式 10s 未排空会快速失败（`did not drain`），说明主循环未运行
  （加载期/窗口空转暂停）。发 main 任务前可用 `debug_stats` 的 drainCount 确认主循环存活。
  注意：窗口非聚焦空转时主循环仍在跑（drain 锚点在空转分支内），但帧率低。

## 热重载（L1）

同名 `scriptId` 再次 `script_compile` 即替换实例（新类加载器，旧实例随引用解除被 GC），
`version` 递增。改脚本行为 = 重编译同 scriptId。

## 常见配方

**读游戏状态（标题界面）：**

```java
public class GameStateProbe implements github.kasuminova.ssoptimizer.api.debug.DebugScript {
    @Override
    public Object invoke(github.kasuminova.ssoptimizer.api.debug.DebugContext ctx,
                         java.util.Map<String, Object> args) {
        com.fs.starfarer.api.SettingsAPI s = com.fs.starfarer.api.Global.getSettings();
        return java.util.Map.of("screen", (int) s.getScreenWidth() + "x" + (int) s.getScreenHeight(),
                "sectorLoaded", com.fs.starfarer.api.Global.getSector() != null,
                "thread", Thread.currentThread().getName());
    }
}
```

**战斗内状态（main 模式）**：`Global.getCombatEngine()` 取引擎实例，遍历 ship list 统计。
战斗外 getCombatEngine() 为 null，脚本须判空。

## 约束

- 服务只绑回环地址 + token 鉴权，但脚本可执行任意代码——仅限本地调试，勿在生产分发开启；
- 脚本内禁止自行起线程操作 GL；脚本引擎不做沙箱；
- Kotlin 脚本为后续能力（编译器 jar 按需加载），初版仅 Java。
