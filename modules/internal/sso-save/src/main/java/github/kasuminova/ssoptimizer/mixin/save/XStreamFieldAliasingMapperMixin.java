package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.core.util.MemberStore;
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

@Mixin(targets = "com.thoughtworks.xstream.mapper.FieldAliasingMapper")
public abstract class XStreamFieldAliasingMapperMixin extends MapperWrapper {
    // miko 补丁版 XStream 的别名字段为 private MemberStore，@Shadow 必须匹配运行期签名。
    @Shadow(remap = false)
    private MemberStore fieldToAliasMap;

    @Shadow(remap = false)
    private MemberStore aliasToFieldMap;

    @Unique
    private final XStreamFieldAliasingCache ssoptimizer$aliasingCache = new XStreamFieldAliasingCache();

    protected XStreamFieldAliasingMapperMixin(final Mapper wrapped) {
        super(wrapped);
    }

    @Invoker("getMember")
    protected abstract String ssoptimizer$invokeGetMember(Class<?> type, String memberName, MemberStore map);

    @Overwrite(remap = false)
    public String serializedMember(final Class type, final String memberName) {
        return ssoptimizer$aliasingCache.getOrResolveSerializedMember(type, memberName, () -> {
            final String alias = ssoptimizer$invokeGetMember(type, memberName, fieldToAliasMap);
            return alias != null ? alias : super.serializedMember(type, memberName);
        });
    }

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
