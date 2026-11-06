plugins {
    id("ssoptimizer-module")
}

dependencies {
    implementation(project(":modules:api:sso-api"))
    implementation(project(":modules:internal:sso-core"))

    implementation("com.google.code.gson:gson:2.13.1")
}
