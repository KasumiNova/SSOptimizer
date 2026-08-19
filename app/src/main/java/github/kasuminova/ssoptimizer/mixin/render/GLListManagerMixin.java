package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.util.GLListManager.GLListToken;
import github.kasuminova.ssoptimizer.bridge.opengl.DisplayListGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * {@code GLListManager}（com.fs.graphics.util.GLListManager）的整体委托：
 * 五个静态方法全部 @Overwrite 到 {@link DisplayListGuard}——原版的全局静态
 * 簿记与 glGenLists 阻塞分配是实体级并行录制的头号障碍（审计 H1，
 * docs/design/render-parallel-audit.md §1），guard 以「全局锁簿记 +
 * ThreadLocal buildingList + 预生成 id stash + fresh-token 护栏」保持对外
 * 语义不变。
 * <p>
 * 公有字段 {@code buildingList}/{@code suspend} 的游戏侧直接读写不在本类
 * 覆盖范围，由 {@code ShipDisplayListGuardMixin} 等调用点 @Redirect 转接
 * （字段访问无法被方法重写拦截）。
 */
@Mixin(targets = GameClassNames.GL_LIST_MANAGER_DOTTED)
public abstract class GLListManagerMixin {
    /**
     * @author KasumiNova
     * @reason 帧边界驱逐簿记并行化（全局锁），语义不变。
     */
    @Overwrite(remap = false)
    public static void nextFrame() {
        DisplayListGuard.nextFrame();
    }

    /**
     * @param token 待失效 token
     * @author KasumiNova
     * @reason 失效路径并行化（全局锁），语义不变。
     */
    @Overwrite(remap = false)
    public static void invalidateList(GLListToken token) {
        DisplayListGuard.invalidateList(token);
    }

    /**
     * @param token 目标 token
     * @return 命中并录制 glCallList 为 true；suspend/未分配/本帧他段新建为 false
     * @author KasumiNova
     * @reason 调用路径并行化 + fresh-token 护栏（并行段内本帧他段新建按未命中处理）。
     */
    @Overwrite(remap = false)
    public static boolean callList(GLListToken token) {
        return DisplayListGuard.callList(token);
    }

    /**
     * @return 新 token；suspend 或并行段内 id stash 耗尽为 null
     * @author KasumiNova
     * @reason id 分配改预生成 stash（段内禁止 glGenLists 阻塞），嵌套检查改 ThreadLocal。
     */
    @Overwrite(remap = false)
    public static GLListToken beginList() {
        return DisplayListGuard.beginList();
    }

    /**
     * @author KasumiNova
     * @reason buildingList 标志改 ThreadLocal 清理，语义不变。
     */
    @Overwrite(remap = false)
    public static void endList() {
        DisplayListGuard.endList();
    }
}
