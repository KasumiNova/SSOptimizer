package ssoptimizer.standalone.host;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/** 只在构造 URL 类路径的调用处使用具名归档，普通资源 URL 保留原件地址。 */
public final class NamedLoaderUrls {
    private static final String LOADER = "java/net/URLClassLoader";
    private static final String HELPER = "ssoptimizer/standalone/host/NamedLoaderUrls";
    private static final String ARRAY = "[Ljava/net/URL;";

    private static volatile Map<String, URL> archives = Map.of();

    private NamedLoaderUrls() { }

    static void install(LaunchPlan plan) throws IOException {
        var urls = new HashMap<String, URL>();
        for (var mounted : plan.archives) {
            URL file = mounted.toUri().toURL();
            URL original = plan.origins.getOrDefault(mounted, mounted).toUri().toURL();
            if (!file.equals(original)) urls.put(original.toExternalForm(), file);
            urls.put("jar:" + file.toExternalForm() + "!/", file);
        }
        archives = Map.copyOf(urls);
    }

    public static URL[] mounted(URL[] urls) {
        if (urls == null) return null;
        URL[] result = urls;
        for (int i = 0; i < urls.length; i++) {
            URL mounted = mounted(urls[i]);
            if (mounted == urls[i]) continue;
            if (result == urls) result = urls.clone();
            result[i] = mounted;
        }
        return result;
    }

    public static URL mounted(URL url) {
        return url == null ? null : archives.getOrDefault(url.toExternalForm(), url);
    }

    static byte[] rewrite(String name, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        var node = new ClassNode();
        reader.accept(node, 0);
        boolean changed = false;
        for (var method : node.methods) {
            int scratch = method.maxLocals;
            for (var it = method.instructions.iterator(); it.hasNext();) {
                var insn = it.next();
                if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(LOADER)) continue;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("addURL")
                        && call.desc.equals("(Ljava/net/URL;)V")) {
                    method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "mounted",
                            "(Ljava/net/URL;)Ljava/net/URL;", false));
                    changed = true;
                    continue;
                }
                if (!call.name.equals("<init>") && !call.name.equals("newInstance")) continue;
                Type[] args = Type.getArgumentTypes(call.desc);
                int index = args[0].getDescriptor().equals(ARRAY) ? 0 : 1;
                if (index >= args.length || !args[index].getDescriptor().equals(ARRAY)) continue;
                int trailing = args.length - index - 1;
                var patch = new InsnList();
                for (int i = trailing - 1; i >= 0; i--) patch.add(new VarInsnNode(Opcodes.ASTORE, scratch + i));
                patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "mounted", "(" + ARRAY + ")" + ARRAY, false));
                for (int i = 0; i < trailing; i++) patch.add(new VarInsnNode(Opcodes.ALOAD, scratch + i));
                method.maxLocals = Math.max(method.maxLocals, scratch + trailing);
                method.instructions.insertBefore(call, patch);
                changed = true;
            }
        }
        if (!changed) return bytes;
        var writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
