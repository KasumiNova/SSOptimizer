plugins {
    id("ssoptimizer-module")
}

// 自动化模块：内建冒烟/基准验收（autostart、save_load_cycle、遥测、帧捕获、基准驱动）。
// 禁止使用系统工具实机测试——本模块即唯一的实机验收通道。
dependencies {
    implementation(project(":modules:internal:sso-core"))
}
