package github.kasuminova.ssoptimizer.common.debug;

import github.kasuminova.ssoptimizer.api.debug.DebugScript;

/**
 * 已编译脚本的注册句柄。
 *
 * <p>每次编译生成全新类加载器与实例，同名 scriptId 替换旧句柄即完成
 * L1 热重载——旧类加载器随引用解除被 GC，不触碰任何已加载游戏类。</p>
 *
 * @param scriptId   脚本句柄 ID（调用方指定）
 * @param className  脚本主类全限定名
 * @param version    编译版本号（同 scriptId 从 1 起递增）
 * @param instance   脚本实例
 * @param classLoader 本次编译产物的隔离类加载器
 * @param compiledAt 编译完成时间戳（epoch millis）
 */
public record ScriptHandle(String scriptId,
                           String className,
                           int version,
                           DebugScript instance,
                           ClassLoader classLoader,
                           long compiledAt) {
}
