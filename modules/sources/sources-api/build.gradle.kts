plugins {
    id("barometr.module")
}

dependencies {
    api(project(":shared:shared-kernel"))
    compileOnly(libs.springModulithApi)
}
