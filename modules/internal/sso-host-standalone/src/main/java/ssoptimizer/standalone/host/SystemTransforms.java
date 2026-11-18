package ssoptimizer.standalone.host;

import com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.jar.Manifest;

public final class SystemTransforms implements RfbPlugin {
    private static final Map<String, UnaryOperator<byte[]>> targets = new ConcurrentHashMap<>();
    private static volatile boolean installed;

    @Override
    public RfbClassTransformer[] makeTransformers() {
        installed = true;
        return new RfbClassTransformer[]{new SystemTransformer()};
    }

    static void checkInstalled() {
        if (!installed) throw new IllegalStateException("RFB 未发现 SSOptimizer 系统插件，请检查系统 classpath");
    }

    static void register(String binaryName, UnaryOperator<byte[]> transform) {
        String name = binaryName.replace('/', '.');
        RfbSystemClassLoader system = (RfbSystemClassLoader) ClassLoader.getSystemClassLoader();
        if (system.findCachedClass(name) != null) throw new IllegalStateException("IME 目标已定义: " + name);
        if (targets.putIfAbsent(name, transform) != null) throw new IllegalStateException("重复的系统处理器: " + name);
    }

    private static final class SystemTransformer implements RfbClassTransformer {
        @Override public String id() { return "ime"; }

        @Override public boolean shouldTransformClass(ExtensibleClassLoader loader, Context context,
                                                      Manifest manifest, String name, ClassNodeHandle node) {
            return context == Context.SYSTEM && node.isPresent() && targets.containsKey(name.replace('/', '.'));
        }

        @Override public void transformClass(ExtensibleClassLoader loader, Context context, Manifest manifest,
                                             String name, ClassNodeHandle node) {
            byte[] result = targets.get(name.replace('/', '.')).apply(node.computeBytes());
            if (result == null) throw new IllegalStateException("IME 字节码与目标不匹配: " + name);
            ClassNode replacement = new ClassNode();
            new ClassReader(result).accept(replacement, 0);
            node.setNode(replacement);
            LogManager.getLogger(SystemTransforms.class).info("已变换 {} (SYSTEM)", name);
        }
    }
}
