package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.api.ExternalModOptimizer;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DcrModOptimizer implements ExternalModOptimizer {

    public static final String FEATURE_KEY = "dcr";

    @Override
    public String featureKey() {
        return FEATURE_KEY;
    }

    @Override
    public Map<String, AsmClassProcessor> processors() {
        final Map<String, AsmClassProcessor> processors = new LinkedHashMap<>();
        processors.put(DcrBatchSaveSynthProcessor.TARGET_CLASS, new DcrBatchSaveSynthProcessor());
        processors.put(DcrOnGameLoadProcessor.TARGET_CLASS, new DcrOnGameLoadProcessor());
        return processors;
    }
}
