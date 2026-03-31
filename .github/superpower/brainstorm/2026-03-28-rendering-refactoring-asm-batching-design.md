# 渲染重构方案与批处理接管设计 (Phase C)

## 1. 架构：原生命令队列与 JNI 桥接
- **原理**：在 Java 端开辟直接内存（Direct ByteBuffer，非堆）或通过 Unsafe 分配，构建一组连续的“Native Command Struct”。
- **单帧数据包**：包含完整的顶点坐标 (`x, y`)、纹理标识 (`TextureID`)、混合模式与色彩数据缓存。
- **发送下放**：避免 Java 侧每一帧的 `glBegin`/`glVertex` 立即模式碎片化。当队列满载或单帧结束，执行一次 `NativeRenderer.flushQueue(ptr, count)` 通知 C++ 层。

## 2. 核心执行逻辑：C++ 端的流水线重排与渲染
- **重排并消除冗余**：C++ 端取得整个单批次数据后，首先基于 `TextureID` 或 `BlendFunc` 排序/分组，消解不必要的上下文绑定切换。
- **渲染发送**：将规整的连续数据转换或直接对应为 `glDrawElements` / `glDrawArrays` 或 VBO/VAO 的提交模式，将原本成百上千次的 JNI 与系统穿透开销降至个位数。
- **短路逻辑结合**：与之前实现的 `RenderStateCache` 和 `SpriteRenderAdapter` 对接，真正填充这些 Fast Path 路由下来的顶点数据。

## 3. 全局接管与未托管 UI 的处理机制
- **彻底的 ASM 接管机制**：弃用被动的异常捕获与状态清理，转为侵入式。通过 ASM 拦截所有第三方 Mod 直接调用的 `org.lwjgl.opengl.GL11` (例如 `glBegin` / `glVertex` / `glColor`)。
- **动态分流**：将检测到的裸调指令强行剥离，打包并追加至本优化器自己的【Native Command Queue】中去，实现原生第三方代码对优化器无感、但同样享受批处理优化的大幅度提升。
- **兼容性考量**：处理极少数直接读写 GPU 显存或调用特定后处理着色器（如 `glUseProgram`）的操作时强制切断批处理栈并执行立即提交 (Flush)，确保正确的图层与光影混合关系。
- **Mod 兼容白名单机制**：为了应对少数由于强制批处理而出现严重渲染错误的第三方 Mod，增加了一套动态配置（例如基于包名或类名）的白名单系统。位于白名单内的代码在执行 GL11 裸调用时，将暂时关闭命令队列累积，直接穿透回原生 JNI。