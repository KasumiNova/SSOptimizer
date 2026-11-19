package ssoptimizer.standalone.host;

import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class PreparedSources {
    private static volatile PreparedSources active;
    private final LaunchPlan plan;
    private final LaunchClassLoader launch;
    private final Set<Path> modJars;

    private PreparedSources(LaunchPlan plan, LaunchClassLoader launch) {
        this.plan = plan;
        this.launch = launch;
        this.modJars = Set.copyOf(plan.mods);
    }

    static synchronized void install(LaunchPlan plan, LaunchClassLoader loader) {
        if (active != null) throw new IllegalStateException("启动清单已安装");
        active = new PreparedSources(plan, loader);
    }

    public static Set<String> enabledIds() { return active.plan.enabled; }

    public static String selectionProblem() { return active.plan.selectionProblem; }

    static boolean isModClass(String name) {
        PreparedSources sources = active;
        if (sources == null || name == null) return false;
        Path owner = sources.plan.owners.get(name.replace('/', '.'));
        return owner != null && sources.modJars.contains(owner);
    }

    public static URL namedJar(URL location) {
        try {
            return active.plan.namedLocation(location);
        } catch (IOException | URISyntaxException ex) {
            throw new IllegalStateException("无法解析模组 CodeSource: " + location, ex);
        }
    }

    public static ClassLoader scriptParent(ClassLoader original, List<String> jarFiles) {
        PreparedSources sources = active;
        try {
            var resolved = new ArrayList<Path>();
            for (String jar : jarFiles) {
                Path source = Path.of(jar);
                Path named = sources.plan.namedSource(source);
                if (named == null) throw new IllegalStateException("模组不在已准备的清单中，请重新启动: " + source);
                resolved.add(named);
            }
            List<Path> selected = resolved.stream().filter(path -> !path.equals(sources.plan.payload)).toList();
            int payloadEntries = resolved.size() - selected.size();
            if (payloadEntries != (sources.plan.payload == null ? 0 : 1)) {
                throw new IllegalStateException("SSOptimizer 游戏模组的启用状态已变更，请重新启动: " + resolved);
            }
            if (!selected.equals(sources.plan.mods)) throw new IllegalStateException(
                    "启用模组或加载顺序已变更，请重新启动。清单=" + sources.plan.mods + "，游戏=" + selected);
            ClassLoader parent = original;
            if (original.getClass() == URLClassLoader.class) {
                parent = original.getParent();
                ((URLClassLoader) original).close();
            }
            URL[] urls = new URL[resolved.size()];
            for (int i = 0; i < urls.length; i++) urls[i] = resolved.get(i).toUri().toURL();
            return sources.new ScriptParent(urls, parent);
        } catch (IOException e) {
            throw new IllegalStateException("无法建立 named 模组脚本类路径: " + jarFiles, e);
        }
    }

    private final class ScriptParent extends URLClassLoader {
        ScriptParent(URL[] urls, ClassLoader parent) { super("SSOptimizer-ScriptParent", urls, parent); }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Path owner = plan.owners.get(name);
            if (owner == null) return super.loadClass(name, resolve);
            try {
                // 必须经过游戏的脚本访问检查；受管理的类由其父级 Launch 定义。
                Class<?> type = getParent().loadClass(name);
                if (type.getClassLoader() != launch) throw new LinkageError("类定义域与启动清单冲突: " + name
                        + "，来源=" + owner + "，加载器=" + type.getClassLoader());
                return type;
            } catch (ClassNotFoundException failure) {
                String message = "Launch 定义失败，已阻止脚本加载器重新定义: " + name + "，来源=" + owner + "，加载器=" + launch;
                LogManager.getLogger(PreparedSources.class).error(message, failure);
                throw new LinkageError(message, failure);
            }
        }
    }
}
