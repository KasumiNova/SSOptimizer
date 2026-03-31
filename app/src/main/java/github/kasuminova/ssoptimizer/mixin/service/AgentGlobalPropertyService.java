package github.kasuminova.ssoptimizer.mixin.service;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory global property service backed by a {@link ConcurrentHashMap}.
 */
public final class AgentGlobalPropertyService implements IGlobalPropertyService {

    private final ConcurrentHashMap<String, Object> properties = new ConcurrentHashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        return new StringPropertyKey(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return (T) properties.get(((StringPropertyKey) key).name);
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        if (value == null) {
            properties.remove(((StringPropertyKey) key).name);
        } else {
            properties.put(((StringPropertyKey) key).name, value);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        return (T) properties.getOrDefault(((StringPropertyKey) key).name, defaultValue);
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object val = properties.get(((StringPropertyKey) key).name);
        return val != null ? val.toString() : defaultValue;
    }

    private record StringPropertyKey(String name) implements IPropertyKey {
    }
}
