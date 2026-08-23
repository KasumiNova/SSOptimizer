package github.kasuminova.ssoptimizer.asm.loading;

import java.util.Map;
import java.util.Set;

/**
 * 目标类：{@code particleengine/ParticleAllocator} 与
 * {@code particleengine/EmitterBufferHandler}（Particle Engine 模组）。<br>
 * 注入位置：两类的 glBufferData(int,FloatBuffer,int)（GL_ARRAY_BUFFER 粒子实例 VBO 池，
 * 4096 步长 DYNAMIC_DRAW）→ vbo 分类转发钩子。<br>
 * 注入动机：GL 显存账本 vbo 分类——粒子引擎的固定步长 VBO 池（静态扫描确认；
 * {@code Particles.fillUniformBuffer} 的 glBufferSubData 不产生新分配，不埋点）。<br>
 * 计量口径：{@code data.remaining() × 4} 字节；buffer id 取分配时当前绑定
 * （调用点前必有 glBindBuffer，javap 已核实）；allocateParticles 的池扩容
 * 重分配按 id 替换计。<br>
 * 删除对称性：两类均无 glDeleteBuffers 路径，<b>只计分配峰值</b>
 * （粒子池全局长存，峰值即实况）。<br>
 * <p>
 * 为什么不用 Mixin：见 {@link GlAllocRedirectProcessor} 类 javadoc。
 */
public final class ParticleEngineVboLedgerProcessor extends GlAllocRedirectProcessor {

    public static final String TARGET_CLASS_ALLOCATOR = "particleengine/ParticleAllocator";
    public static final String TARGET_CLASS_EMITTER = "particleengine/EmitterBufferHandler";

    private static final Set<String> TARGETS = Set.of(TARGET_CLASS_ALLOCATOR,
            TARGET_CLASS_EMITTER);
    private static final Map<String, String> REDIRECTS = Map.of(
            "org/lwjgl/opengl/GL15.glBufferData(ILjava/nio/FloatBuffer;I)V", "vboBufferData");

    @Override
    protected Set<String> targetClasses() {
        return TARGETS;
    }

    @Override
    protected Map<String, String> redirects() {
        return REDIRECTS;
    }
}
