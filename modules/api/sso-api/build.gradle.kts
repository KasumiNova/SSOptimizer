plugins {
    id("ssoptimizer-module")
}

// 纯接口层：对外暴露的跨模块 API（ASM 处理器框架接口 + 跨域行为接口）。
// 零实现、零第三方运行时依赖；游戏 jar 与 NanoForge 仅为 compileOnly 编译对齐。
