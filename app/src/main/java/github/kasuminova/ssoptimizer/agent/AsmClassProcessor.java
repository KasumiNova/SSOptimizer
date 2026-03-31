package github.kasuminova.ssoptimizer.agent;

@FunctionalInterface
public interface AsmClassProcessor {
    byte[] process(byte[] classfileBuffer);
}