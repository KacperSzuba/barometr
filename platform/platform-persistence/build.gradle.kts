plugins {
    id("barometr.module")
}

dependencies {
    api(project(":shared:shared-kernel"))
    api(libs.jooq)
    api(libs.jooqPostgresExtensions)
    implementation(libs.springBootStarterJooq)
    // Owns the extension migration every other schema depends on.
    runtimeOnly(libs.postgresql)
}
