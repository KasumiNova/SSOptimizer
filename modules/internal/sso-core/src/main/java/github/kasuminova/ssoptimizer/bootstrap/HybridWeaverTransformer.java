package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 混合织入变换器，NanoForge coremod 的 ASM 分发 transformer。
 * <p>
 * 经 {@code coremod.toml} 的 {@code [asm] transformers} 声明，由 LaunchWrapper 以无参构造
 * 实例化并注册进 LaunchClassLoader transformer 链（排在 NanoForge 的 patch / obf→named remap
 * 之后、Mixin 之前）。游戏类加载到这里时已是 named 字节码，故只做纯 named 类名匹配，
 * 不再有 javaagent 时代的二次 remap 双保险。
 * <p>
 * 注册表为静态：{@code SSOptimizerCorePlugin.onLoad} 在装配阶段写入（transformer 实例化
 * 早于 onLoad，但 {@link #transform} 只在更晚的游戏类加载时执行，懒读静态表是安全时序，
 * 与 NanoForge 的 {@code PatcherManager.activePatches()} 同款模式）。类名统一使用 JVM 内部
 * 格式（{@code /} 分隔）。
 */
public final class HybridWeaverTransformer implements IClassTransformer {
    private static final Logger LOGGER = Logger.getLogger(HybridWeaverTransformer.class);

    /** 类名（内部格式）→ 处理器注册表，onLoad 写入、transform 懒读。 */
    private static final Map<String, AsmClassProcessor> PROCESSORS = new ConcurrentHashMap<>();

    /**
     * 全局处理器注册表：对 Launch 域全部类执行（不按类名匹配）。
     * 仅适合自带字节级预过滤、未命中时零分配快速返回 {@code null} 的处理器
     * （如 {@code ColorCtorSanitizeProcessor} 的 ASCII 关键字预扫描），
     * 否则逐类全量解析的开销不可接受。onLoad 写入、transform 懒读，
     * 与 {@link #PROCESSORS} 同款安全时序。
     */
    private static final List<AsmClassProcessor> GLOBAL_PROCESSORS = new CopyOnWriteArrayList<>();

    /**
     * 正在处理中的类名（按线程）。防护场景：处理器的内部类在执行期被懒加载，
     * 其加载会穿过 Mixin 子系统，Mixin 为解析目标类（如 NanoForge 的
     * StarfarerLauncherMixin）会经 transformer 链反读同一个游戏类的字节，
     * 形成「处理器执行 → 内部类加载 → Mixin 反读 → 再次进入本处理器」的重入，
     * 以 ClassCircularityError 收场（运行时已验证）。重入时透传原字节：
     * Mixin 侧只是读取分析，用未处理字节无害；真正的定义期变换由外层调用完成。
     */
    private static final ThreadLocal<Set<String>> IN_FLIGHT = ThreadLocal.withInitial(HashSet::new);

    /**
     * LaunchWrapper 无参构造实例化入口。注册表读写全部走静态方法，
     * 实例本身不持有状态。
     */
    public HybridWeaverTransformer() {
    }

    /**
     * 注册指定类名的 ASM 字节码处理器。
     *
     * @param className 目标类名（点号或斜杠分隔均可，内部统一转换为斜杠格式）
     * @param processor 处理器实例
     */
    public static void registerProcessor(String className, AsmClassProcessor processor) {
        PROCESSORS.put(normalizeClassName(className), processor);
    }

    /**
     * 移除指定类名的处理器注册。
     *
     * @param className 目标类名
     */
    public static void removeProcessor(String className) {
        PROCESSORS.remove(normalizeClassName(className));
    }

    /**
     * 注册全局 ASM 字节码处理器（对 Launch 域全部类执行）。
     * <p>
     * 处理器必须自带廉价预过滤：未命中时返回 {@code null}，命中时返回改写后字节。
     * 全局处理器先于按类名精确匹配的处理器执行，输出作为后者的输入字节。
     *
     * @param processor 处理器实例
     */
    public static void registerGlobalProcessor(AsmClassProcessor processor) {
        GLOBAL_PROCESSORS.add(processor);
    }

    /**
     * 获取当前已注册的处理器数量（含全局处理器）。
     */
    public static int getProcessorCount() {
        return PROCESSORS.size() + GLOBAL_PROCESSORS.size();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 先执行全部全局处理器（{@link #GLOBAL_PROCESSORS}，逐类执行，自带预过滤），
     * 再以 {@code transformedName}（优先）或 {@code name} 做纯 named 匹配，命中即执行对应
     * {@link AsmClassProcessor}。全局处理器的输出字节作为按名处理器的输入。
     * <p>
     * <b>RFB 契约警告</b>：RFB 的 {@code runTransformers} 无条件采纳返回值
     * （{@code basicClass = newKlass}），返回 {@code null} 会把类字节丢弃，
     * 类加载以 "Class bytes are null" 失败——与原版 LaunchWrapper「null = 无变更」
     * 的契约不同。故未命中/处理器未修改/处理器异常时都必须返回原字节。
     */
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }

        String className = transformedName != null ? transformedName : name;
        if (className == null) {
            return basicClass;
        }

        String key = normalizeClassName(className);
        AsmClassProcessor processor = PROCESSORS.get(key);
        if (processor == null && GLOBAL_PROCESSORS.isEmpty()) {
            return basicClass;
        }

        Set<String> inFlight = IN_FLIGHT.get();
        if (!inFlight.add(key)) {
            // 同类重入（见 IN_FLIGHT 注释）：透传未处理字节
            return basicClass;
        }
        try {
            byte[] current = basicClass;
            boolean processed = false;
            // 全局处理器先行：输出作为后续处理器的输入
            for (AsmClassProcessor global : GLOBAL_PROCESSORS) {
                try {
                    byte[] result = global.process(current);
                    if (result != null) {
                        current = result;
                        processed = true;
                    }
                } catch (Throwable t) {
                    LOGGER.error("[SSOptimizer] Global ASM processor failed for " + className, t);
                }
            }
            if (processor != null) {
                try {
                    byte[] result = processor.process(current);
                    if (result != null) {
                        current = result;
                        processed = true;
                    }
                } catch (Throwable t) {
                    LOGGER.error("[SSOptimizer] ASM processor failed for " + className, t);
                }
            }
            if (processed) {
                LOGGER.debug("[SSOptimizer] Processed class: " + className);
            }
            return current;
        } finally {
            inFlight.remove(key);
        }
    }

    private static String normalizeClassName(String className) {
        return className.replace('.', '/');
    }
}
