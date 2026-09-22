# 持久映射有序写入桥（mapped-write-bridge）

## 背景与目标

SSO RT 管线化下，渲染线程执行第 N 帧绘制命令时，模组逻辑线程可能已在推进第
N+1 帧并**直接向持久映射 VBO 内存写数据**——这类写入不经过任何 GL 调用，
bridge 录制层无法感知，与在飞绘制并发读到写了一半的数据。

已确证的触发方：BoxUtil 1.6.0 静态尾迹（`BUtil_StaticTrailMemoryPool`，
`GL_MAP_WRITE_BIT|GL_MAP_PERSISTENT_BIT` 真实映射，`computeTrailNode` /
`processCutTrailOnEntity` 两处经 `pool.getMappingBuffer().asIntBuffer()` 直写
映射内存），表现为 RT 模式下小概率大色块撕裂（小段 trail 突变成大块不规则
三角形）闪烁。

目标：在不改变模组写入时机语义的前提下，让「向映射内存落笔」获得相对绘制
命令的流序与 GPU 序，消除跨帧竞争。

## 设计：流内有序上传

核心思路：**逻辑线程不再直写映射内存**，改写向等容 CPU scratch；真正的落笔
封装为命令排入渲染流，由渲染线程在确认 GPU 读完上一帧后执行。

### 写入路径（逻辑线程）

1. Mixin（`sso-modopt` 的 `StaticTrailMemoryPoolMixin`）把两处
   `getMappingBuffer().asIntBuffer()` 重定向到等容 CPU scratch（按映射
   ByteBuffer 弱身份键管理，跟随映射字节序；池扩容换新映射即新槽）。
2. 逻辑线程只写 scratch——与 GPU 无任何共享，天然无竞争。
3. 方法 RETURN 时把 scratch 全量经 `MappedWriteBridge.submitOrderedWrite`
   排入渲染流：提交时经 `BridgeSupport.pool().snapshot` 快照（MPMC 线程
   安全），命令携带快照、映射令牌与当前帧序号。
4. compute 双逻辑线程去重：槽上 `pendingUpload` 保证每池每轮只提交一次
   （先 RETURN 者提交时双方已过 computeBarrier，scratch 完整）；cut 路径
   单线程逐槽提交。

### 执行路径（渲染线程，`MappedWriteBridgeImpl.executeOrderedWrite`）

1. **帧序闸门**：`fullyExecutedSeq < frameSeq - 1` 说明上一帧悬挂未排完
   （其帧尾标记在续跑任务里，队列序上排在本帧之后），抛
   `SuspendFrameException` 悬挂本帧重排，流序不被颠覆。该判定必须在快照
   归还的 finally 之外——悬挂后命令持同一快照重试，提前归还会造成同一
   实例双重入池（别名污染）与重试窗口内的数据覆写。
2. **生命周期令牌复查**：映射在提交后、执行前可能因池扩容/销毁失效
   （`GPUMemoryPool._expandBuffer` 会 unmap 旧映射重建 VBO），失效即跳过
   并 warn，写入方下一计算周期重写全量数据自愈。
3. **帧尾 fence 等待**：对上一帧帧尾发布的真实 fence sync 做
   `clientWaitSync`（1s 超时跳过并 warn）——GPU 侧上一帧的 VBO 读全部
   结束才落笔。
4. 字节拷贝进映射，随后 `glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT)`
   建立 CPU 写对后续 GPU 命令的可见性（非相干持久映射必须）。
5. finally 归还快照。

### 帧尾 fence 发布

- 通道挂载（armed，首个 submit 触发）后，`RenderQueueImpl.submitCurrentFrameLocked`
  经 `FrameEndMarkerHook` 在每帧帧尾追加标记命令；渲染线程执行到它（= 本帧
  全部命令已进入 GPU 队列）即 `fenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE)` 发布
  到 `latestFence`。
- fence 槽 latest/retiring 双槽、隔代删除，全部只被渲染线程访问，无锁。
- 未挂载时帧尾不插标记，零开销。
- 挂载初始化：`fullyExecutedSeq = max(-1, queue.lastSubmittedSequence())`——
  挂载前的帧无标记但也无经本通道的写入所对应的绘制，悬挂判定对它们无对象
  可保护。

### 生命周期登记（`RealMappingRegistry`）

- track：`GL30.glMapBufferRange` 真实回退（持久映射非仿真路径）返回点，
  以（VBO id ↔ 映射 ByteBuffer）登记令牌。
- 失效：`GL15.glUnmapBuffer` 真实路径（先于真实 unmap 执行）、
  `BufferMapEmulator.onDeleteBuffer` / `onBufferData`（存储重建即映射失效）。
- 有意不钩 GL44 bufferStorage：合法调用杀不死既有映射，属不可能情形。
- 未登记的映射拒绝写入并记 error——无法校验生命周期的写入可能在池扩容
  unmap 后触达已释放内存（SIGSEGV），宁可跳过等下一周期自愈。

## 边界与已知限制

- **只覆盖持久真实映射路径**：非持久回退路径（GL < 4.4 才出现）的
  `glBufferSubData` 跨上下文乱序问题不在本机制覆盖内（mixin javadoc 已注明）。
- 跳过语义全部有 warn/error 日志，无静默兜底；跳过造成的尾迹缺口由写入方
  下一计算周期全量重写自愈。
- scratch 与映射同尺寸、全量上传：尾迹池规模固定（量级 MB 内），相比引入
  脏区追踪的复杂度，全量拷贝的开销可忽略且语义简单。

## 测试

- `RealMappingRegistryTest`：登记/查询/失效/重置语义。
- `MappedWriteBridgeImplTest`：happy path（字节级内容相等、fence 等待句柄
  身份、屏障位、快照借还平衡、fence 隔代删除）、未登记拒绝、失效跳过、
  fence 超时跳过、悬挂重排（验证写入命令被重排到上一帧续跑之后且快照不
  提前归还）、未挂载零开销。
- `RenderQueueSequenceTest`：帧序号单调、帧尾标记执行时机、
  `lastSubmittedSequence` 语义。
