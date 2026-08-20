import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * ssoptimizer-native-module 插件的参数化扩展。
 *
 * 每个 native 子模块通过 ssoNative { ... } 声明自己的功能域开关，
 * 插件据此裁剪 pkg-config 探测、编译宏、链接库与运行时依赖收集。
 */
abstract class SsoNativeExtension {
    /**
     * 模块名（render/loading/font/ime），决定产物名：
     * libssoptimizer_<moduleName>.so（Linux）/ ssoptimizer_<moduleName>.dll（Windows）。
     */
    abstract val moduleName: Property<String>

    /** 是否探测并链接 libpng（loading 模块）。 */
    abstract val libpng: Property<Boolean>

    /** 是否探测并链接 freetype（font 模块）。 */
    abstract val freetype: Property<Boolean>

    /** 是否探测并链接 X11（ime 模块，仅 Linux）。 */
    abstract val x11: Property<Boolean>

    /** Windows 目标系统库（裸名，如 opengl32 / user32 / imm32）。 */
    abstract val windowsSystemLibs: ListProperty<String>

    /** Linux 目标系统库（裸名，如 GL）。 */
    abstract val linuxSystemLibs: ListProperty<String>
}
