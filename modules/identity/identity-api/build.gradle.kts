plugins {
    id("barometr.module")
}

dependencies {
    api(project(":shared:shared-kernel"))
    // Annotations only, so that depending on this contract drags in no runtime.
    compileOnly(libs.springModulithApi)
}
