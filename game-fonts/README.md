# game-fonts — 字体资源

本目录存放 SSOptimizer 字体覆盖功能 (`original-match` 配置) 所需的字体文件。

`ttf/` 为运行期 TTF 字体源（打包进 `mods/ssoptimizer/fonts/`）；`fnt/` 自字体渲染
重写 P4 起不再作为 overlay 覆写游戏 `graphics/fonts/`（fnt 数据改为运行期内存供给），
仅保留作测试 fixture（如 `AtlasSoftwareRenderIT` 的原版度量基准）。

## 目录结构

```
game-fonts/
├── ttf/          # TrueType 字体
│   ├── lte50549.ttf          # Insignia — 游戏自带
│   ├── orbitron-black.ttf    # Orbitron Black — 游戏自带
│   ├── orbitron-bold.ttf     # Orbitron Bold — 游戏自带
│   ├── orbitron-light.ttf    # Orbitron Light — 游戏自带
│   ├── orbitron-medium.ttf   # Orbitron Medium — 游戏自带
│   ├── orbitron-regular.ttf  # Orbitron Regular — 游戏自带
│   ├── orbitron-semibold.ttf # Orbitron SemiBold — 游戏自带（bold 角色实际映射字重）
│   ├── MiSans-Regular.ttf    # 小米 MiSans — 全系 CJK 回退字体 (SIL OFL 1.1)
│   └── Oxanium-Medium.ttf    # Oxanium — 用于 victor 系列 (SIL OFL 1.1)
└── fnt/          # BMFont 描述文件（已添加 CJK 字符表；仅测试 fixture，不再随分发部署）
    ├── insignia15LTaa.fnt
    ├── insignia21LTaa.fnt
    ├── insignia25LTaa.fnt
    ├── orbitron12condensed.fnt
    ├── orbitron20aa.fnt
    ├── orbitron20aabold.fnt
    ├── orbitron24aa.fnt
    ├── orbitron24aabold.fnt
    ├── victor10.fnt
    └── victor14.fnt
```

## 许可证

| 字体 | 许可证 |
|------|--------|
| lte50549.ttf, orbitron-*.ttf | Starsector 游戏自带 |
| MiSans-Regular.ttf | SIL Open Font License 1.1 |
| Oxanium-Medium.ttf | SIL Open Font License 1.1 |
