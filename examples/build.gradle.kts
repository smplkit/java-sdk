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
}

application {
    mainClass.set(providers.gradleProperty("mainClass").getOrElse("com.smplkit.examples.ConfigRuntimeShowcase"))
    // Enable Java assertions (-ea) so the `assert` statements in our
    // showcases actually fire. Without this, the JVM runs with assertions
    // disabled by default and the showcases report success even when
    // their assertions would have failed.
    applicationDefaultJvmArgs = listOf("-ea")
}
