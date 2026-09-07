---
name: spec-game-testing
description: SSOptimizer 游戏功能验收规范。禁止用系统工具（grim / ydotool / hyprctl / wtype / xdotool 等）对游戏做实机驱动测试；功能验收只允许走项目内建测试通道（单元测试、smoke_test_game_launch.sh、automation 模式）。需要验证游戏内行为时加载本 skill。
---

# 游戏功能验收规范

## 硬性禁令

**禁止使用系统级工具对运行中的游戏做实机测试**，包括但不限于：

- 截图：`grim`、`hyprctl clients`、`slurp`、scrot、import 等；
- 输入注入：`ydotool`、`wtype`、`xdotool`、python-xlib 直发事件等；
- 窗口操控：hyprctl dispatch、wmctrl 等。

这些方式脆弱（依赖窗口布局/缩放/焦点）、不可重复、结论无法沉淀进仓库。

## 允许的验收通道（内建测试代码）

1. **单元/集成测试**：`./gradlew :app:test`。字节码注入类功能必须做到「实际定义并执行注入后的类」级别的功能验证，不做纯字节码形态 contain 断言。
2. **启动冒烟**：`tools/smoke_test_game_launch.sh <gameDir> [timeout] [game|launcher|automation]`，
   autostart 直入真实游戏，自动判定 ClassFormatError/VerifyError/NoClassDefFoundError 等致命标记。
3. **游戏内自动化**：smoke 脚本的 `automation` 模式（`-Dssoptimizer.automation.*` +
   scenario + telemetry 校验脚本），覆盖需要进入战斗/场景的验收。

## 覆盖不到的场景怎么办

**扩展内建测试代码，而不是退回系统工具**。例如需要「载入存档后打开舰队面板」的验收：
给 automation 框架加新 scenario，或在 smoke 脚本里加新的日志标记判定。
临时目测验收由用户本人进行，不由 Agent 代劳。
