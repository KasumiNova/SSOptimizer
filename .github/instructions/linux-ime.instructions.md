---
description: "Linux IME 开发：X11/XIM 协议、LWJGL 2 输入法集成、ASM Hook"
applyTo: "{app/src/**/ime/**/*.java,native/src/**/*ime*.cpp}"
---

# Linux IME 开发指南

## 架构概要

SSOptimizer 通过 ASM 字节码注入接管 LWJGL 2 的 X11 事件处理流程，自建 XIM/XIC 实现中文输入法支持。

### 核心问题

LWJGL 2 的 `LinuxDisplay` 虽然会创建 XIM/XIC，但 `LinuxKeyboard.handleKeyEvent()` 仅处理 KeyPress/KeyRelease，不会将 ClientMessage 等 XIM 协议事件传递给 `XFilterEvent`。这导致 XIM 状态机永远收不到 IM 服务器的响应。

### 解决方案分三层

1. **LinuxKeyboardImeProcessor**（ASM）：阻止 LWJGL 创建自己的 XIM/XIC，避免双 XIC 竞争。
2. **LinuxEventImeProcessor**（ASM）：使 `LinuxEvent.filterEvent(J)Z` 始终返回 false，防止 LWJGL 调用 `XFilterEvent`。
3. **LinuxDisplayImeProcessor**（ASM）：在 `processEvents()` 中注入两个 Hook：
   - `onRawXEvent`：在 `nextEvent()` 后、窗口检查前调用，捕获 ClientMessage 等被窗口检查跳过的事件。
   - `onXEvent`：在 `filterEvent()` 后调用，传递事件和 filterEvent 的返回值。

### 原生侧

`ssoptimizer_linux_ime.cpp` 中的 `handleKeyEvent()` 是唯一的 `XFilterEvent` 调用点：
- 所有事件类型都传递给 `XFilterEvent`（XIM 协议需要处理 ClientMessage）。
- 仅 KeyPress 且 `XFilterEvent` 返回 False 时调用 `Xutf8LookupString` 获取输入文本。

## 关键注意事项

- **绝对不能**在 LWJGL 和 SSOptimizer 中同时调用 `XFilterEvent`，否则第二次调用会得到错误结果。
- XIC 的 Focus/Unfocus 必须正确管理，否则 IME 会在全局事件上触发。
- 候选窗位置使用 `XNSpotLocation` 设置，坐标系需从 OpenGL（左下角原点）转换到 X11（左上角原点）。
- 候选窗需要 `XIMPreeditPosition` 风格和 `XNFontSet`（通过 `XCreateFontSet` 创建最小化字体集）。
- 所有 X11 API 调用必须在同一线程（LWJGL 事件处理线程）。

## 调试工具

- `tools/xim_ime_test.c`：独立的 XIM 测试程序，验证 XIM 链路是否正常。
- `tools/ime_keyboard_smoke.py`：自动化 IME 烟测脚本，支持按键序列和鼠标点击。
- `tools/xim_send_keys.py`：向窗口发送按键事件。
