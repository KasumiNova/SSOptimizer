package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.BytecodeRemapper;
import github.kasuminova.ssoptimizer.mapping.MappingDirection;
import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;

import java.io.InputStream;

/**
 * 运行期字节码视图测试 fixture。
 * <p>
 * named-game-jars 全量改名（构建期全量表）后，classpath 上的 named jar 不再保留
 * 混淆成员名，不能再作为"运行期视图"的字节码来源。本 fixture 从测试 classpath 上的
 * 入库 vendor jar（obf 视图）读取原始类字节码，并按需用人工映射表（运行期权威表）
 * 换算出运行期 named 视图——与 agent 运行时 {@code RuntimeRemapTransformer} 的输入输出严格一致：
 * <ul>
 *     <li>{@link #readObfuscatedBytes}：游戏类加载进 JVM 时的原始 obf 字节码；</li>
 *     <li>{@link #readRuntimeNamedBytes}：运行期 remap 后的 named 视图字节码（ASM 处理器工作视图）。</li>
 * </ul>
 */
final class RuntimeViewFixtures {
    private static final TinyV2MappingRepository REPOSITORY = TinyV2MappingRepository.loadDefault();
    private static final BytecodeRemapper OBFUSCATED_TO_NAMED = new BytecodeRemapper(
            REPOSITORY, MappingDirection.OBFUSCATED_TO_NAMED);

    private RuntimeViewFixtures() {
    }

    /**
     * 把 named 或 obf 内部名解析为运行时（obf）类名。
     * 人工表未覆盖的类（identity 类、第三方类）原样返回。
     *
     * @param internalName named 或 obf 内部名
     * @return 运行时类名
     */
    static String runtimeClassName(String internalName) {
        MappingEntry classEntry = REPOSITORY.findClassByNamedName(internalName).orElse(null);
        return classEntry != null ? classEntry.obfuscatedName() : internalName;
    }

    /**
     * 读取原始 obf 类字节码。
     *
     * @param internalName named 或 obf 内部名
     * @return 类字节码；classpath 上不存在时返回 {@code null}
     */
    static byte[] readObfuscatedBytes(String internalName) {
        String resourcePath = runtimeClassName(internalName) + ".class";
        try (InputStream input = RuntimeViewFixtures.class.getClassLoader().getResourceAsStream(resourcePath)) {
            return input != null ? input.readAllBytes() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 读取运行期 named 视图类字节码（obf 字节码经人工表 remap）。
     *
     * @param internalName named 或 obf 内部名
     * @return named 视图字节码；classpath 上不存在时返回 {@code null}
     */
    static byte[] readRuntimeNamedBytes(String internalName) {
        byte[] obfuscatedBytes = readObfuscatedBytes(internalName);
        return obfuscatedBytes == null ? null : OBFUSCATED_TO_NAMED.remapClass(obfuscatedBytes).bytecode();
    }
}
