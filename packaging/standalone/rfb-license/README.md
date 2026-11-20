# RetroFuturaBootstrap 1.0.12

独立版 dev 和 obf 随包分发修改后的 RetroFuturaBootstrap 1.0.12。系统加载器按上游规则查找类。当本地无归档来源、RFB 变换器未生成字节、Launch 子加载器已安装且能从自身获取同名类时，追加一次受 RFB 原递归保护的委派。系统资源缺失时暂缓定义包，避免后续加载同包密封 JAR 中的类时触发 `sealing violation`。RFB 变换器提供系统类字节后，系统加载器仍在定义类前定义包。

RetroFuturaBootstrap 依据 GNU LGPL 第 3 版分发。完整许可证文本附在本目录。修改对应源码包括[上游 1.0.12](https://github.com/GTNewHorizons/RetroFuturaBootstrap/tree/1.0.12) 和同目录的 [PatchRfbDelegation.java](PatchRfbDelegation.java)。在 SSOptimizer 源码目录运行 `./gradlew patchStandaloneRfb` 可以生成修改后的库。Windows 发布签名读取包含该库的暂存目录。
