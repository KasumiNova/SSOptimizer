package github.kasuminova.ssoptimizer.common.debug;

import java.lang.instrument.Instrumentation;

/**
 * hotswap 自 attach 微型 agent。
 *
 * <p>动机：{@link Instrumentation} 实例只能经 agent 回调获得。本类在编译期随
 * sso-debug 产出，运行期由 {@link HotswapSupport} 把 class 字节抽出打包成
 * 微型 agent jar，经 jdk.attach 自 attach 加载（agent 类由系统类加载器加载，
 * 与 sso-debug 域不同——实例经 {@link #INST} 静态字段跨域取回）。</p>
 *
 * <p>边界（AGENTS.md 调试例外条款）：仅用于调试会话内的方法体级 redefine，
 * 不产出持久类变换、不改类结构；仅 sso-debug 调试模式启用时可触达。</p>
 */
public final class HotswapAgent {
    /** agent 回调写入的 Instrumentation 实例（系统类加载器域）。 */
    public static volatile Instrumentation INST;

    private HotswapAgent() {
    }

    /**
     * JDK agent 入口（{@code loadAgent} 回调）。
     *
     * @param args agent 参数（未使用）
     * @param inst Instrumentation 实例
     */
    public static void agentmain(final String args, final Instrumentation inst) {
        INST = inst;
    }
}
