plugins {
    `java-library`
    jacoco
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "com.smplkit"
// Version is set by CI from the git tag; this default is for local development only
version = findProperty("projectVersion") as String? ?: "0.0.0-local"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Generated client dependencies (Jackson-based, used by internal/generated/)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.1")
    implementation("org.openapitools:jackson-databind-nullable:0.2.6")
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")

    // JSON Logic evaluation for flags runtime
    implementation("io.github.jamsesso:json-logic-java:1.0.7")

    // Logging adapter dependencies — compileOnly so they're not transitive
    compileOnly("ch.qos.logback:logback-classic:1.4.14")
    compileOnly("org.slf4j:slf4j-api:2.0.12")
    compileOnly("org.apache.logging.log4j:log4j-core:2.23.1")
    compileOnly("org.apache.logging.log4j:log4j-api:2.23.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Test dependencies for adapter tests
    testImplementation("ch.qos.logback:logback-classic:1.4.14")
    testImplementation("org.slf4j:slf4j-api:2.0.12")
    testImplementation("org.apache.logging.log4j:log4j-core:2.23.1")
    testImplementation("org.apache.logging.log4j:log4j-api:2.23.1")
}

tasks.test {
    useJUnitPlatform()
    // Allow env var manipulation via reflection in tests (Java 17+ module system)
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(
        sourceSets.main.get().output.asFileTree.matching {
            exclude("com/smplkit/internal/generated/**")
        }
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates("com.smplkit", "smplkit-sdk", version.toString())

    pom {
        name.set("smplkit Java SDK")
        description.set("Official Java SDK for the smplkit platform.")
        url.set("https://github.com/smplkit/java-sdk")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("smplkit")
                name.set("Smpl Solutions LLC")
                url.set("https://smplkit.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/smplkit/java-sdk.git")
            developerConnection.set("scm:git:ssh://github.com/smplkit/java-sdk.git")
            url.set("https://github.com/smplkit/java-sdk")
        }
    }
}
