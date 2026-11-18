package github.kasuminova.ssoptimizer.api.save;

import java.io.Serializable;
import java.util.List;

/**
 * 已登记 lambda 的恢复工厂，仅由存档兼容层在绑定与捕获参数校验成功后调用。
 * 工厂必须返回与登记样本相同调用位置生成的可序列化 lambda，禁止按存档字符串查找或调用方法。
 */
@FunctionalInterface
public interface SaveLambdaFactory {
    /**
     * 在读档线程上恢复 lambda。参数按登记顺序排列，列表不可修改，允许非基本类型参数为 null。
     * 工厂异常会中止当前对象的恢复，并由读档错误链报告。
     */
    Serializable restore(List<Object> capturedArguments);
}
