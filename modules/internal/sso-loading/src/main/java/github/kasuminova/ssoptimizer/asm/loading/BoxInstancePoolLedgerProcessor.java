package github.kasuminova.ssoptimizer.asm.loading;

import java.util.Map;
import java.util.Set;

/**
 * 目标类：{@code org/boxutil/backends/core/BUtil_InstanceDataMemoryPool}
 * （BoxUtil 模组 backends/BoxUtilImpl.jar）。<br>
 * 注入位置：{@code initSSBO}/{@code expandSSBO}/{@code copyRangeSSBO}/{@code _compact}
 * 的 glBufferData(int,long,int) 与 glBufferStorage(int,long,int)（GL_SHADER_STORAGE_BUFFER
 * 实例数据池，随战场实例数扩容）→ vbo 分类转发钩子；同类内的 glDeleteBuffers
 * → 对称减量。<br>
 * 注入动机：GL 显存账本 vbo 分类的最大模组来源（静态扫描确认，DYNAMIC_DRAW SSBO
 * 池 + GL44 不可变存储双路径）。<br>
 * 计量口径：size 实参直接入账；buffer id 取分配时当前绑定（调用点前必有
 * glBindBuffer，javap 已核实）；同 id 重分配按替换计（GL 语义即旧存储释放）。<br>
 * 删除对称性：类内存在 glDeleteBuffers（销毁/压缩路径），已对称挂 remove。<br>
 * <p>
 * 为什么不用 Mixin：见 {@link GlAllocRedirectProcessor} 类 javadoc。
 */
public final class BoxInstancePoolLedgerProcessor extends GlAllocRedirectProcessor {

    public static final String TARGET_CLASS =
            "org/boxutil/backends/core/BUtil_InstanceDataMemoryPool";

    private static final Set<String> TARGETS = Set.of(TARGET_CLASS);
    private static final Map<String, String> REDIRECTS = Map.of(
            "org/lwjgl/opengl/GL15.glBufferData(IJI)V", "vboBufferData",
            "org/lwjgl/opengl/GL44.glBufferStorage(IJI)V", "vboBufferStorage",
            "org/lwjgl/opengl/GL15.glDeleteBuffers(I)V", "vboDeleteBuffer");

    @Override
    protected Set<String> targetClasses() {
        return TARGETS;
    }

    @Override
    protected Map<String, String> redirects() {
        return REDIRECTS;
    }
}
