import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class SsoNativeExtension {
    abstract val moduleName: Property<String>

    abstract val libpng: Property<Boolean>

    abstract val freetype: Property<Boolean>

    abstract val x11: Property<Boolean>

    abstract val windowsSystemLibs: ListProperty<String>

    abstract val linuxSystemLibs: ListProperty<String>
}
