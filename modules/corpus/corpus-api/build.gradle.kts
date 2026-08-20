plugins {
    id("barometr.module")
}

dependencies {
    api(project(":shared:shared-kernel"))
    api(project(":modules:ingestion:ingestion-api"))
    compileOnly(libs.springModulithApi)
}
