package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.mapper.ClassAliasingMapper;
import github.kasuminova.ssoptimizer.api.save.SaveLambdaFactory;
import github.kasuminova.ssoptimizer.api.save.SaveTypeRegistration;

import java.io.Serializable;
import java.util.List;

public final class SaveTypeRegistrationImpl implements SaveTypeRegistration {
    @Override
    public void registerTypes(final Object serializer, final Class<?>... types) {
        aliases(serializer).registerTypes(types);
    }

    @Override
    public void registerLambda(final Object serializer, final Serializable prototype, final Class<?> capturingClass,
                               final Class<?> implementationClass, final Class<?> functionalInterface,
                               final List<Class<?>> capturedTypes, final SaveLambdaFactory factory) {
        final SaveAliases aliases = aliases(serializer);
        aliases.lambdas().register(prototype, capturingClass, implementationClass, functionalInterface, capturedTypes, factory);
        aliases.registerTypes(capturedTypes.toArray(Class<?>[]::new));
        aliases.registerTypes(functionalInterface);
    }

    private static SaveAliases aliases(final Object serializer) {
        if (!(serializer instanceof XStream stream)) {
            throw new IllegalArgumentException("serializer must be the XStream instance supplied to configureXStream");
        }
        final Object mapper = stream.getMapper().lookupMapperOfType(ClassAliasingMapper.class);
        if (!(mapper instanceof SaveAliasAccess access)) {
            throw new IllegalStateException("The standalone XStream compatibility configuration is not active");
        }
        return access.ssoptimizer$saveAliases();
    }
}
