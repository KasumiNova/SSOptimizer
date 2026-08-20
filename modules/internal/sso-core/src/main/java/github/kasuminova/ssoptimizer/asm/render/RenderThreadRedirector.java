package github.kasuminova.ssoptimizer.asm.render;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渲染线程分离模式的 GL 调用 owner 重定向器（纯 ASM 改写逻辑，被 coremod transformer
 * 与 Janino 脚本字节码钩子共用）。
 * <p>
 * <b>为什么 Mixin 不可行</b>：本改写的目标是「加载进 LaunchClassLoader 的每一个游戏类、
 * 模组类、Janino 动态编译类」中对 {@code org.lwjgl.opengl.*} 静态入口的调用点——
 * 调用方集合是开放且不可枚举的（整个游戏 + 全部已启用模组），Mixin 要求逐目标类
 * 声明注入点，无法对「任意类的任意方法体内的 owner 引用」做全局重定向；这属于
 * 类加载期的横切字节码改写，只能落在 transformer 链上（项目规范允许的 ASM 兜底情形：
 * 跨类开放集的指令级 owner 改写）。
 * <p>
 * 改写规则：
 * <ul>
 *   <li>owner 改写表：{@code org/lwjgl/opengl/{GL11~GL15,GL20,GL30~GL32,
 *       GL40~GL44,ARBVertexBufferObject,EXTFramebufferObject,ARBFramebufferObject,
 *       ARBTextureStorage,ARBBindlessTexture,NVBindlessTexture,Display,GLContext,
 *       Drawable,SharedDrawable,GLSync,Util}} → bridge/opengl 同名类；</li>
 *   <li>只改写 bridge 实际镜像了的方法（镜像表在首次使用时解析 bridge 类自身字节码
 *       构建，方法名+描述符匹配）；未镜像的调用保持原 owner 并按调用签名首次
 *       记 warn 日志——未镜像调用在分离模式下会在无 context 的调用线程执行真实 GL，
 *       日志是覆盖面审计与崩溃定位的唯一依据；</li>
 *   <li>描述符类型改写仅限被改写的调用点：GLSync/Drawable/SharedDrawable 三类在
 *       bridge 化后对象身份不同，调用点描述符同步替换为 bridge 类型；类声明
 *       （字段/方法签名）不改写——跨声明持有这三类对象的模组（BoxUtil 级）会因
 *       类型不一致校验失败，属已声明的 v1 不兼容范围；</li>
 *   <li>visitLdcInsn 的字符串字面量绝不动；{@code Type} 型 class 字面量只在命中
 *       三个对象身份类型时改写；</li>
 *   <li>字段访问（GETSTATIC 等）不改写：javac 会把 GL 常量内联，运行期字段访问
 *       不存在于这些 owner 上；出现即 warn 保持原样（bridge 类不声明常量字段，
 *       改写 owner 会 NoSuchFieldError）。</li>
 * </ul>
 * 排除规则：
 * <ul>
 *   <li>{@code org/lwjgl/**} 自身（LWJGL 内部类互相调用必须保持真实 GL）；</li>
 *   <li>{@code github/kasuminova/ssoptimizer/bridge/**}（bridge 命令体必须调真 GL）；</li>
 *   <li>common/render/** 不豁免：本阶段不存在「在渲染线程直调真 GL 的 common/render
 *       类」（渲染线程执行的命令体全部位于 bridge 包内），各渲染助手在主线程录制，
 *       其 org.lwjgl 调用必须被重定向入队。</li>
 * </ul>
 * 字节码帧不重算（不用 {@code COMPUTE_FRAMES}）：owner/描述符改写不改变操作数栈形状，
 * 原始帧原样保留有效。
 * <p>
 * feature flag：{@link RenderThreadMode#ENABLE_PROPERTY} 显式 {@code =false} 时
 * {@link #redirect} 原样返回（零开销零风险），镜像表也不会构建；默认启用。
 */
public final class RenderThreadRedirector {
    private static final Logger LOGGER = Logger.getLogger(RenderThreadRedirector.class);

    /** LWJGL GL 包前缀（常量池 UTF8 预筛与 owner 判定共用）。 */
    private static final String LWJGL_PREFIX = "org/lwjgl/opengl/";
    private static final byte[] LWJGL_PREFIX_BYTES = LWJGL_PREFIX.getBytes(StandardCharsets.UTF_8);
    /** bridge GL 镜像包前缀（owner 改写目标）。 */
    private static final String BRIDGE_PREFIX = "github/kasuminova/ssoptimizer/bridge/opengl/";
    /** 排除规则前缀：bridge 包整体（命令体必须调真 GL）。 */
    private static final String BRIDGE_PACKAGE = "github/kasuminova/ssoptimizer/bridge/";

    /** owner 改写表覆盖的类名（org/lwjgl/opengl 下的简单名，与 bridge 类一一同名）。 */
    private static final String[] MIRRORED_CLASS_NAMES = {
            "GL11", "GL12", "GL13", "GL14", "GL15", "GL20", "GL30", "GL31", "GL32", "GL33",
            "GL40", "GL41", "GL42", "GL43", "GL44",
            "ARBVertexBufferObject", "EXTFramebufferObject", "ARBFramebufferObject",
            "ARBTextureStorage", "ARBBindlessTexture", "NVBindlessTexture",
            "ARBInstancedArrays", "ARBDrawInstanced",
            "Display", "GLContext", "Drawable", "SharedDrawable", "GLSync", "Util"
    };

    /** owner 改写表：org/lwjgl/opengl/X → bridge/opengl/X。 */
    private static final Map<String, String> OWNER_REMAP = new HashMap<>();
    /**
     * 描述符类型改写表：对象身份被 bridge 化的三类。只在被改写调用点的描述符
     * 上做字符串级替换（含数组形态，如 {@code [Lorg/lwjgl/opengl/GLSync;}）。
     */
    private static final Map<String, String> TYPE_REMAP = new HashMap<>();

    static {
        for (String simpleName : MIRRORED_CLASS_NAMES) {
            OWNER_REMAP.put(LWJGL_PREFIX + simpleName, BRIDGE_PREFIX + simpleName);
        }
        for (String simpleName : new String[]{"GLSync", "Drawable", "SharedDrawable"}) {
            TYPE_REMAP.put('L' + LWJGL_PREFIX + simpleName + ';',
                    'L' + BRIDGE_PREFIX + simpleName + ';');
        }
    }

    /** 镜像方法表：bridge 简单类名 → {@code name+desc} 集合（惰性构建，仅分离模式）。 */
    private static volatile Map<String, Set<String>> mirrorTable;

    /** 已告警的未镜像调用（owner.方法名+描述符），每处只 warn 一次。 */
    private static final Set<String> WARNED_UNMIRRORED = ConcurrentHashMap.newKeySet();

    private RenderThreadRedirector() {
    }

    /**
     * 对单个类的字节码执行 GL owner 重定向。
     * <p>
     * flag 检查刻意不经 {@link RenderThreadMode#isEnabled()} 方法调用：本方法在
     * transformer 链上执行，{@code ENABLE_PROPERTY} 是编译期内联的字符串常量，
     * 直接读系统属性不产生对 RenderThreadMode 的类引用——
     * 否则「正在改写 RenderThreadMode 类自身时触发其加载」会以
     * {@link ClassCircularityError} 收场（运行时已验证）。
     * 默认启用（RT 流水线已稳定），仅显式 {@code =false} 回退——判定式与
     * {@link RenderThreadMode#isEnabled()} 保持逐字一致。
     *
     * @param internalClassName 类名（点号或斜杠分隔均可；可为 {@code null}，
     *                          此时从字节码读取类名做排除判定）
     * @param classBytes        类文件字节
     * @return 改写后的字节码；flag 关闭 / 命中排除规则 / 无 LWJGL GL 引用 /
     *         无实际改写点时原样返回入参
     */
    public static byte[] redirect(final String internalClassName, final byte[] classBytes) {
        if ("false".equalsIgnoreCase(System.getProperty(RenderThreadMode.ENABLE_PROPERTY, "true"))
                || classBytes == null) {
            return classBytes;
        }
        if (internalClassName != null && isExcluded(internalClassName.replace('.', '/'))) {
            return classBytes;
        }
        if (!containsLwjglReference(classBytes)) {
            return classBytes;
        }

        ensureMirrorTable();

        ClassReader reader = new ClassReader(classBytes);
        if (internalClassName == null && isExcluded(reader.getClassName())) {
            return classBytes;
        }

        ClassWriter writer = new ClassWriter(reader, 0);
        RedirectClassVisitor visitor = new RedirectClassVisitor(writer);
        reader.accept(visitor, 0);
        return visitor.modified ? writer.toByteArray() : classBytes;
    }

    /**
     * 对 Janino {@code generateBytecodes} 的输出（类名 → 字节码）逐个重定向。
     * flag 关闭或无任何改写时返回原 map（调用方零适配）。
     *
     * @param bytecodes Janino 编译输出（key 为点号类名）
     * @return 重定向后的新 map，或原 map
     */
    public static Map<String, byte[]> redirectAll(final Map<String, byte[]> bytecodes) {
        if ("false".equalsIgnoreCase(System.getProperty(RenderThreadMode.ENABLE_PROPERTY, "true"))
                || bytecodes == null || bytecodes.isEmpty()) {
            return bytecodes;
        }
        Map<String, byte[]> result = new LinkedHashMap<>(bytecodes.size());
        boolean any = false;
        for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
            byte[] rewritten = redirect(entry.getKey(), entry.getValue());
            any |= rewritten != entry.getValue();
            result.put(entry.getKey(), rewritten);
        }
        return any ? result : bytecodes;
    }

    /** 排除规则：LWJGL 自身与 bridge 包（命令体必须调真 GL）。 */
    static boolean isExcluded(final String internalClassName) {
        return internalClassName.startsWith(LWJGL_PREFIX)
                || internalClassName.startsWith(BRIDGE_PACKAGE)
                // transformer/redirector 自身：避免对改写器实现类的无谓解析
                // （其常量池含改写表字符串，预筛必然命中）
                || internalClassName.startsWith("github/kasuminova/ssoptimizer/bootstrap/")
                || internalClassName.equals("github/kasuminova/ssoptimizer/asm/render/RenderThreadRedirector");
    }

    /** 常量池预筛：字节中不含 LWJGL GL 包名则不可能存在待改写引用。 */
    private static boolean containsLwjglReference(final byte[] bytes) {
        outer:
        for (int i = 0; i <= bytes.length - LWJGL_PREFIX_BYTES.length; i++) {
            for (int j = 0; j < LWJGL_PREFIX_BYTES.length; j++) {
                if (bytes[i + j] != LWJGL_PREFIX_BYTES[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** 内部名中的对象身份类型替换（GLSync/Drawable/SharedDrawable → bridge）。 */
    private static String remapType(final String internalName) {
        switch (internalName) {
            case "org/lwjgl/opengl/GLSync":
                return BRIDGE_PREFIX + "GLSync";
            case "org/lwjgl/opengl/Drawable":
                return BRIDGE_PREFIX + "Drawable";
            case "org/lwjgl/opengl/SharedDrawable":
                return BRIDGE_PREFIX + "SharedDrawable";
            default:
                return internalName;
        }
    }

    private static String remapDescriptor(final String desc) {
        String result = desc;
        for (Map.Entry<String, String> entry : TYPE_REMAP.entrySet()) {
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static void ensureMirrorTable() {
        if (mirrorTable == null) {
            synchronized (RenderThreadRedirector.class) {
                if (mirrorTable == null) {
                    mirrorTable = buildMirrorTable();
                    int total = 0;
                    for (Set<String> methods : mirrorTable.values()) {
                        total += methods.size();
                    }
                    LOGGER.info("[SSOptimizer] 渲染线程重定向镜像表就绪：" + total + " 个方法");
                }
            }
        }
    }

    /**
     * 解析 bridge 类自身字节码构建镜像方法表（方法名+描述符）。
     * 以 bridge 类为唯一事实源，bridge 扩面时本表自动跟随，无需维护静态清单。
     */
    private static Map<String, Set<String>> buildMirrorTable() {
        Map<String, Set<String>> table = new HashMap<>();
        ClassLoader loader = RenderThreadRedirector.class.getClassLoader();
        for (String simpleName : MIRRORED_CLASS_NAMES) {
            String resource = BRIDGE_PREFIX + simpleName + ".class";
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("[SSOptimizer] bridge 类资源缺失: " + resource);
                }
                Set<String> methods = new HashSet<>();
                new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9, null) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                                                     String signature, String[] exceptions) {
                        methods.add(name + desc);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                table.put(simpleName, methods);
            } catch (IOException e) {
                throw new IllegalStateException("[SSOptimizer] 读取 bridge 类资源失败: " + resource, e);
            }
        }
        return table;
    }

    /** 未镜像调用审计：保持原 owner，每个调用签名只 warn 一次。 */
    private static void warnUnmirrored(final String className, final String owner,
                                       final String name, final String desc) {
        if (WARNED_UNMIRRORED.add(owner + '.' + name + desc)) {
            LOGGER.warn("[SSOptimizer] GL 调用未镜像，分离模式下将在无 context 的调用线程执行真实 GL"
                    + "（崩溃风险/覆盖面缺口）：" + owner + '.' + name + desc + "（首见于 " + className + "）");
        }
    }

    /** 类级改写 visitor：只在方法体内做指令改写，类结构原样保留。 */
    private static final class RedirectClassVisitor extends ClassVisitor {
        private boolean modified;
        private String className;

        RedirectClassVisitor(final ClassWriter writer) {
            super(Opcodes.ASM9, writer);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.className = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
            return new RedirectMethodVisitor(delegate, className, this);
        }
    }

    /** 方法体指令改写 visitor：owner 改写 + 调用点描述符改写。 */
    private static final class RedirectMethodVisitor extends MethodVisitor {
        private final String className;
        private final RedirectClassVisitor classVisitor;

        RedirectMethodVisitor(final MethodVisitor delegate, final String className,
                              final RedirectClassVisitor classVisitor) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
            this.classVisitor = classVisitor;
        }

        @Override
        public void visitMethodInsn(int opcode, String ownerName, String name, String desc, boolean itf) {
            String bridgeOwner = OWNER_REMAP.get(ownerName);
            if (bridgeOwner == null) {
                super.visitMethodInsn(opcode, ownerName, name, desc, itf);
                return;
            }
            String remappedDesc = remapDescriptor(desc);
            Set<String> mirrored = mirrorTable.get(bridgeOwner.substring(BRIDGE_PREFIX.length()));
            if (mirrored != null && mirrored.contains(name + remappedDesc)) {
                super.visitMethodInsn(opcode, bridgeOwner, name, remappedDesc, itf);
                classVisitor.modified = true;
            } else {
                warnUnmirrored(className, ownerName, name, desc);
                super.visitMethodInsn(opcode, ownerName, name, desc, itf);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String ownerName, String name, String desc) {
            // 不改写：javac 内联 GL 常量，这些 owner 上不存在运行期字段访问；
            // bridge 类不声明常量字段，改写 owner 会 NoSuchFieldError。记审计日志后原样保留。
            if (OWNER_REMAP.containsKey(ownerName)) {
                warnUnmirrored(className, ownerName, name, desc);
            }
            super.visitFieldInsn(opcode, ownerName, name, desc);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            String remapped = remapType(type);
            if (!remapped.equals(type)) {
                super.visitTypeInsn(opcode, remapped);
                classVisitor.modified = true;
            } else {
                super.visitTypeInsn(opcode, type);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            // 字符串字面量绝不动；Type 字面量只在命中对象身份类型时改写
            if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                String remapped = remapType(type.getInternalName());
                if (!remapped.equals(type.getInternalName())) {
                    super.visitLdcInsn(Type.getObjectType(remapped));
                    classVisitor.modified = true;
                    return;
                }
            }
            super.visitLdcInsn(value);
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int numDimensions) {
            String remapped = remapDescriptor(desc);
            if (!remapped.equals(desc)) {
                super.visitMultiANewArrayInsn(remapped, numDimensions);
                classVisitor.modified = true;
            } else {
                super.visitMultiANewArrayInsn(desc, numDimensions);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
            // lambda/方法引用场景兜底：indy 描述符与 bootstrap 参数中的 Handle/Type
            // 同样可能引用被改写 owner（如 GL11::glBegin 方法引用）
            String remappedDesc = remapDescriptor(desc);
            Object[] remappedArgs = bsmArgs;
            for (int i = 0; i < bsmArgs.length; i++) {
                Object arg = bsmArgs[i];
                if (arg instanceof Type type && type.getSort() == Type.OBJECT) {
                    String remapped = remapType(type.getInternalName());
                    if (!remapped.equals(type.getInternalName())) {
                        if (remappedArgs == bsmArgs) {
                            remappedArgs = bsmArgs.clone();
                        }
                        remappedArgs[i] = Type.getObjectType(remapped);
                    }
                } else if (arg instanceof Handle handle) {
                    String bridgeOwner = OWNER_REMAP.get(handle.getOwner());
                    if (bridgeOwner != null) {
                        if (remappedArgs == bsmArgs) {
                            remappedArgs = bsmArgs.clone();
                        }
                        remappedArgs[i] = new Handle(handle.getTag(), bridgeOwner, handle.getName(),
                                remapDescriptor(handle.getDesc()), handle.isInterface());
                    }
                }
            }
            boolean changed = !remappedDesc.equals(desc) || remappedArgs != bsmArgs;
            super.visitInvokeDynamicInsn(name, remappedDesc, bsm, remappedArgs);
            if (changed) {
                classVisitor.modified = true;
            }
        }
    }
}
