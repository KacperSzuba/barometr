plugins {
    id("barometr.module")
}

dependencies {
    api(project(":shared:shared-kernel"))
    api(project(":modules:sources:sources-api"))
    compileOnly(libs.springModulithApi)
}
