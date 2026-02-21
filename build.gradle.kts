plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.4" apply false
}

// Detekt configuration
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        allRules = true
    }
    
    // Gera relatório HTML
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reports {
            html.required.set(true)
            xml.required.set(true)
        }
    }
}
