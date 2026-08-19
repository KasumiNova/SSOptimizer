package github.kasuminova.ssoptimizer.bridge.opengl;

import com.fs.graphics.util.GLListManager.GLListToken;
import github.kasuminova.ssoptimizer.common.render.queue.RenderSegment;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 游戏 {@code GLListManager}（com.fs.graphics.util.GLListManager，DoNotObfuscate
 * 名字稳定）的并行安全替代实现：{@code GLListManagerMixin} 把全部五个静态方法
 * @Overwrite 委托到本类，游戏侧对公有字段 {@code buildingList}/{@code suspend}
 * 的直接读写经各调用点 Mixin @Redirect 转接到 {@link #isBuildingList()} 与
 * {@link #setSuspend(boolean)}。
 * <p>
 * 动机：原版实现的全局静态簿记（activeLists/allocatedLists/freeLists/
 * currListId/maxListId/buildingList）是实体级并行录制的头号障碍（审计 H1，
 * docs/design/render-parallel-audit.md §1）——worker 并发 beginList/callList
 * 会互踩 id 池与集合，buildingList 全局标志会让并发段落进错分支。本类在保持
 * 原版对外语义（token 失效节奏、callList 未命中返回 false、嵌套 beginList
 * 抛错、suspend 全局暂停）不变的前提下做并行化：
 * <ul>
 *   <li>簿记（allocated/active/freeIds/metas）由单把全局锁保护——临界区只有
 *       集合操作，无 GL 调用，竞争可忽略；token 的 id 与（帧, 段）创建戳由
 *       本类侧表（{@link #metas}）持有，随 token 失效一并清除，不改造
 *       GLListToken 类本体；</li>
 *   <li>{@code buildingList} 改为 {@link ThreadLocal}——「是否处于列表编译中」
 *       是线程语义（嵌套检查、渲染路径切换都只对当前线程有意义）；</li>
 *   <li>id 分配改 {@link BridgeSupport#acquireListId()} 预生成 stash——原版
 *       beginList 内的 glGenLists 阻塞调用在并行段内会打穿管线（fail-fast），
 *       stash 由渲染线程帧尾低水位补货，段内恒零阻塞；段内 stash 耗尽返回
 *       null（与 suspend 同语义，调用方惯用法自带「缓存未命中则直接渲染」
 *       回退）；</li>
 *   <li>fresh-token 护栏：本帧由其他录制段新建的 token，在并行段内 callList
 *       返回 false——重放时「call 的段排在 build 的段前」会调到未编译的
 *       display list（GL 规范下是静默错误/旧内容），必须让调用方走直接
 *       渲染回退。主线程串行段不受限：编排器屏障保证串行锚点段在帧内登记序
 *       恒晚于已完成的并行段。</li>
 * </ul>
 * display list 本体（glNewList/glEndList/glCallList）仍走 bridge 录制进当前段，
 * 编译/执行发生在渲染线程，段序即编译/调用序。
 */
public final class DisplayListGuard {
    /** 簿记全局锁：只护集合操作，GL 调用一律在锁外（经 bridge 录制）。 */
    private static final Object LOCK = new Object();

    /** 已分配（存活）token 集：语义同原版 allocatedLists。 */
    private static final Set<GLListToken> allocated = new HashSet<>();
    /** 本帧被 callList 触达的 token 集：语义同原版 activeLists。 */
    private static final Set<GLListToken> active = new HashSet<>();
    /** 可回收 id 池：语义同原版 freeLists（失效 token 的 id 入池复用，不 glDeleteLists）。 */
    private static final ArrayDeque<Integer> freeIds = new ArrayDeque<>();
    /**
     * token 侧表（identity 语义）：id + （帧, 段）创建戳。条目随 token 离开
     * allocated（失效/驱逐/上下文重建）同步清除，规模恒等于 allocated。
     */
    private static final Map<GLListToken, TokenMeta> metas = new IdentityHashMap<>();

    /** 全局暂停标志：语义同原版 suspend（Planet 3D 渲染期置位），volatile 保证跨线程可见。 */
    private static volatile boolean suspend = false;
    /** 线程语义的「列表编译中」标志：替代原版全局 buildingList。 */
    private static final ThreadLocal<Boolean> building = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private DisplayListGuard() {
    }

    /**
     * 帧边界簿记（原版 {@code nextFrame()}）：驱逐「已分配但本帧未被 callList
     * 触达」的 token（id 回 freeIds 复用），清空 active。游戏每帧由
     * {@code GLLauncher} 调用一次（主线程），但加锁后任何线程调用都安全。
     */
    public static void nextFrame() {
        synchronized (LOCK) {
            Set<GLListToken> evict = new HashSet<>(allocated);
            evict.removeAll(active);
            for (GLListToken token : evict) {
                TokenMeta meta = metas.remove(token);
                freeIds.addLast(meta.id);
                allocated.remove(token);
            }
            active.clear();
        }
    }

    /** 主动失效（原版 {@code invalidateList()}）：id 回池并从簿记移除。 */
    public static void invalidateList(GLListToken token) {
        if (token == null) {
            return;
        }
        synchronized (LOCK) {
            if (allocated.remove(token)) {
                TokenMeta meta = metas.remove(token);
                freeIds.addLast(meta.id);
                active.remove(token);
            }
        }
    }

    /**
     * 调用 display list（原版 {@code callList()}）：suspend/未分配/本帧他段新建
     * 时返回 false（调用方回退直接渲染）；命中则录制 glCallList 并标记本帧活跃。
     */
    public static boolean callList(GLListToken token) {
        if (suspend || token == null) {
            return false;
        }
        RenderSegment bound = BridgeSupport.recordingContext().boundSegment;
        int id;
        synchronized (LOCK) {
            TokenMeta meta = metas.get(token);
            if (meta == null || !allocated.contains(token)) {
                return false;
            }
            if (bound != null
                    && meta.createdFrame == BridgeSupport.queue().currentFrame()
                    && meta.createdSegment != bound) {
                // fresh-token 护栏：本帧由其他段新建的 token，重放序无法保证
                // 「编译先于调用」，段内一律按未命中处理
                return false;
            }
            active.add(token);
            id = meta.id;
        }
        GL11.glCallList(id);
        return true;
    }

    /**
     * 开始编译 display list（原版 {@code beginList()}）：suspend 或并行段内
     * stash 耗尽返回 null；同线程嵌套编译抛错（原版语义）；成功则分配 id、
     * 登记簿记、给 token 打上（帧, 段）创建戳并录制 glNewList。
     */
    public static GLListToken beginList() {
        if (suspend) {
            return null;
        }
        if (building.get()) {
            throw new RuntimeException("Can't create nested lists using GLListManager");
        }
        int id;
        synchronized (LOCK) {
            Integer reused = freeIds.pollFirst();
            id = reused != null ? reused : -1;
        }
        if (id < 0) {
            id = BridgeSupport.acquireListId();
            if (id < 0) {
                // 并行段内 stash 耗尽：按 suspend 等价语义退化（调用方直接渲染）
                return null;
            }
        }
        GLListToken token = new GLListToken(id);
        TokenMeta meta = new TokenMeta();
        meta.id = id;
        meta.createdFrame = BridgeSupport.queue().currentFrame();
        meta.createdSegment = BridgeSupport.recordingContext().boundSegment;
        synchronized (LOCK) {
            allocated.add(token);
            active.add(token);
            metas.put(token, meta);
        }
        GL11.glNewList(id, org.lwjgl.opengl.GL11.GL_COMPILE_AND_EXECUTE);
        building.set(Boolean.TRUE);
        return token;
    }

    /** 结束编译（原版 {@code endList()}）：清线程标志并录制 glEndList。 */
    public static void endList() {
        if (suspend) {
            return;
        }
        building.set(Boolean.FALSE);
        GL11.glEndList();
    }

    /** 线程语义的「列表编译中」查询：替代游戏侧对公有字段 buildingList 的直接读。 */
    public static boolean isBuildingList() {
        return building.get();
    }

    /** 全局暂停开关：替代游戏侧对公有字段 suspend 的直接写（Planet.render3d）。 */
    public static void setSuspend(boolean value) {
        suspend = value;
    }

    /** 全局暂停状态查询（原版 suspend 字段读语义）。 */
    public static boolean isSuspend() {
        return suspend;
    }

    /**
     * GL 上下文重建后的全量作废：display list 本体随上下文销毁，存活 token
     * 的 id 全部失效——清空簿记让调用方经 callList 未命中路径重建。
     * 由 {@link BridgeSupport#onContextRecreated()} 调用。
     */
    static void onContextRecreated() {
        synchronized (LOCK) {
            allocated.clear();
            active.clear();
            freeIds.clear();
            metas.clear();
        }
        building.remove();
    }

    /** 测试用：卸载时复位全部静态簿记，避免用例间串扰。 */
    static void reset() {
        onContextRecreated();
        suspend = false;
    }

    /** token 侧表条目：display list id 与（帧, 段）创建戳。 */
    private static final class TokenMeta {
        int id;
        Object createdFrame;
        Object createdSegment;
    }
}
