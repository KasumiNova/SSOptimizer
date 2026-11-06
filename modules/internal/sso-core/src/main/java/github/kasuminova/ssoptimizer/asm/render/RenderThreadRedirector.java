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
 * 将 Launch 域类中的 LWJGL GL 调用重定向到渲染命令桥。
 * 调用方包含开放的游戏、模组与动态编译类集合，Mixin 无法逐类声明，故使用 ASM。
 * 仅改写桥已镜像的方法；GLSync、Drawable、SharedDrawable 的声明、调用描述符与帧类型必须同步替换，
 * System 域声明保持不变。invokedynamic 站点必须整体改写或整体保留，避免链接期类型不一致。
 */
public final class RenderThreadRedirector {
    private static final Logger LOGGER = Logger.getLogger(RenderThreadRedirector.class);

    private static final String LWJGL_PREFIX = "org/lwjgl/opengl/";
    private static final byte[] LWJGL_PREFIX_BYTES = LWJGL_PREFIX.getBytes(StandardCharsets.UTF_8);
    private static final String GLU_SPHERE_OWNER = "org/lwjgl/util/glu/Sphere";
    private static final byte[] GLU_SPHERE_OWNER_BYTES = GLU_SPHERE_OWNER.getBytes(StandardCharsets.UTF_8);
    private static final String GLU_SPHERE_DRAW_NAME = "draw";
    private static final String GLU_SPHERE_DRAW_DESC = "(FII)V";
    private static final String GLU_SUPPORT_OWNER =
            "github/kasuminova/ssoptimizer/bridge/opengl/GluSupport";
    private static final String GLU_SUPPORT_DRAW_DESC = "(Lorg/lwjgl/util/glu/Sphere;FII)V";
    private static final String BRIDGE_PREFIX = "github/kasuminova/ssoptimizer/bridge/opengl/";
    private static final String BRIDGE_PACKAGE = "github/kasuminova/ssoptimizer/bridge/";
    private static final String SYSTEM_LWJGL_PREFIX = "org/lwjgl/";

    static final String[] MIRRORED_CLASS_NAMES = {
            "AMDDebugOutput", "AMDDrawBuffersBlend", "AMDInterleavedElements", "AMDMultiDrawIndirect", "AMDNameGenDelete", "AMDPerformanceMonitor",
            "AMDSamplePositions", "AMDSparseTexture", "AMDStencilOperationExtended", "AMDVertexShaderTessellator", "APPLEElementArray", "APPLEFence",
            "APPLEFlushBufferRange", "APPLEObjectPurgeable", "APPLETextureRange", "APPLEVertexArrayObject", "APPLEVertexArrayRange", "APPLEVertexProgramEvaluators",
            "ARBBaseInstance", "ARBBindlessTexture", "ARBBlendFuncExtended", "ARBBufferObject", "ARBBufferStorage", "ARBCLEvent",
            "ARBClearBufferObject", "ARBClearTexture", "ARBClipControl", "ARBColorBufferFloat", "ARBComputeShader", "ARBComputeVariableGroupSize",
            "ARBCopyBuffer", "ARBCopyImage", "ARBDebugOutput", "ARBDirectStateAccess", "ARBDrawBuffers", "ARBDrawBuffersBlend",
            "ARBDrawElementsBaseVertex", "ARBDrawIndirect", "ARBDrawInstanced", "ARBES2Compatibility", "ARBES31Compatibility", "ARBFragmentProgram",
            "ARBFramebufferNoAttachments", "ARBFramebufferObject", "ARBGeometryShader4", "ARBGetProgramBinary", "ARBGetTextureSubImage", "ARBGpuShaderFp64",
            "ARBImaging", "ARBIndirectParameters", "ARBInstancedArrays", "ARBInternalformatQuery", "ARBInternalformatQuery2", "ARBInvalidateSubdata",
            "ARBMapBufferRange", "ARBMatrixPalette", "ARBMultiBind", "ARBMultiDrawIndirect", "ARBMultisample", "ARBMultitexture",
            "ARBOcclusionQuery", "ARBPixelBufferObject", "ARBPointParameters", "ARBProgram", "ARBProgramInterfaceQuery", "ARBProvokingVertex",
            "ARBRobustness", "ARBSampleShading", "ARBSamplerObjects", "ARBSeparateShaderObjects", "ARBShaderAtomicCounters", "ARBShaderImageLoadStore",
            "ARBShaderObjects", "ARBShaderStorageBufferObject", "ARBShaderSubroutine", "ARBShadingLanguageInclude", "ARBSparseBuffer", "ARBSparseTexture",
            "ARBSync", "ARBTessellationShader", "ARBTextureBarrier", "ARBTextureBufferObject", "ARBTextureBufferRange", "ARBTextureCompression",
            "ARBTextureMultisample", "ARBTextureStorage", "ARBTextureStorageMultisample", "ARBTextureView", "ARBTimerQuery", "ARBTransformFeedback2",
            "ARBTransformFeedback3", "ARBTransformFeedbackInstanced", "ARBTransposeMatrix", "ARBUniformBufferObject", "ARBVertexArrayObject", "ARBVertexAttrib64bit",
            "ARBVertexAttribBinding", "ARBVertexBlend", "ARBVertexBufferObject", "ARBVertexProgram", "ARBVertexShader", "ARBVertexType2_10_10_10_REV",
            "ARBViewportArray", "ARBWindowPos", "ATIDrawBuffers", "ATIElementArray", "ATIEnvmapBumpmap", "ATIFragmentShader",
            "ATIMapObjectBuffer", "ATIPnTriangles", "ATISeparateStencil", "ATIVertexArrayObject", "ATIVertexAttribArrayObject", "ATIVertexStreams",
            "Display", "Drawable", "EXTBindableUniform", "EXTBlendColor", "EXTBlendEquationSeparate", "EXTBlendFuncSeparate",
            "EXTBlendMinmax", "EXTCompiledVertexArray", "EXTDepthBoundsTest", "EXTDrawBuffers2", "EXTDrawInstanced", "EXTDrawRangeElements",
            "EXTFogCoord", "EXTFramebufferBlit", "EXTFramebufferMultisample", "EXTFramebufferObject", "EXTGeometryShader4", "EXTGpuProgramParameters",
            "EXTGpuShader4", "EXTMultiDrawArrays", "EXTPalettedTexture", "EXTPixelBufferObject", "EXTPointParameters", "EXTProvokingVertex",
            "EXTSecondaryColor", "EXTSeparateShaderObjects", "EXTShaderImageLoadStore", "EXTStencilClearTag", "EXTStencilTwoSide", "EXTTextureArray",
            "EXTTextureBufferObject", "EXTTextureInteger", "EXTTimerQuery", "EXTTransformFeedback", "EXTVertexAttrib64bit", "EXTVertexShader",
            "EXTVertexWeighting", "GL11", "GL12", "GL13", "GL14", "GL15",
            "GL20", "GL21", "GL30", "GL31", "GL32", "GL33",
            "GL40", "GL41", "GL42", "GL43", "GL44", "GL45",
            "GLContext", "GLSync", "GREMEDYFrameTerminator", "GREMEDYStringMarker", "INTELMapTexture", "KHRDebug",
            "KHRRobustness", "NVBindlessMultiDrawIndirect", "NVBindlessTexture", "NVBlendEquationAdvanced", "NVConditionalRender", "NVCopyImage",
            "NVDepthBufferFloat", "NVDrawTexture", "NVEvaluators", "NVExplicitMultisample", "NVFence", "NVFragmentProgram",
            "NVFramebufferMultisampleCoverage", "NVGeometryProgram4", "NVGpuProgram4", "NVGpuShader5", "NVHalfFloat", "NVOcclusionQuery",
            "NVParameterBufferObject", "NVPathRendering", "NVPixelDataRange", "NVPointSprite", "NVPresentVideo", "NVPrimitiveRestart",
            "NVProgram", "NVRegisterCombiners", "NVRegisterCombiners2", "NVShaderBufferLoad", "NVTextureBarrier", "NVTextureMultisample",
            "NVTransformFeedback", "NVTransformFeedback2", "NVVertexArrayRange", "NVVertexAttribInteger64bit", "NVVertexBufferUnifiedMemory", "NVVertexProgram",
            "NVVideoCapture", "SharedDrawable", "Util"
    };

    private static final Map<String, String> OWNER_REMAP = new HashMap<>();
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

    private static volatile Map<String, Set<String>> mirrorTable;

    private static final Set<String> WARNED_UNMIRRORED = ConcurrentHashMap.newKeySet();

    private RenderThreadRedirector() {
    }

    /** 直接读取开关常量，避免变换 {@code RenderThreadMode} 自身时触发其类加载。 */
    public static byte[] redirect(final String internalClassName, final byte[] classBytes) {
        if ("false".equalsIgnoreCase(System.getProperty(RenderThreadMode.ENABLE_PROPERTY, "true"))
                || classBytes == null) {
            return classBytes;
        }
        if (internalClassName != null && isExcluded(internalClassName.replace('.', '/'))) {
            return classBytes;
        }
        if (!containsRedirectableReference(classBytes)) {
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

    static boolean isExcluded(final String internalClassName) {
        return internalClassName.startsWith(LWJGL_PREFIX)
                || internalClassName.startsWith(BRIDGE_PACKAGE)
                || internalClassName.startsWith("github/kasuminova/ssoptimizer/bootstrap/")
                || internalClassName.equals("github/kasuminova/ssoptimizer/asm/render/RenderThreadRedirector");
    }

    private static boolean containsRedirectableReference(final byte[] bytes) {
        return containsBytes(bytes, LWJGL_PREFIX_BYTES) || containsBytes(bytes, GLU_SPHERE_OWNER_BYTES);
    }

    private static boolean containsBytes(final byte[] bytes, final byte[] needle) {
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

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

    /** 镜像表必须包含 bridge 继承链上的全部方法，链上资源缺失时直接报错。 */
    private static Map<String, Set<String>> buildMirrorTable() {
        Map<String, Set<String>> table = new HashMap<>();
        ClassLoader loader = RenderThreadRedirector.class.getClassLoader();
        for (String simpleName : MIRRORED_CLASS_NAMES) {
            Set<String> methods = new HashSet<>();
            String internalName = BRIDGE_PREFIX + simpleName;
            while (internalName != null && internalName.startsWith(BRIDGE_PREFIX)) {
                String resource = internalName + ".class";
                final Set<String> collected = methods;
                final String[] superName = new String[1];
                try (InputStream in = loader.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IllegalStateException("[SSOptimizer] bridge 类资源缺失: " + resource);
                    }
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9, null) {
                        @Override
                        public void visit(int version, int access, String name, String signature,
                                          String superNameArg, String[] interfaces) {
                            superName[0] = superNameArg;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc,
                                                         String signature, String[] exceptions) {
                            collected.add(name + desc);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (IOException e) {
                    throw new IllegalStateException("[SSOptimizer] 读取 bridge 类资源失败: " + resource, e);
                }
                internalName = superName[0];
            }
            table.put(simpleName, methods);
        }
        return table;
    }

    private static void warnUnmirrored(final String className, final String owner,
                                       final String name, final String desc) {
        if (WARNED_UNMIRRORED.add(owner + '.' + name + desc)) {
            LOGGER.warn("[SSOptimizer] GL 调用未镜像，分离模式下将在无 context 的调用线程执行真实 GL"
                    + "（崩溃风险/覆盖面缺口）：" + owner + '.' + name + desc + "（首见于 " + className + "）");
        }
    }

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
            String remappedSignature = signature == null ? null : remapDescriptor(signature);
            String remappedSuper = superName == null ? null : remapType(superName);
            String[] remappedInterfaces = interfaces;
            for (int i = 0; i < interfaces.length; i++) {
                String remapped = remapType(interfaces[i]);
                if (!remapped.equals(interfaces[i])) {
                    if (remappedInterfaces == interfaces) {
                        remappedInterfaces = interfaces.clone();
                    }
                    remappedInterfaces[i] = remapped;
                }
            }
            if ((remappedSignature != null && !remappedSignature.equals(signature))
                    || (remappedSuper != null && !remappedSuper.equals(superName))
                    || remappedInterfaces != interfaces) {
                modified = true;
            }
            super.visit(version, access, name, remappedSignature, remappedSuper, remappedInterfaces);
        }

        @Override
        public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc,
                                                         String signature, Object value) {
            String remappedDesc = remapDescriptor(desc);
            String remappedSignature = signature == null ? null : remapDescriptor(signature);
            if (!remappedDesc.equals(desc)
                    || (remappedSignature != null && !remappedSignature.equals(signature))) {
                modified = true;
            }
            return super.visitField(access, name, remappedDesc, remappedSignature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            String remappedDesc = remapDescriptor(desc);
            String remappedSignature = signature == null ? null : remapDescriptor(signature);
            if (!remappedDesc.equals(desc)
                    || (remappedSignature != null && !remappedSignature.equals(signature))) {
                modified = true;
            }
            MethodVisitor delegate = super.visitMethod(access, name, remappedDesc,
                    remappedSignature, exceptions);
            return new RedirectMethodVisitor(delegate, className, this);
        }
    }

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
            // GLU Sphere 的整段绘制入队，System 域内部调用无法经 owner 改写覆盖。
            if (opcode == Opcodes.INVOKEVIRTUAL && GLU_SPHERE_OWNER.equals(ownerName)
                    && GLU_SPHERE_DRAW_NAME.equals(name) && GLU_SPHERE_DRAW_DESC.equals(desc)) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, GLU_SUPPORT_OWNER,
                        "enqueueSphereDraw", GLU_SUPPORT_DRAW_DESC, false);
                classVisitor.modified = true;
                return;
            }
            String bridgeOwner = OWNER_REMAP.get(ownerName);
            if (bridgeOwner == null) {
                // System 域 owner 的声明不改写，调用描述符保持原样。
                if (!ownerName.startsWith(SYSTEM_LWJGL_PREFIX)) {
                    String remappedModDesc = remapDescriptor(desc);
                    if (!remappedModDesc.equals(desc)) {
                        super.visitMethodInsn(opcode, ownerName, name, remappedModDesc, itf);
                        classVisitor.modified = true;
                        return;
                    }
                }
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
            // GL 字段访问的 owner 不改写，bridge 类未声明这些字段。
            if (OWNER_REMAP.containsKey(ownerName)) {
                warnUnmirrored(className, ownerName, name, desc);
                super.visitFieldInsn(opcode, ownerName, name, desc);
                return;
            }
            if (!ownerName.startsWith(SYSTEM_LWJGL_PREFIX)) {
                String remappedDesc = remapDescriptor(desc);
                if (!remappedDesc.equals(desc)) {
                    super.visitFieldInsn(opcode, ownerName, name, remappedDesc);
                    classVisitor.modified = true;
                    return;
                }
            }
            super.visitFieldInsn(opcode, ownerName, name, desc);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            // 帧中的身份类型名须与改写后的局部变量和操作数栈类型同步。
            super.visitFrame(type, numLocal, remapFrameEntries(local), numStack, remapFrameEntries(stack));
        }

        private Object[] remapFrameEntries(final Object[] entries) {
            if (entries == null) {
                return null;
            }
            Object[] remapped = entries;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i] instanceof String typeName) {
                    String mapped = remapType(typeName);
                    if (!mapped.equals(typeName)) {
                        if (remapped == entries) {
                            remapped = entries.clone();
                        }
                        remapped[i] = mapped;
                        classVisitor.modified = true;
                    }
                }
            }
            return remapped;
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
            // 字符串字面量不改写，仅替换对象身份类型的 Type 字面量。
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
            String remappedDesc = remapDescriptor(desc);
            // indy 描述符、Handle 与 Type 参数必须整站一致改写或保留。
            boolean keepWholeSite = false;
            for (Object arg : bsmArgs) {
                if (!(arg instanceof Handle handle)) {
                    continue;
                }
                String remappedHandleDesc = remapDescriptor(handle.getDesc());
                if (remappedHandleDesc.equals(handle.getDesc())) {
                    continue;
                }
                if (willRemapHandle(handle, remappedHandleDesc)) {
                    continue;
                }
                keepWholeSite = true;
                break;
            }
            Object[] remappedArgs = bsmArgs;
            for (int i = 0; i < bsmArgs.length; i++) {
                Object arg = bsmArgs[i];
                if (arg instanceof Type type) {
                    if (keepWholeSite) {
                        continue;
                    }
                    String remappedTypeDesc = remapDescriptor(type.getDescriptor());
                    if (!remappedTypeDesc.equals(type.getDescriptor())) {
                        if (remappedArgs == bsmArgs) {
                            remappedArgs = bsmArgs.clone();
                        }
                        remappedArgs[i] = Type.getType(remappedTypeDesc);
                    }
                } else if (arg instanceof Handle handle) {
                    String remappedHandleDesc = remapDescriptor(handle.getDesc());
                    String bridgeOwner = OWNER_REMAP.get(handle.getOwner());
                    if (bridgeOwner != null) {
                        // 未镜像 Handle 保留原 owner，避免链接到 bridge 类中不存在的方法。
                        Set<String> mirrored = mirrorTable.get(bridgeOwner.substring(BRIDGE_PREFIX.length()));
                        if (mirrored != null && mirrored.contains(handle.getName() + remappedHandleDesc)) {
                            if (remappedArgs == bsmArgs) {
                                remappedArgs = bsmArgs.clone();
                            }
                            remappedArgs[i] = new Handle(handle.getTag(), bridgeOwner, handle.getName(),
                                    remappedHandleDesc, handle.isInterface());
                        } else {
                            warnUnmirrored(className, handle.getOwner(), handle.getName(), handle.getDesc());
                        }
                    } else if (!handle.getOwner().startsWith(SYSTEM_LWJGL_PREFIX)
                            && !remappedHandleDesc.equals(handle.getDesc()) && !keepWholeSite) {
                        if (remappedArgs == bsmArgs) {
                            remappedArgs = bsmArgs.clone();
                        }
                        remappedArgs[i] = new Handle(handle.getTag(), handle.getOwner(), handle.getName(),
                                remappedHandleDesc, handle.isInterface());
                    }
                }
            }
            boolean changed = (!keepWholeSite && !remappedDesc.equals(desc)) || remappedArgs != bsmArgs;
            super.visitInvokeDynamicInsn(name, keepWholeSite ? desc : remappedDesc, bsm, remappedArgs);
            if (changed) {
                classVisitor.modified = true;
            }
        }

        /** 未镜像的 System 域 Handle 保留，其余 Handle 随声明同步改写。 */
        private boolean willRemapHandle(final Handle handle, final String remappedHandleDesc) {
            if (handle.getOwner().startsWith(SYSTEM_LWJGL_PREFIX)) {
                String bridgeOwner = OWNER_REMAP.get(handle.getOwner());
                if (bridgeOwner == null) {
                    return false;
                }
                Set<String> mirrored = mirrorTable.get(bridgeOwner.substring(BRIDGE_PREFIX.length()));
                return mirrored != null && mirrored.contains(handle.getName() + remappedHandleDesc);
            }
            return true;
        }
    }
}
