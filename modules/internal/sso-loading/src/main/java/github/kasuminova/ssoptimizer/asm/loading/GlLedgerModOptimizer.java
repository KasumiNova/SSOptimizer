package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.api.ExternalModOptimizer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GL 显存分类账本的模组埋点集合（ASM 处理器）。
 * <p>
 * 动机：账本钩子类 {@code GlLedgerHooks} 只计数不织入；第三方模组类在本环境下
 * 无法作为 Mixin 目标（Mixin 0.8.7 在模组 jar 挂载前一次性 prepare config，
 * 目标字节不可得即永久失效——运行时实测五个目标全部
 * {@code @Mixin target ... was not found}），织入只能走 ASM 处理器，
 * 各处理器的注入位置/动机/计量口径见各自 javadoc。
 * <p>
 * 覆盖：第一批（GraphicsLib ShaderLib/LightShader + BoxUtil ShaderCore/
 * BUtil_RenderingBuffer/PublicFBO）方法级注入；第二批（静态扫描驱动的
 * upTex/screenRT/vbo 三分类）走 {@link GlAllocRedirectProcessor} 调用点重定向——
 * BoxUtil TextureManager/LegacyNormalMapHelper（upTex）、Moci/No101/ASTD/
 * BoxConfigGUI（screenRT）、BUtil_InstanceDataMemoryPool/ParticleEngine（vbo）。
 * 游戏类 SpriteBatch 的 vbo 埋点是 Mixin（loading.SpriteBatchLedgerMixin），
 * 不在本集合内。
 * <p>
 * 集合落在 sso-loading 而非 sso-modopt：钩子类在 sso-loading，同模块可编译期
 * 核对钩子签名（sso-modopt 不允许依赖 sso-loading）。
 * <p>
 * 总开关：{@code -Dssoptimizer.disable.glledger=true}（由调用方按 {@link #featureKey()}
 * 判断）。目标类互不相同，无需 {@code CompositeAsmClassProcessor}。
 */
public final class GlLedgerModOptimizer implements ExternalModOptimizer {

    /** 功能键，对应 {@code -Dssoptimizer.disable.glledger} 总开关。 */
    public static final String FEATURE_KEY = "glledger";

    @Override
    public String featureKey() {
        return FEATURE_KEY;
    }

    @Override
    public Map<String, AsmClassProcessor> processors() {
        final Map<String, AsmClassProcessor> processors = new LinkedHashMap<>();
        processors.put(ShaderLibLedgerProcessor.TARGET_CLASS, new ShaderLibLedgerProcessor());
        processors.put(LightShaderLedgerProcessor.TARGET_CLASS, new LightShaderLedgerProcessor());
        processors.put(BoxShaderCoreLedgerProcessor.TARGET_CLASS, new BoxShaderCoreLedgerProcessor());
        processors.put(BoxRenderingBufferLedgerProcessor.TARGET_CLASS,
                new BoxRenderingBufferLedgerProcessor());
        processors.put(PublicFboLedgerProcessor.TARGET_CLASS, new PublicFboLedgerProcessor());
        // upTex：模组直传贴图
        processors.put(BoxTextureUploadLedgerProcessor.TARGET_CLASS,
                new BoxTextureUploadLedgerProcessor());
        processors.put(BoxLegacyNormalMapLedgerProcessor.TARGET_CLASS,
                new BoxLegacyNormalMapLedgerProcessor());
        // screenRT：屏幕尺寸 RT 旁路
        processors.put(MociSingularityLedgerProcessor.TARGET_CLASS,
                new MociSingularityLedgerProcessor());
        processors.put(No101SingularityLedgerProcessor.TARGET_CLASS,
                new No101SingularityLedgerProcessor());
        processors.put(AstdTexTrailLedgerProcessor.TARGET_CLASS, new AstdTexTrailLedgerProcessor());
        processors.put(BoxConfigGuiLedgerProcessor.TARGET_CLASS, new BoxConfigGuiLedgerProcessor());
        // vbo：缓冲对象
        processors.put(BoxInstancePoolLedgerProcessor.TARGET_CLASS,
                new BoxInstancePoolLedgerProcessor());
        final ParticleEngineVboLedgerProcessor particleVbo = new ParticleEngineVboLedgerProcessor();
        processors.put(ParticleEngineVboLedgerProcessor.TARGET_CLASS_ALLOCATOR, particleVbo);
        processors.put(ParticleEngineVboLedgerProcessor.TARGET_CLASS_EMITTER, particleVbo);
        return processors;
    }
}
