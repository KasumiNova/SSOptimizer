package ssoptimizer.standalone.host;

import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
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

    private PreparedSources(LaunchPlan plan, LaunchClassLoader launch) {
        this.plan = plan;
        this.launch = launch;
    }

    static synchronized void install(LaunchPlan plan, LaunchClassLoader loader) {
        if (active != null) throw new IllegalStateException("启动清单已安装");
        active = new PreparedSources(plan, loader);
    }

    public static Set<String> enabledIds() { return active.plan.enabled; }

    public static String selectionProblem() { return active.plan.selectionProblem; }

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
            ClassLoader parent = original;
            if (original.getClass() == URLClassLoader.class) {
                parent = original.getParent();
                ((URLClassLoader) original).close();
            }
            URL[] urls = new URL[resolved.size()];
            for (int i = 0; i < urls.length; i++) urls[i] = resolved.get(i).toUri().toURL();
            return sources.new ScriptParent(urls, parent);
        } catch (IOException e) {
            throw new IllegalStateException("无法建立模组脚本类路径: " + jarFiles, e);
        }
    }

    private final class ScriptParent extends URLClassLoader {
        ScriptParent(URL[] urls, ClassLoader parent) { super("SSOptimizer-ScriptParent", urls, parent); }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (LaunchPlan.isSystem(name)) return super.loadClass(name, resolve);
            URL resource = launch.findResource(name.replace('.', '/') + ".class");
            if (resource == null) return super.loadClass(name, resolve);
            try {
                // 必须经过游戏的脚本访问检查，受管理的类由其父级 Launch 定义。
                return getParent().loadClass(name);
            } catch (ClassNotFoundException failure) {
                String message = "Launch 定义失败，已阻止脚本加载器重新定义: " + name + "，资源=" + resource + "，加载器=" + launch;
                LogManager.getLogger(PreparedSources.class).error(message, failure);
                throw new LinkageError(message, failure);
            }
        }
    }
}
