package github.kasuminova.ssoptimizer.mixin.loading;

import com.fs.util.ResourceLoader;
import github.kasuminova.ssoptimizer.common.loading.ResourceIndex;
import github.kasuminova.ssoptimizer.common.loading.ResourceLoaderThreadState;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ResourceLoader 文件探测路径的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.util.ResourceLoader}<br>
 * 注入动机：原版对 DIRECTORY 资源根目录的访问是「逐文件 exists stat 直到命中」，
 * 目录枚举还会按「子目录 × mod 根目录」重复 listFiles，mod 数量多时启动期产生
 * 大量文件系统调用。<br>
 * 注入效果：DIRECTORY 资源的打开、枚举、exists/lastModified 查询全部转交
 * {@link ResourceIndex} 的内存快照；ABSOLUTE_AND_CWD 与 CLASSPATH 资源保持原版逻辑。
 * {@code -Dssoptimizer.disable.resourceindex=true} 时逐方法回退原版文件访问。
 */
@Mixin(targets = GameClassNames.RESOURCE_LOADER_DOTTED)
public abstract class ResourceLoaderMixin {
    @Unique
    private static final AtomicBoolean ssoptimizer$warmupTriggered = new AtomicBoolean();

    @Shadow(remap = false)
    private List<ResourceLoader.ResourceSpec> resourceSpecs;

    /**
     * resourcePath（source filter）读取重定向 → 线程本地。
     * <p>
     * 动机：原版 resourcePath 是实例级全局状态，「setResourcePath 写入 → 下次
     * openResource 读取即置 null」的消费序列在并行加载下会跨线程泄漏（线程 A 的
     * filter 被线程 B 消费，B 的合法资源被错误过滤报 not found）。改由
     * {@link ResourceLoaderThreadState} 按线程封闭，同线程语义与原版一致。
     */
    @Redirect(method = "openResource(Ljava/lang/String;Z)Ljava/io/InputStream;", remap = false,
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                    target = "Lcom/fs/util/ResourceLoader;resourcePath:Ljava/lang/String;"))
    private String ssoptimizer$getSourceFilter(final ResourceLoader self) {
        return ResourceLoaderThreadState.getSourceFilter();
    }

    /**
     * resourcePath 写入重定向 → 线程本地。
     * <p>
     * 覆盖两处写入：{@code setResourcePath(String)} 的设置与
     * {@code openResource(String, boolean)} 消费后的置 null（均为方法内唯一锚点）。
     */
    @Redirect(method = {"openResource(Ljava/lang/String;Z)Ljava/io/InputStream;",
            "setResourcePath(Ljava/lang/String;)V"}, remap = false,
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                    target = "Lcom/fs/util/ResourceLoader;resourcePath:Ljava/lang/String;"))
    private void ssoptimizer$setSourceFilter(final ResourceLoader self, final String filter) {
        ResourceLoaderThreadState.setSourceFilter(filter);
    }

    /**
     * suppressCustomResources 读取重定向 → 线程本地。
     * <p>
     * 原版为全局静态标记：loadCSV/loadJSON(path, false) 置 true，下次 openResource
     * 读取后置 false。并行加载下同样存在跨线程泄漏，一并封闭到线程本地。
     */
    @Redirect(method = "openResource(Ljava/lang/String;Z)Ljava/io/InputStream;", remap = false,
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lcom/fs/util/ResourceLoader;suppressCustomResources:Z"))
    private static boolean ssoptimizer$isSuppressCustomResources() {
        return ResourceLoaderThreadState.isSuppressCustomResources();
    }

    /** suppressCustomResources 消费置 false 重定向 → 线程本地。 */
    @Redirect(method = "openResource(Ljava/lang/String;Z)Ljava/io/InputStream;", remap = false,
            at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
                    target = "Lcom/fs/util/ResourceLoader;suppressCustomResources:Z"))
    private static void ssoptimizer$setSuppressCustomResources(final boolean suppress) {
        ResourceLoaderThreadState.setSuppressCustomResources(suppress);
    }

    /**
     * 按指定资源规格打开资源流。
     *
     * @param path 资源相对路径
     * @param spec 资源规格
     * @return 资源流；未命中时返回 null（原版语义）
     * @throws FileNotFoundException 打开失败时抛出
     * @author KasumiNova
     * @reason DIRECTORY 资源改走 ResourceIndex 内存快照，消除逐文件 stat。
     */
    @Overwrite(remap = false)
    public InputStream openResource(final String path,
                                    final ResourceLoader.ResourceSpec spec) throws FileNotFoundException {
        switch (spec.type) {
            case DIRECTORY: {
                if (ResourceIndex.isEnabled()) {
                    ssoptimizer$triggerWarmup();
                    try {
                        return ResourceIndex.open(new File(spec.path), path);
                    } catch (final FileNotFoundException e) {
                        throw e;
                    } catch (final IOException e) {
                        throw new FileNotFoundException(e.getMessage());
                    }
                }
                final File file = new File(spec.path + "/" + path);
                if (file.exists()) {
                    return new BufferedInputStream(new FileInputStream(file));
                }
                return null;
            }
            case ABSOLUTE_AND_CWD: {
                final File file = new File(path);
                if (file.exists()) {
                    return new BufferedInputStream(new FileInputStream(file));
                }
                return null;
            }
            case CLASSPATH: {
                final InputStream stream = ResourceLoader.class.getClassLoader().getResourceAsStream(path);
                if (stream != null) {
                    return new BufferedInputStream(stream);
                }
                return null;
            }
            default:
                return null;
        }
    }

    /**
     * 查询指定资源规格下资源的最后修改时间。
     *
     * @author KasumiNova
     * @reason DIRECTORY 资源改走 ResourceIndex 内存快照。
     */
    @Overwrite(remap = false)
    public long getResourceLastModified(final String path,
                                        final ResourceLoader.ResourceSpec spec) {
        switch (spec.type) {
            case DIRECTORY: {
                if (ResourceIndex.isEnabled()) {
                    ssoptimizer$triggerWarmup();
                    return ResourceIndex.lastModified(new File(spec.path), path);
                }
                final File file = new File(spec.path + "/" + path);
                return file.exists() ? file.lastModified() : 0L;
            }
            case ABSOLUTE_AND_CWD: {
                final File file = new File(path);
                return file.exists() ? file.lastModified() : 0L;
            }
            default:
                return 0L;
        }
    }

    /**
     * 查询指定资源规格下资源是否存在。
     *
     * @author KasumiNova
     * @reason DIRECTORY 资源改走 ResourceIndex 内存快照。
     */
    @Overwrite(remap = false)
    public boolean resourceExists(final String path,
                                  final ResourceLoader.ResourceSpec spec) {
        switch (spec.type) {
            case DIRECTORY: {
                if (ResourceIndex.isEnabled()) {
                    ssoptimizer$triggerWarmup();
                    return ResourceIndex.exists(new File(spec.path), path);
                }
                return new File(spec.path + "/" + path).exists();
            }
            case ABSOLUTE_AND_CWD:
                return new File(path).exists();
            case CLASSPATH: {
                try (InputStream stream = ResourceLoader.class.getClassLoader().getResourceAsStream(path)) {
                    return stream != null;
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            }
            default:
                return false;
        }
    }

    /**
     * 解析指定资源规格下资源的 File。
     *
     * @author KasumiNova
     * @reason DIRECTORY 资源改走 ResourceIndex 内存快照。
     */
    @Overwrite(remap = false)
    public File getResourceFile(final String path,
                                final ResourceLoader.ResourceSpec spec) {
        switch (spec.type) {
            case DIRECTORY: {
                if (ResourceIndex.isEnabled()) {
                    ssoptimizer$triggerWarmup();
                    return ResourceIndex.file(new File(spec.path), path);
                }
                final File file = new File(spec.path + "/" + path);
                return file.exists() ? file : null;
            }
            case ABSOLUTE_AND_CWD: {
                final File file = new File(path);
                return file.exists() ? file : null;
            }
            case CLASSPATH:
                throw new RuntimeException("Can't find a file on the classpath - use openResource() instead.");
            default:
                return null;
        }
    }

    /**
     * 枚举目录下指定后缀的文件路径（跨资源根去重，返回相对根目录路径）。
     *
     * @param dir 目录相对路径
     * @param suffix 文件名后缀过滤
     * @return 去重后的路径列表
     * @author KasumiNova
     * @reason DIRECTORY 资源改走 ResourceIndex 内存快照，消除「子目录 × mod 根目录」重复 listFiles。
     */
    @Overwrite(remap = false)
    public List<String> getPathsInDir(final String dir, final String suffix) {
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        final LinkedHashSet<String> result = new LinkedHashSet<>();

        for (final ResourceLoader.ResourceSpec spec : this.resourceSpecs) {
            switch (spec.type) {
                case DIRECTORY: {
                    final File root = new File(spec.path);
                    final List<ResourceIndex.Entry> children = ResourceIndex.isEnabled()
                            ? ResourceIndex.children(root, dir)
                            : ssoptimizer$directChildren(root, dir);
                    final String rootAbsolute = root.getAbsolutePath();
                    for (final ResourceIndex.Entry child : children) {
                        if (!child.file().getName().endsWith(suffix)) {
                            continue;
                        }
                        if (!seen.add(dir + "/" + child.file().getName())) {
                            continue;
                        }
                        String absolute = child.file().getAbsolutePath();
                        if (absolute.length() > rootAbsolute.length()) {
                            absolute = absolute.substring(rootAbsolute.length());
                            if (absolute.startsWith("\\") || absolute.startsWith("/")) {
                                absolute = absolute.substring(1);
                            }
                        }
                        result.add(absolute);
                    }
                    break;
                }
                case ABSOLUTE_AND_CWD: {
                    for (final ResourceIndex.Entry child : ssoptimizer$directChildren(new File(dir), "")) {
                        if (child.file().getName().endsWith(suffix) && seen.add(dir + "/" + child.file().getName())) {
                            result.add(child.file().getAbsolutePath());
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        }

        return new ArrayList<>(result);
    }

    /**
     * 枚举目录下指定后缀的文件路径。
     *
     * @param dir 目录相对路径
     * @param suffix 文件名后缀过滤
     * @param relative true 时走去重相对路径逻辑，false 时返回不去重的绝对路径
     * @return 路径列表
     * @author KasumiNova
     * @reason DIRECTORY 资源改走 ResourceIndex 内存快照。
     */
    @Overwrite(remap = false)
    public List<String> getPathsInDir(final String dir, final String suffix, final boolean relative) {
        if (relative) {
            return getPathsInDir(dir, suffix);
        }

        final List<String> result = new ArrayList<>();
        for (final ResourceLoader.ResourceSpec spec : this.resourceSpecs) {
            switch (spec.type) {
                case DIRECTORY: {
                    final List<ResourceIndex.Entry> children = ResourceIndex.isEnabled()
                            ? ResourceIndex.children(new File(spec.path), dir)
                            : ssoptimizer$directChildren(new File(spec.path), dir);
                    for (final ResourceIndex.Entry child : children) {
                        if (child.file().getName().endsWith(suffix)) {
                            result.add(child.file().getAbsolutePath());
                        }
                    }
                    break;
                }
                case ABSOLUTE_AND_CWD: {
                    for (final ResourceIndex.Entry child : ssoptimizer$directChildren(new File(dir), "")) {
                        if (child.file().getName().endsWith(suffix)) {
                            result.add(child.file().getAbsolutePath());
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return result;
    }

    /**
     * 枚举目录下的子目录（跨资源根去重）。
     *
     * @param dir 目录相对路径
     * @return 子目录相对路径列表
     * @author KasumiNova
     * @reason DIRECTORY 资源改走 ResourceIndex 内存快照。
     */
    @Overwrite(remap = false)
    public List<String> getSubdirectories(final String dir) {
        final LinkedHashSet<String> result = new LinkedHashSet<>();

        for (final ResourceLoader.ResourceSpec spec : this.resourceSpecs) {
            final List<ResourceIndex.Entry> children;
            switch (spec.type) {
                case DIRECTORY:
                    children = ResourceIndex.isEnabled()
                            ? ResourceIndex.children(new File(spec.path), dir)
                            : ssoptimizer$directChildren(new File(spec.path), dir);
                    break;
                case ABSOLUTE_AND_CWD:
                    children = ssoptimizer$directChildren(new File(dir), "");
                    break;
                default:
                    continue;
            }
            for (final ResourceIndex.Entry child : children) {
                final String name = child.file().getName();
                if (child.directory() && !child.file().isHidden() && !name.startsWith(".")) {
                    result.add(dir + "/" + name);
                }
            }
        }

        return new ArrayList<>(result);
    }

    @Unique
    private void ssoptimizer$triggerWarmup() {
        if (!ssoptimizer$warmupTriggered.compareAndSet(false, true)) {
            return;
        }
        final List<File> roots = new ArrayList<>(this.resourceSpecs.size());
        for (final ResourceLoader.ResourceSpec spec : this.resourceSpecs) {
            if (spec.type == ResourceLoader.ResourceType.DIRECTORY && spec.path != null) {
                roots.add(new File(spec.path));
            }
        }
        ResourceIndex.warmupAsync(roots);
    }

    @Unique
    private static List<ResourceIndex.Entry> ssoptimizer$directChildren(final File dir, final String relDir) {
        final File target = relDir.isEmpty() ? dir : new File(dir, relDir);
        final File[] files = target.listFiles();
        final List<ResourceIndex.Entry> result = new ArrayList<>(files == null ? 0 : files.length);
        if (files == null) {
            return result;
        }
        for (final File file : files) {
            final boolean directory = file.isDirectory();
            result.add(new ResourceIndex.Entry(file.getName(), file, directory, file.lastModified(),
                    directory ? 0L : file.length()));
        }
        return result;
    }
}
