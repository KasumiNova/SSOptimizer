# SSOptimizer 运行时文字链路与 Phase 2 切入点

## 目标
记录 Starsector 运行时文字渲染链路中与 Phase 2（动态 glyph cache / 运行时接管）直接相关的已验证映射，避免后续重复逆向。

## 证据链

### 1. 最终 quad 发射点
- 类：`com.fs.graphics.super.Object`
- 方法：`private void o00000(float, float, com.fs.graphics.super.oOOO, float, boolean)`
- 现有接管：`EngineSuperObjectProcessor` 已将该方法改写到 `SuperObjectRenderHelper.renderGlyphQuad(...)`
- 关键事实：这一层只剩 glyph 尺寸、bearing、UV、`scale`、shadow 参数，已经没有 codepoint / font identity。

### 2. 运行时主文字循环
`com.fs.graphics.super.Object` 的主循环会：
1. 从当前字符串取 `charAt(i)`
2. 通过 `com.fs.graphics.super.return.oO0000(int)` 查 glyph
3. 通过 `com.fs.graphics.super.return.o00000(int, int)` 查 kerning
4. 计算运行时 `scale`
5. 调用 `o00000(float, float, com.fs.graphics.super.oOOO, float, boolean)` 发射 glyph quad
6. 用 glyph advance 更新光标

### 3. scale 的直接来源
在 `com.fs.graphics.super.Object` 的主循环里，运行时 `scale` 由下式计算：

$$
scale = \frac{Object.ø00000}{return.class()}
$$

其中：
- `Object.ø00000`：当前对象上的文字尺寸/名义字号字段
- `return.class()`：字体资源里的名义尺寸字段（从用法看极可能对应 BMFont 的 `info.size`，但该点目前仍以“高置信推断”标记）

同一循环中，换行使用：

$$
y -= scale \times return.float()
$$

因此 `return.float()` 对应行高字段（高置信，对应用法等同于 BMFont `lineHeight`）。

## `com.fs.graphics.super.return` 的职责
`return` 是字体资源对象，至少负责：
- 持有 glyph 数组：`oOOO[]`
- 持有 kerning map：`Map<Pair, E>`
- `oO0000(int)`：按字符码查 glyph
- `o00000(int, int)`：按前后字符查 kerning

这意味着：
- **glyph identity（codepoint）在 `Object` 主循环与 `return` 查表层还存在**
- **到了 `SuperObjectRenderHelper.renderGlyphQuad(...)` 已经丢失**

## `com.fs.graphics.super.oOOO` 映射
基于 `javap` 与调用关系，当前可高置信映射为：

### 原始 glyph 指标
- `Ö00000()` → `char id`
- `if()` → `xoffset`
- `void()` → `xadvance`
- `ø00000()` → `width`
- `ÒO0000()` → `height`
- `Ò00000()` → `yoffset / bearingY`

### 原始 atlas 坐标
- `OO0000()` → atlas `x`
- `super()` → atlas `y`
- `ø00000()` → glyph `width`
- `ÒO0000()` → glyph `height`

### 归一化 UV
这些值由 `return.o00000(oOOO)` 在装载字体资源时预先计算：
- `õ00000()` → `texX`
- `do()` → `texY`
- `String()` → `texWidth`
- `ô00000()` → `texHeight`

## 对 Phase 2 的直接含义

### 1. 当前 native-freetype 仍会受游戏缩放影响
当前路径仍是：
- 先生成 atlas
- 再在运行时按 `scale` 缩放 quad

所以只要 `scale != 1.0`，最终观感仍会回到 bitmap 放缩问题域。

### 2. 动态 glyph cache 必须 scale-aware
因为 `scale` 是在运行时按对象字号计算出来的，动态 cache 的 key 至少需要包含：
- 字体身份（font resource / face）
- codepoint
- scale bucket
- 可能的渲染风格位（AA / shadow 相关策略）

当前仓库里已经落了一个最小类型化原型：

- `TextGlyphCacheKey(fontInstanceId, nominalFontSize, lineHeight, scaleBucketMillis, glyphId)`

它代表了“在不改原始布局算法的前提下，pre-quad 层能稳定拿到的最小 cache 身份”。

### 3. 最小可行接管点不应只放在最终 helper
更合理的候选层级是：
- `Object` 主循环（同时拥有 codepoint、kerning、scale）
- `return.oO0000(int)` / `return.o00000(int, int)` 周边（仍保留 glyph identity）

`SuperObjectRenderHelper.renderGlyphQuad(...)` 更适合继续承担：
- 热路径发射优化
- runtime diagnostics
- native quad emission

而不是独自承担真正的 glyph identity 级 cache。

## 后续建议
1. 优先在 `Object` 主循环层做只读诊断，补齐：
   - codepoint 分布
   - `xoffset / xadvance` 分布
   - scale bucket 与 codepoint 的联合分布
   - typed cache key（`TextGlyphCacheKey`）的真实热度分布
2. 再决定最小 runtime cache 原型是：
   - 在 `Object` 层按 `codepoint + scale bucket` 接管
   - 还是扩展 `return` 层做新的 glyph provider
3. 对 `scale != 1.0` 的常见 bucket（例如 `1.125`、`1.250`）优先做原型，避免一上来支持全连续缩放。
