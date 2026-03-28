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
    mainClass.set("com.smplkit.examples.ConfigShowcase")
}
