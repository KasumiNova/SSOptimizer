# 将 `.20` 的 named 存档迁移至原版 RC8

使用写档时的私有 `.20`（`18e67d2`）发行目录、该实例的启动清单及其中列出的 named 缓存。目标游戏核心须与写档所用的原始归档完全一致。每个 named 归档的清单必须记录原始 SHA-256 和未压缩 Tiny 的 SHA-256。工具校验游戏和模组归档，并检查映射是否匹配。来源不明或只有原版字段的槽会报错。

退出正在写入该槽的游戏，选择一个不存在的新槽路径。父目录须已存在。在本仓库根目录使用 Python 3 和 JDK 25 执行：

```sh
export JAVA_HOME=/path/to/jdk-25
python3 tools/save-migration/migrate.py \
  --from-18e67d2 /private/archive/ssoptimizer-standalone-20/version \
  --plan /private/archive/instance/cache/launch.properties \
  --core /private/archive/game/starsector-core \
  /private/archive/old-slot /private/qa/saves/converted-slot
```

命令使用旧发行中的启动器、payload、私有映射和原游戏的 JAXB，在临时目录编译当前工作区的迁移程序。Windows 同样可使用 `python` 运行该脚本，`JAVA_HOME` 需指向本机 JDK 25。编译和转换都可断网运行。

工具按源类的字段声明、描述符、继承关系和 XStream 别名处理 XML。它只对已定位的地形字段恢复原版 Deflate，对战役引擎的两个 `.20` 索引缓存布尔值作删除处理。主存档与配套的 `.bak` 都会转换。使用 `campaign.zip` 的槽保留压缩格式。其他槽文件复制到新目录。输出前再次核对源文件摘要。已经存在的槽、未完成的写入、链接或来源冲突都会中止迁移。

成功后，新槽中的 `.ssoptimizer-named-migration.properties` 记录原始路径、写档归档、Tiny 摘要及转换前后各文件的 SHA-256。使用相同的游戏字节码和模组集合载入新槽，回退时选择原槽。

## 独立验证

Windows 英文 RC8 原版槽在 Linux JDK 25 的 obf 构件中加载并另存，关闭进程后再次加载。未安装模组的原版 RC8 使用随游戏提供的 Java 17 读回了该新档。实际 `.20` 写出的命名存档也经过相同原版单独冷加载。真实战役的明文和压缩副本分别完成转换，压缩主文件及备份的 XML 逐字节对比通过。原版槽交给 named 迁移命令时被拒绝。

[真实样本、摘要、截图与命令](</mnt/store/Games/ssoptimizer-standalone-validation-rc8/evidence/save-obf-rc8-20261120/README.md>)
