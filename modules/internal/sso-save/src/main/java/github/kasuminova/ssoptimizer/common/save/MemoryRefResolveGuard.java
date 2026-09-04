package github.kasuminova.ssoptimizer.common.save;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 读档窗口 Memory 引用解析守卫。
 * <p>
 * 动机：原版 {@code Memory.replaceIdsWithEntities} 在首次访问时把 mRef_/enRef_ 字符串
 * 反查为实体/市场对象，反查目标是 {@code Global.getSector()} 的<b>当前</b>引擎。
 * 而在 XStream unmarshal 期间（载入阶段3），引擎单例仍是旧实例（启动加载时为空引擎），
 * 模组若在 readResolve 链上触碰舰队 memory（实证：Second in Command 的
 * {@code SCControllerHullmod.applyEffectsBeforeShipCreation} 经
 * {@code FleetMember.readResolve → updateStats} 触发），全部引用反查必然失败，
 * 原版随之<b>静默删除</b>这些键——读档完成后脚本再读取即 NPE
 * （实证：CjyToyBox CTBBlueHouse2$oreSupplyFleetScript.advance:447）。<br>
 * 机制：读档开始到「阶段25 {@code CampaignEngine.setInstance(新引擎)}」之间为不安全窗口，
 * 窗口内的删除操作全部挂起并记录来源 Memory；新引擎安装后（仍在 onGameLoad 之前）
 * 对记录的 Memory 重放解析，此时经济体市场表与实体索引完整，反查语义与
 * 「读档完成后首次访问」等价。真正已失效的引用（市场/实体确实不存在）
 * 会在重放时按原版语义正常删除并输出 WARN 日志。<br>
 * 已知边界：游戏内重载存档时旧引擎非空，窗口内反查可能「成功」解析到旧引擎的同名对象
 * （产生陈旧引用），此为原版+模组交互的既有问题，本守卫不处理。
 */
public final class MemoryRefResolveGuard {
    private static final Logger LOGGER = Logger.getLogger(MemoryRefResolveGuard.class);

    /**
     * 供 Mixin 注入到 {@code Memory} 的重放接口（Mixin 接口注入模式）。
     */
    public interface PendingResolution {
        /**
         * 以当前（新引擎已安装）状态重放一次 {@code replaceIdsWithEntities}。
         */
        void ssoptimizer$rerunResolution();
    }

    /** 不安全窗口标志：读档 HEAD 置位，新引擎安装后复位。 */
    private static volatile boolean unsafeWindow;

    /** 读档入口时的引擎单例，用于区分成功路径（装新引擎）与异常路径（恢复旧引擎）。 */
    private static volatile Object engineAtHead;

    /** 窗口内发生挂起删除的 Memory 实例（实现 PendingResolution 接口）。 */
    private static final List<PendingResolution> PENDING = new ArrayList<>();

    private MemoryRefResolveGuard() {
    }

    /**
     * @return 当前是否处于读档不安全窗口（unmarshal 期间，引擎单例尚未指向新引擎）
     */
    public static boolean isUnsafeWindow() {
        return unsafeWindow;
    }

    /**
     * 读档入口（{@code CampaignGameManager.loadGame} HEAD）：进入不安全窗口并
     * 记录当前引擎单例。嵌套调用（.bak 回退重试）重复置位无副作用。
     */
    public static void enterLoad() {
        enterLoad(com.fs.starfarer.campaign.CampaignEngine.getInstance());
    }

    /**
     * {@link #enterLoad()} 的可测性重载：由调用方提供入口时的引擎单例，
     * 避免单测触碰游戏类。
     *
     * @param currentEngine 读档入口时的引擎单例
     */
    static void enterLoad(final Object currentEngine) {
        unsafeWindow = true;
        engineAtHead = currentEngine;
    }

    /**
     * 新引擎安装点（阶段25 setInstance 之后）：关闭窗口并重放全部挂起解析。
     * 此时距 onGameLoad 与首帧 advance 之间无任何模组代码执行，挂起键不会以
     * 字符串形态泄漏给模组。
     *
     * @param installedEngine 本次安装的引擎实例；等于入口时的单例说明是异常路径
     *                        恢复旧引擎，不关闭窗口
     */
    public static void onEngineInstalled(final Object installedEngine) {
        if (!unsafeWindow || installedEngine == engineAtHead) {
            return;
        }
        unsafeWindow = false;
        runFixups();
    }

    /**
     * 读档方法返回清理：无论成败关闭窗口；成功路径的窗口已在引擎安装点关闭，
     * 失败路径挂起的 Memory 属于被丢弃的部分对象图，直接清空。
     */
    public static void loadFinished() {
        unsafeWindow = false;
        engineAtHead = null;
        synchronized (PENDING) {
            PENDING.clear();
        }
    }

    /**
     * 窗口内挂起一次删除：记录来源 Memory，键保持字符串形态待重放。
     *
     * @param memory 被解析的 Memory（实现 {@link PendingResolution}）
     */
    public static void recordSuppressed(final PendingResolution memory) {
        synchronized (PENDING) {
            PENDING.add(memory);
        }
    }

    /**
     * 重放全部挂起解析。逐实例捕获异常并记录，单个损坏 Memory 不阻断其余重放。
     */
    private static void runFixups() {
        final List<PendingResolution> batch;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        int failures = 0;
        for (final PendingResolution memory : batch) {
            try {
                memory.ssoptimizer$rerunResolution();
            } catch (Throwable t) {
                failures++;
                LOGGER.error("[SSOptimizer] Memory 引用重放解析失败", t);
            }
        }
        LOGGER.info(String.format(
                "[SSOptimizer] 读档窗口守卫：重放 %d 个 Memory 的引用解析（失败 %d）", batch.size(), failures));
    }
}
