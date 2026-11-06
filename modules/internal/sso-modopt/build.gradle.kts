plugins {
    id("ssoptimizer-module")
}

dependencies {
    implementation(project(":modules:internal:sso-core"))
    implementation("com.github.luben:zstd-jni:1.5.7-3")
}
