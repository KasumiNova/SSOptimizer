package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.mapper.Mapper;
import com.thoughtworks.xstream.mapper.MapperWrapper;
import github.kasuminova.ssoptimizer.common.save.XStreamFieldAliasingCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * XStream 字段别名链查询加速 Mixin。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.mapper.FieldAliasingMapper}<br>
 * 注入动机：热点报告显示 {@code serializedMember/realMember} 会对同一字段组合反复沿继承层级执行
 * {@code getMember(...)} 查找，并在 miss 时继续落回后续 mapper 链；在大对象图保存中，这部分属于高频纯查询开销。<br>
 * 注入效果：为每个 mapper 实例增加最终解析结果缓存，在字段别名配置未变化时直接复用结果，降低 HashMap 查询与继承层遍历成本。
 */
@Mixin(targets = "com.thoughtworks.xstream.mapper.FieldAliasingMapper")
public abstract class XStreamFieldAliasingMapperMixin extends MapperWrapper {
    @Shadow(remap = false)
    protected Map<?, ?> fieldToAliasMap;

    @Shadow(remap = false)
    protected Map<?, ?> aliasToFieldMap;

    @Unique
    private final XStreamFieldAliasingCache ssoptimizer$aliasingCache = new XStreamFieldAliasingCache();

    protected XStreamFieldAliasingMapperMixin(final Mapper wrapped) {
        super(wrapped);
    }

    @Invoker("getMember")
    protected abstract String ssoptimizer$invokeGetMember(Class<?> type, String memberName, Map<?, ?> map);

    /**
     * 解析字段的序列化名称。
     *
     * @param type       声明类型
     * @param memberName 原始字段名
     * @return 最终序列化字段名
     * @author GitHub Copilot
     * @reason 缓存最终解析结果，避免对同一字段重复执行本地别名查找与后续 mapper fallback。
     */
    @Overwrite(remap = false)
    public String serializedMember(final Class type, final String memberName) {
        return ssoptimizer$aliasingCache.getOrResolveSerializedMember(type, memberName, () -> {
            final String alias = ssoptimizer$invokeGetMember(type, memberName, fieldToAliasMap);
            return alias != null ? alias : super.serializedMember(type, memberName);
        });
    }

    /**
     * 解析序列化字段名对应的真实字段名。
     *
     * @param type       声明类型
     * @param memberName 序列化字段名
     * @return 真实字段名
     * @author GitHub Copilot
     * @reason 缓存最终解析结果，避免对同一字段重复执行本地反向别名查找与后续 mapper fallback。
     */
    @Overwrite(remap = false)
    public String realMember(final Class type, final String memberName) {
        return ssoptimizer$aliasingCache.getOrResolveRealMember(type, memberName, () -> {
            final String alias = ssoptimizer$invokeGetMember(type, memberName, aliasToFieldMap);
            return alias != null ? alias : super.realMember(type, memberName);
        });
    }

    @Inject(method = "addFieldAlias", at = @At("HEAD"), remap = false)
    private void ssoptimizer$clearAliasingCacheOnMutation(final String alias,
                                                          final Class<?> definedIn,
                                                          final String fieldName,
                                                          final CallbackInfo callbackInfo) {
        ssoptimizer$aliasingCache.clear();
    }
}
