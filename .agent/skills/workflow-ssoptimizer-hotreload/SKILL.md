---
name: workflow-ssoptimizer-hotreload
description: SSOptimizer 游戏内热重载工作流（sso-debug）。需要不重启游戏替换已加载类的方法体（L2 hotswap）、或重编译调试脚本（L1）时加载。覆盖 L1/L2 两级机制的选择、hotswap RPC 用法、HotSpot 重定义限制清单与失败判读。
---

# 游戏内热重载工作流（sso-debug）

两级热重载能力，按场景选择：

| 级别 | 机制 | 适用 | 限制 |
| --- | --- | --- | --- |
| L1 脚本重载 | 同名 scriptId 重编译 → 新类加载器替换实例 | 调试脚本/探针的行为迭代 | 只影响脚本自身，不动已加载类 |
| L2 方法体 hotswap | 自 attach + `Instrumentation.redefineClasses` | 已加载的游戏/模组类的方法体替换 | 仅方法体；不可增删成员/改签名；已建实例字段布局不变 |

## L1：脚本重载

见 workflow-ssoptimizer-script-debug——重编译同 scriptId 即可，`version` 递增，
旧实例无持久状态迁移（脚本应保持无状态或自行容忍状态丢失）。

## L2：hotswap RPC

### 前置条件

- 调试服务启用（`-Dssoptimizer.debug.enabled=true`）；
- **必须**携带 `-Djdk.attach.allowAttachSelf=true`（JDK 25 在启动期读取该属性，
  运行期 setProperty 无效——已实测）。缺失时 hotswap RPC 返回明确错误；
- 目标类**必须已被加载**（hotswap 是重定义，不是首次加载；未加载返回
  `target class not loaded`）。

smoke 通道完整示例：

```bash
JAVA_TOOL_OPTIONS="-Dssoptimizer.debug.enabled=true -Djdk.attach.allowAttachSelf=true" \
  bash tools/smoke_test_game_launch.sh <gameDir> 300 game
```

### 调用

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"hotswap","id":1,"params":{
        "className":"com.example.Target","source":"<完整 Java 源码>"}}' \
  http://127.0.0.1:8471/rpc
```

- `source` 是与已加载类**同包同名**的完整源码（经 javax.tools 编译，classpath
  含游戏 named jar 与全部模组，可引用游戏类）；
- 成功返回 `{"className":..., "redefined":true}`；新行为对后续调用立即生效
  （包括已在运行的调用栈的后续进入）。

### 限制清单（HotSpot 重定义语义）

- 只能改方法体。新增/删除字段或方法、改方法签名、改类继承结构 →
  redefine 失败（`ClassFormatError`/`UnsupportedOperationException`，RPC 返回
  -32603 并附说明）；
- 源码中的内部类/辅助类**不会**被重定义（仅重定义 className 指定的主类）；
- 已建实例的字段值与布局不变；静态初始化块不会重跑；
- 对「正在高频执行的目标方法」重定义是安全的（JVM 在安全点完成切换），
  但被 SSOptimizer Mixin/ASM 改写过的方法，hotswap 会丢掉改写层——
  **不要 hotswap 已被织入的游戏热路径方法**（渲染/战斗循环），除非明确知道
  该方法无织入。

### 失败判读

| RPC 错误 message 关键词 | 含义 | 处置 |
| --- | --- | --- |
| `requires -Djdk.attach.allowAttachSelf=true` | 启动未带自 attach 属性 | 重启游戏带属性 |
| `target class not loaded` | 类尚未加载 | 先触发该类被加载（进入相关界面/战斗）再 hotswap |
| `compilation failed` | 源码编译错误 | 按诊断信息修源码（行号对应 source 文本） |
| `redefine failed ... method-body only` | 违反结构限制 | 只改方法体；结构性改动必须走 Mixin + 重启 |

### 验证探针

游戏内自带 `github.kasuminova.ssoptimizer.common.debug.HotswapProbe.marker()`
（返回 `"original"`），无副作用，可随时 hotswap 验证链路：
重定义其方法体返回其他字符串，再用调试脚本调用 marker() 确认新值生效。
