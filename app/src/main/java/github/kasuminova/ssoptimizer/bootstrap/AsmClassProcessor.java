package github.kasuminova.ssoptimizer.bootstrap;

@FunctionalInterface
public interface AsmClassProcessor {
    byte[] process(byte[] classfileBuffer);
}