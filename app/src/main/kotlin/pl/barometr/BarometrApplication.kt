package pl.barometr

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

/**
 * Base package `pl.barometr`, so every direct subpackage is an application
 * module in Spring Modulith's eyes. Classes sitting in this package itself —
 * this one, the security configuration, the exception handler — belong to no
 * module, which is right: they are the assembly, not the domain.
 */
@Modulithic(
    systemName = "Barometr",
    // Value types every module leans on; without this Modulith would report the
    // resulting fan-in as a design smell rather than the intent it is.
    sharedModules = ["shared"],
)
@SpringBootApplication
@ConfigurationPropertiesScan
class BarometrApplication

fun main(args: Array<String>) {
    runApplication<BarometrApplication>(*args)
}
