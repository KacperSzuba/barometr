plugins {
    id("barometr.module")
}

dependencies {
    api(project(":shared:shared-kernel"))
    api(project(":modules:corpus:corpus-api"))
    compileOnly(libs.springModulithApi)
}
