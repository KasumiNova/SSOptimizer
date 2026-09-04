package github.kasuminova.ssoptimizer.common.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PositionSortHelper} 的逻辑验证。
 * <p>
 * 核心验证：与原版 {@code PositionImpl.sortChildren} 排序循环逐字等价的
 * 对拍实现（{@link #vanillaSort}，仅把 LinkedList 成员判定语义原样保留）
 * 在 n=1..40 随机锚图下结果序与异常出口完全一致，覆盖集合/线性两条
 * 成员判定路径（阈值 {@code SMALL_SET_THRESHOLD = 16} 两侧）。
 * 另覆盖空表、单元素、链式锚定稳定序、外部锚点异常、锚环异常、
 * 重复元素出口等边界。
 */
class PositionSortHelperTest {

    /** 测试节点：恒等语义（不覆写 equals/hashCode，与 PositionImpl 一致）。 */
    private static final class Node {
        Node base;

        void anchorTo(final Node base) {
            this.base = base;
        }
    }

    /** 原版 sortChildren 排序循环的逐字转抄（守卫条件除外，由 mixin 侧负责）。 */
    private static void vanillaSort(final List<Node> items) {
        final LinkedList<Node> var1 = new LinkedList<>();
        int var2 = 0;

        while (var1.size() < items.size()) {
            for (final Node var3 : items) {
                if (!var1.contains(var3)) {
                    final Node var5 = var3.base;
                    if (var5 != null && !items.contains(var5)) {
                        throw new RuntimeException("May only anchor on siblings");
                    }

                    if (var5 == null || var1.contains(var5)) {
                        var1.add(var3);
                    }
                }
            }

            if (++var2 > items.size()) {
                throw new RuntimeException("Circular dependency of sibling positions detected");
            }
        }

        items.clear();
        items.addAll(var1);
    }

    private static List<Node> sortWithHelper(final List<Node> items) {
        final List<Node> copy = new ArrayList<>(items);
        PositionSortHelper.sortByAnchor(copy, node -> node.base);
        return copy;
    }

    private static List<Node> sortWithVanilla(final List<Node> items) {
        final List<Node> copy = new ArrayList<>(items);
        vanillaSort(copy);
        return copy;
    }

    @Test
    void emptyAndSingleElementPassThrough() {
        final List<Node> empty = new ArrayList<>();
        PositionSortHelper.sortByAnchor(empty, node -> node.base);
        assertEquals(0, empty.size());

        final Node solo = new Node();
        final List<Node> single = new ArrayList<>(List.of(solo));
        PositionSortHelper.sortByAnchor(single, node -> node.base);
        assertEquals(List.of(solo), single);
    }

    @Test
    void anchorChainProducesStableTopologicalOrder() {
        // c 锚 b、b 锚 a：无论输入顺序如何，a 必须先于 b 先于 c
        final Node a = new Node();
        final Node b = new Node();
        final Node c = new Node();
        b.anchorTo(a);
        c.anchorTo(b);

        final List<Node> sorted = sortWithHelper(List.of(c, b, a));
        assertEquals(List.of(a, b, c), sorted);

        // 无锚点元素保持输入相对顺序（稳定）
        final Node d = new Node();
        final Node e = new Node();
        final List<Node> plain = sortWithHelper(List.of(d, e, a));
        assertEquals(List.of(d, e, a), plain, "a/b/c 已就位后输入顺序保持");
    }

    @Test
    void externalAnchorThrowsSiblingError() {
        final Node inside = new Node();
        final Node outsider = new Node();
        inside.anchorTo(outsider);

        final List<Node> items = new ArrayList<>(List.of(inside));
        final RuntimeException ex = assertThrows(RuntimeException.class,
                () -> PositionSortHelper.sortByAnchor(items, node -> node.base));
        assertEquals("May only anchor on siblings", ex.getMessage());

        // 原版同样抛此异常（逐字对拍）
        final List<Node> vanillaItems = new ArrayList<>(List.of(inside));
        final RuntimeException vanillaEx = assertThrows(RuntimeException.class,
                () -> vanillaSort(vanillaItems));
        assertEquals(vanillaEx.getMessage(), ex.getMessage());
    }

    @Test
    void anchorCycleThrowsCircularDependency() {
        final Node a = new Node();
        final Node b = new Node();
        a.anchorTo(b);
        b.anchorTo(a);

        final List<Node> items = new ArrayList<>(List.of(a, b));
        final RuntimeException ex = assertThrows(RuntimeException.class,
                () -> PositionSortHelper.sortByAnchor(items, node -> node.base));
        assertEquals("Circular dependency of sibling positions detected", ex.getMessage());
    }

    @Test
    void selfAnchorThrowsCircularDependency() {
        final Node a = new Node();
        a.anchorTo(a);

        final List<Node> items = new ArrayList<>(List.of(a));
        final RuntimeException ex = assertThrows(RuntimeException.class,
                () -> PositionSortHelper.sortByAnchor(items, node -> node.base));
        assertEquals("Circular dependency of sibling positions detected", ex.getMessage());
    }

    @Test
    void duplicateElementMatchesVanillaExit() {
        // 同一实例出现两次：原版在第二轮起 var1.contains 恒真、列表永远排不满，
        // 最终走「Circular dependency」出口；helper 必须同出口同文案。
        final Node a = new Node();
        final List<Node> items = new ArrayList<>(List.of(a, a));

        final RuntimeException ex = assertThrows(RuntimeException.class,
                () -> PositionSortHelper.sortByAnchor(items, node -> node.base));
        final RuntimeException vanillaEx = assertThrows(RuntimeException.class,
                () -> vanillaSort(new ArrayList<>(List.of(a, a))));
        assertEquals(vanillaEx.getMessage(), ex.getMessage());
    }

    @Test
    void randomAnchorGraphsMatchVanillaAcrossThreshold() {
        // n=1..40 覆盖线性路径（<16）与集合路径（>=16），含乱序锚链与多锚点
        final Random random = new Random(20260904L);
        for (int n = 1; n <= 40; n++) {
            for (int trial = 0; trial < 50; trial++) {
                final List<Node> nodes = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    nodes.add(new Node());
                }
                // 随机锚图：每个节点以 50% 概率锚到任意其他节点（保证无环：只锚更低下标）
                for (int i = 1; i < n; i++) {
                    if (random.nextBoolean()) {
                        nodes.get(i).anchorTo(nodes.get(random.nextInt(i)));
                    }
                }
                // 随机打乱输入顺序
                final List<Node> shuffled = new ArrayList<>(nodes);
                for (int i = n - 1; i > 0; i--) {
                    final int j = random.nextInt(i + 1);
                    final Node tmp = shuffled.get(i);
                    shuffled.set(i, shuffled.get(j));
                    shuffled.set(j, tmp);
                }

                final List<Node> expected = sortWithVanilla(shuffled);
                final List<Node> actual = sortWithHelper(shuffled);
                assertEquals(expected, actual, "n=" + n + " trial=" + trial + " 结果序与原版不一致");
            }
        }
    }
}
