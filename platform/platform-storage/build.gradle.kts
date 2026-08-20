plugins {
    id("barometr.module")
}

dependencies {
    api(project(":shared:shared-kernel"))
    implementation(libs.springBootStarter)

    testImplementation(kotlin("test"))
}
