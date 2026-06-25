plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(rootProject)
    // logback-classic is compileOnly in the SDK; examples need it at runtime so that
    // ServiceLoader can load Slf4jLogbackAdapter.  Log4j2 is not included here — the
    // loadAdaptersFromProviders catch block skips it gracefully when the jar is absent.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.35")
    runtimeOnly("org.slf4j:slf4j-api:2.0.18")
}

application {
    mainClass.set(providers.gradleProperty("mainClass").getOrElse("com.smplkit.examples.ConfigRuntimeShowcase"))
    // Enable Java assertions (-ea) so the `assert` statements in our
    // showcases actually fire. Without this, the JVM runs with assertions
    // disabled by default and the showcases report success even when
    // their assertions would have failed.
    applicationDefaultJvmArgs = listOf("-ea")
}
