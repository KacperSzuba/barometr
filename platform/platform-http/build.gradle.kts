plugins {
    id("barometr.module")
}

dependencies {
    api(project(":shared:shared-kernel"))

    // RestClient rather than a hand-built client on java.net.http: Boot's
    // auto-configured builder is instrumented by Micrometer, so every connector
    // request produces an `http.client.requests` timer and a trace span. That is
    // a hard requirement of the observability tasks, not a convenience.
    // `api`, not `implementation`: the factory's signature exposes
    // `RestClient.Builder`, so consumers need it on their compile classpath.
    api(libs.springBootStarterRestClient)
    implementation(libs.springBootStarterWeb)
    // Retry lives in Spring Framework 7 core — backoff, jitter, max delay and
    // retryable-exception predicates included. No third-party retry needed.
    implementation(libs.springBootStarter)

    implementation(libs.resilience4jRateLimiter)
    implementation(libs.resilience4jMicrometer)
    implementation(libs.crawlerCommons)

    testImplementation(kotlin("test"))
}
