package ssoptimizer.standalone.host;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import net.minecraft.launchwrapper.LaunchClassLoader;
import java.util.HashMap;
import java.util.Map;

/** 只在构造 URL 类路径的调用处使用具名归档，普通资源 URL 保留原件地址。 */
public final class NamedLoaderUrls {
    private static final String LOADER = "java/net/URLClassLoader";
    private static final String HELPER = "ssoptimizer/standalone/host/NamedLoaderUrls";
    private static final String ARRAY = "[Ljava/net/URL;";

    private record Locations(LaunchClassLoader launch, Map<String, URL> mounted, Map<String, URL> originals) { }
    private static volatile Locations locations;

    private NamedLoaderUrls() { }

    static void install(LaunchPlan plan, LaunchClassLoader launch) throws IOException {
        var urls = new HashMap<String, URL>();
        var originals = new HashMap<String, URL>();
        for (var mounted : plan.archives) {
            URL file = mounted.toUri().toURL();
            URL original = plan.origins.getOrDefault(mounted, mounted).toUri().toURL();
            if (!file.equals(original)) {
                urls.put(original.toExternalForm(), file);
                originals.put(file.toExternalForm(), original);
            }
            originals.put("jar:" + file.toExternalForm() + "!/", original);
            urls.put("jar:" + file.toExternalForm() + "!/", file);
        }
        locations = new Locations(launch, Map.copyOf(urls), Map.copyOf(originals));
    }

    public static URL[] mounted(URL[] urls) {
        return translate(urls, locations.mounted());
    }

    public static URL[] visible(URLClassLoader owner, URL[] urls) {
        var paths = locations;
        if (owner == paths.launch()) urls = paths.launch().getSources().toArray(URL[]::new);
        return translate(urls, paths.originals());
    }

    private static URL[] translate(URL[] urls, Map<String, URL> map) {
        if (urls == null) return null;
        URL[] result = urls;
        for (int i = 0; i < urls.length; i++) {
            URL url = urls[i];
            URL mounted = url == null ? null : map.getOrDefault(url.toExternalForm(), url);
            if (mounted == urls[i]) continue;
            if (result == urls) result = urls.clone();
            result[i] = mounted;
        }
        return result;
    }

    public static URL mounted(URL url) {
        return url == null ? null : locations.mounted().getOrDefault(url.toExternalForm(), url);
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
                if (call.name.equals("getURLs") && call.desc.equals("()" + ARRAY)) {
                    method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
                    method.instructions.insert(call, visibleCall());
                    changed = true;
                    continue;
                }
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
        if (needsPublicView(node)) {
            var view = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "getURLs", "()" + ARRAY, null, null);
            view.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            view.instructions.add(new InsnNode(Opcodes.DUP));
            view.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LOADER, "getURLs", "()" + ARRAY, false));
            view.instructions.add(visibleCall());
            view.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(view);
            changed = true;
        }
        if (!changed) return bytes;
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean needsPublicView(ClassNode node) {
        // URLClassLoader 没有实现 Serializable。带接口的子类可能通过接口继承它。
        if (!LOADER.equals(node.superName) || !node.interfaces.isEmpty()) return false;
        boolean definesClasses = false;
        for (var method : node.methods) {
            if (method.name.equals("getURLs") && method.desc.equals("()" + ARRAY)) return false;
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) continue;
            if (method.name.equals("loadClass") && (method.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")
                    || method.desc.equals("(Ljava/lang/String;Z)Ljava/lang/Class;"))
                    || method.name.equals("findClass") && method.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                definesClasses = true;
            }
        }
        return definesClasses;
    }

    private static MethodInsnNode visibleCall() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "visible", "(Ljava/net/URLClassLoader;" + ARRAY + ")" + ARRAY, false);
    }
}
