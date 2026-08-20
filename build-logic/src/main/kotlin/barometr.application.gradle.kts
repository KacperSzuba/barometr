plugins {
    id("barometr.spring-platform")
    id("org.springframework.boot")
}

/**
 * The single deployable. `app` is the only project allowed to see every module's
 * implementation, because assembling them is its entire purpose — it holds no
 * domain logic of its own.
 *
 * Deliberately without the boundary check that `barometr.module` applies.
 */
