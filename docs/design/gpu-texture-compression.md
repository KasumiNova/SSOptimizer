# 显卡纹理压缩（GPU Texture Compression）候选记录

状态：**仅记录方向，未规划实施**。排在字体渲染重写之后。本平台范围：Linux / Windows，
**不考虑 macOS 兼容性**。

## 动机

游戏贴图（舰船/武器图集、背景、UI）当前以未压缩 RGBA 纹理解码常驻显存。接入 GPU 压缩纹理
（BC 族）可将显存占用降至 1/4~1/8，并减少纹理上传带宽；与 sso-loading 的
LazyTextureManager / 图集体系是天然的接入层。

## 候选技术

| 候选 | 说明 | 备注 |
|---|---|---|
| **NVTT 3** | NVIDIA Texture Tools 3，CPU/GPU 双后端压缩器，支持 BC1~BC7 全族，C++ 库可嵌 native 模块 | 首选编码器候选；需确认许可（MIT）与 Linux/Windows 构建链 |
| **BC1 / BC6 / BC7 算法优化** | BC1（RGB 4bpp）、BC7（RGBA 高质量 8bpp）、BC6H（HDR） | BC7 质量/体积比最佳但编码慢，适合离线预压 + 缓存；BC1 可运行时实时压 |
| **DirectDraw (DDS)** | 压缩纹理的容器格式（.dds，含 mip 链） | 作为磁盘缓存容器候选；游戏贴图源为 png，需转换管线 |

## 初步接入设想（待正式设计）

- 接入点：sso-loading `LazyTextureManager` 解码后、上传前压缩；或离线预压为 DDS 缓存
  （类似 FontPackCache 的指纹缓存机制）。
- 运行时压缩选 BC1/BC7 快速档，离线预压选 BC7 高质量档；两者可共存（运行时压缩做兜底）。
- native 侧新子模块（如 native-texcompress），内嵌 NVTT 3 或自研 BC 编码器。
- 需验证：游戏 GL 上下文对 `GL_EXT_texture_compression_s3tc` / `GL_ARB_texture_compression_bptc`
  的支持面（Linux Mesa / Windows 驱动）；不支持时静默回退未压缩路径并记日志。
- 图集类纹理（ShipWeaponAtlas 等）压缩需与 UV remap 逻辑联调，注意块压缩的 4×4 边界对齐。

## 待办（立项时回答）

1. 显存基线测量：当前各场景纹理显存占用与上传耗时（诊断先行）。
2. NVTT 3 构建链接入评估（pkg-config/预编译二进制/源码内嵌三选一）。
3. 压缩缓存目录与指纹设计（复用 `mods/ssoptimizer/cache/` 体系）。
