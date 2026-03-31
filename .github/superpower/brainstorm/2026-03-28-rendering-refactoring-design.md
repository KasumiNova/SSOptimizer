# 渲染代码重构设计 (Phase A & B)

## 1. 目标与背景
基于前期的注入基建 (ASM + Mixin 混合织入器)，现正式进入渲染管线的优化。此次优先落地 Phase A 与 Phase B，旨在不破坏图像正确性的前提下，极大地剥离废操作，减少 OpenGL (LWJGL) JNI 开销。

## 2. 核心架构：渲染状态缓存 (RenderStateCache)
- **存储模型**：采用 `ThreadLocal` 线程隔离模型。在保障极低性能开销的同时，防范未来可能出现的异步或多线程后加载问题。
- **职责**：追踪并记录当前 OpenGL 上下文的活跃状态数据，包括：
  - 最后一次绑定的 `Texture ID`
  - 当前激活的 `BlendFunc` 模式
  - 当前写入的着色器色彩或矩阵帧状态

## 3. Phase A：基础状态拦截与去重
- **CombatState JNI 剥离**：使用 Mixin 注入 `CombatState.traverse()` (或者在 ASM 层面)，旁路高开销的 `GL11.glFinish()` 调用，仅允许在开发者 Profiler 下通过。
- **纹理绑定去重**：拦截高频 `Object.Ø00000()` / `Sprite.bind()` 方法，在切换贴图前对比 `RenderStateCache` 的记录。若本次请求的纹理 ID 与上一帧相同，直接 `return` 放弃向 GPU 提交绑定信号。

## 4. Phase B：状态短路快车道 (Fast Path)
- **注入目标**：
  - `Sprite.render()`
  - `Sprite.renderRegion()`
  - `GenericTextureParticle.render()`
- **技术映射**：
  - **环境预判**：对象调用 `render()` 前，Mixin 获取该精灵/粒子需求的混合模式与渲染颜色。
  - **状态比对与短路**：对比缓存一致时，利用前置构建好的 ASM 字节码处理器，动态擦除原本内置于渲染逻辑内的 `glPushMatrix() / glPopMatrix()` 与冗余 `glBlendFunc/glColor4f` 调用链，将多条 JNI 请求压缩成纯原生的坐标推演。
- **回退安全 (Fallback)**：遇到不受管辖的 UI 组件 (例如第三方自写 `glBegin/glEnd` 绘制操作)，捕获其上下文越界抛出并主动驱逐缓存，强制下一帧对象完全重走标准 JNI 以保证渲染正确一致。

## 5. 验收标准
- 确保游戏在 60 帧重负载战斗场景下，JNI (尤其 `glBindTexture` 和 `glBlendFunc`) 总调用量较优化前下降 35%-55%。
- 肉眼无任何 UI、粒子或飞船轮廓渲染错乱现象。
