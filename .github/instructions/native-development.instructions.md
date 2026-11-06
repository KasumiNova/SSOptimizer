---
description: "C++ / JNI 原生模块开发：OpenGL、PNG、纹理压缩、字体栅格化与输入法"
applyTo: "{modules/internal/sso-render/native,modules/internal/sso-loading/native,modules/internal/sso-loading/native-texcompress,modules/internal/sso-font/native,modules/internal/sso-ime/native}/src/**/*.{cpp,h,c}"
---

# C++ / JNI 原生模块开发规范

## 编译要求

- 使用 C++ 20。
- 依赖按模块拆分：OpenGL（渲染）、libpng（PNG 解码）、FreeType（字体）、X11/XIM 与 IMM32（输入法）。纹理压缩使用模块内置的 bc7enc 与 rgbcx，不依赖第三方系统库。
- 原生库产物为 `libssoptimizer_<module>.so` 或 `ssoptimizer_<module>.dll`，构建命令如下：
  - `./gradlew :modules:internal:sso-render:native-render:build`
  - `./gradlew :modules:internal:sso-loading:native-loading:build`
  - `./gradlew :modules:internal:sso-loading:native-texcompress:build`
  - `./gradlew :modules:internal:sso-font:native-font:build`
  - `./gradlew :modules:internal:sso-ime:native-ime:build`

## JNI 注释规范

- JNI 边界说明对应 Java 方法、参数与返回值，以及内存所有权和释放责任。
- 模块内部函数默认不写复述流程的注释，只在非直观生命周期、线程约束或外部库缺陷绕过时保留单句说明。
- 注释使用简体中文，不写设计稿章节号、决议代号或版本演变。

## 内存管理

- JNI 返回的 `jbyteArray`、`jstring` 由 JVM GC 管理，不需要手动释放。
- 原生分配的内存必须有对应释放路径，局部引用与全局引用按 JNI 生命周期规则释放。
- 使用 RAII 或 scope guard 管理临时资源。

## 错误处理

- 不得让 C++ 异常越过 JNI 边界进入 JVM。
- 错误通过 JNI `ThrowNew` 传递回 Java 层，返回前释放已持有的原生资源。
- 空指针与能力检查失败时，明确抛出异常或记录日志，不得静默忽略。

## X11 / XIM（Linux IME）

- `XFilterEvent` 必须接收所有事件类型，XIM 协议使用 ClientMessage（type=33）通信。
- 仅在 KeyPress 且 `XFilterEvent` 返回 False 时调用 `Xutf8LookupString`。
- XIC 生命周期与游戏窗口绑定，窗口切换时先 detach，再 reattach。
- 所有 X11 调用必须在同一线程。

## OpenGL 批渲染

- 确保 VAO / VBO 的绑定与解绑配对。
- 在 JNI 函数入口检查 OpenGL context 是否可用。
