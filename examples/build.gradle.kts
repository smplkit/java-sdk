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
}
