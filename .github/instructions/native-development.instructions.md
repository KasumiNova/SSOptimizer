---
description: "C++ / JNI 原生模块开发：字体栅格化、PNG 解码、OpenGL 批渲染、Linux IME"
applyTo: "native/src/**/*.{cpp,h,c}"
---

# C++ / JNI 原生模块开发规范

## 编译要求

- C++ 20 标准
- 依赖：FreeType（字体栅格化）、X11 / XIM（Linux IME）、OpenGL（批渲染）
- 构建：`./gradlew :native:build`

## JNI 注释规范

每个 JNI 函数必须注释以下内容：

```cpp
/**
 * 对应 Java 方法：类全名#方法名(参数描述)
 *
 * @param env JNI 环境
 * @param ... 每个参数的含义
 * @return 返回值含义
 *
 * 内存管理：说明哪些资源需要调用方释放，哪些由原生侧管理
 */
```

## 内存管理

- JNI 返回的 `jbyteArray`、`jstring` 由 JVM GC 管理，不需要手动释放。
- 原生分配的内存（如 `malloc`、`new`）必须有对应的释放路径。
- 使用 RAII 或 scope guard 管理临时资源。

## 错误处理

- JNI 函数中不要抛出 C++ 异常（会导致 JVM 崩溃）。
- 错误信息通过 JNI `ThrowNew` 传递回 Java 层。
- 关键路径使用 `if (!ptr) { ThrowNew(env, ...); return; }` 模式。

## X11 / XIM（Linux IME）

- `XFilterEvent` 必须传递所有事件类型，不仅仅是 KeyPress。XIM 协议使用 ClientMessage（type=33）通信。
- `Xutf8LookupString` 只能在 `XFilterEvent` 返回 False 的 KeyPress 事件上调用。
- XIC 生命周期与游戏窗口绑定：窗口切换时 detach → reattach。
- 所有 X11 调用必须在同一线程。

## OpenGL 批渲染

- 确保 VAO / VBO 的绑定-解绑配对。
- 在 JNI 函数入口检查 OpenGL context 是否可用。
