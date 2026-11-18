package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.mapper.ClassAliasingMapper;
import com.thoughtworks.xstream.mapper.Mapper;
import com.thoughtworks.xstream.mapper.SecurityMapper;
import com.thoughtworks.xstream.security.NoTypePermission;
import github.kasuminova.ssoptimizer.mixin.compat.xstream.SaveSecurityAccessor;
import org.apache.log4j.Logger;

public final class SavePermissions {
    private static final Logger LOGGER = Logger.getLogger(SavePermissions.class);

    private SavePermissions() {
    }

    public static void rememberDefaults(final XStream stream) {
        final Object aliases = stream.getMapper().lookupMapperOfType(ClassAliasingMapper.class);
        final Object security = stream.getMapper().lookupMapperOfType(SecurityMapper.class);
        if (aliases instanceof SaveAliasAccess access && security instanceof SaveSecurityAccessor securityAccess) {
            access.ssoptimizer$saveAliases().rememberPermissions(securityAccess.ssoptimizer$getPermissions());
        }
    }

    public static void install(final XStream stream) {
        final ClassAliasingMapper mapper = (ClassAliasingMapper) stream.getMapper()
                .lookupMapperOfType(ClassAliasingMapper.class);
        if (!(mapper instanceof SaveAliasAccess access)) {
            throw new IllegalStateException("Campaign save compatibility requires the alias registration Mixin");
        }
        final SaveAliases aliases = access.ssoptimizer$saveAliases();
        final SecurityMapper security = (SecurityMapper) stream.getMapper().lookupMapperOfType(SecurityMapper.class);
        if (!(security instanceof SaveSecurityAccessor securityAccess)) {
            throw new IllegalStateException("Campaign save compatibility requires the security mapper Mixin");
        }
        synchronized (aliases) {
            if (aliases.permission() != null) {
                return;
            }
            final SaveTypePermission permission;
            try {
                permission = aliases.seal(aliases.restrictions(securityAccess.ssoptimizer$getPermissions()));
            } catch (IllegalStateException error) {
                LOGGER.error("[SSOptimizer] Campaign save permission configuration is unsupported", error);
                throw error;
            }
            stream.addPermission(NoTypePermission.NONE);
            stream.addPermission(type -> {
                permission.requireAllowed(type);
                return true;
            });
            stream.registerConverter(new RegisteredLambdaConverter(stream.getMapper(), aliases.lambdas()), XStream.PRIORITY_VERY_HIGH);
            LOGGER.info("[SSOptimizer] Campaign save permissions enabled for exact registered types and lambda bindings");
        }
    }

    public static void check(final Mapper mapper, final Class<?> type, final HierarchicalStreamReader reader) {
        final Mapper aliases = mapper.lookupMapperOfType(ClassAliasingMapper.class);
        if (!(aliases instanceof SaveAliasAccess access)) {
            return;
        }
        final SaveTypePermission permission = access.ssoptimizer$saveAliases().permission();
        if (permission == null) {
            return;
        }
        try {
            permission.requireAllowed(type);
        } catch (ConversionException error) {
            if (reader != null) {
                error.add("xml-node", reader.getNodeName());
                reader.appendErrors(error);
            }
            throw error;
        }
    }
}
