package github.kasuminova.ssoptimizer.api.save;

import java.io.Serializable;
import java.util.List;

/**
 * 为游戏存档序列化器登记精确类型与 lambda 绑定，避免开放整个包或类型层次。
 * 仅在独立 XStream 兼容配置启用时注册；未注册表示当前启动配置不提供此权限管理能力。
 * 必须在 ModPlugin.configureXStream 的配置线程中调用，序列化器创建完成后禁止扩大许可。
 * serializer 参数必须是游戏传入的 XStream 实例；使用 Object 保持 API 模块不依赖第三方库。
 */
public interface SaveTypeRegistration {
    /**
     * 登记确实需要持久化的具体 Class。数组同时登记其元素类型；子类、字段类型和嵌套类不会自动登记。
     * 危险运行时类型仍被拒绝。错误参数或配置阶段已结束时抛出 IllegalArgumentException 或 IllegalStateException。
     */
    void registerTypes(Object serializer, Class<?>... types);

    /**
     * 从可信代码创建的样本取得 lambda 绑定，保存捕获类、实现方法与函数签名的精确身份。
     * capturingClass、implementationClass 和 functionalInterface 必须与样本描述一致，capturedTypes 必须逐项声明捕获参数类型。
     * 工厂仅在 XML 中的全部绑定字段与登记值一致、捕获参数校验通过后调用。
     * 相同绑定重复登记、样本不是可序列化 lambda 或类型不一致时抛出 IllegalArgumentException。
     */
    void registerLambda(Object serializer, Serializable prototype, Class<?> capturingClass,
                        Class<?> implementationClass, Class<?> functionalInterface,
                        List<Class<?>> capturedTypes, SaveLambdaFactory factory);
}
