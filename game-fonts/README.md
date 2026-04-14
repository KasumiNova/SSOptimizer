# game-fonts — 字体资源

本目录存放 SSOptimizer 字体覆盖功能 (`original-match` 配置) 所需的字体文件。

## 目录结构

```
game-fonts/
├── ttf/          # TrueType 字体
│   ├── lte50549.ttf          # Insignia — 游戏自带
│   ├── orbitron-black.ttf    # Orbitron Black — 游戏自带
│   ├── orbitron-bold.ttf     # Orbitron Bold — 游戏自带
│   ├── orbitron-light.ttf    # Orbitron Light — 游戏自带
│   ├── MiSans-Medium.ttf     # 小米 MiSans — CJK 回退字体 (SIL OFL 1.1)
│   └── Oxanium-Medium.ttf    # Oxanium — 用于 victor 系列 (SIL OFL 1.1)
└── fnt/          # BMFont 描述文件（已添加 CJK 字符表）
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
| MiSans-Medium.ttf | SIL Open Font License 1.1 |
| Oxanium-Medium.ttf | SIL Open Font License 1.1 |
