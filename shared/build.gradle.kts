plugins {
    id("barometr.module")
}

dependencies {
    // Value types only. Nothing here may reach for Spring, persistence or HTTP —
    // the moment shared code needs a framework, it belongs to a context.
    api(libs.uuidCreator)
}
