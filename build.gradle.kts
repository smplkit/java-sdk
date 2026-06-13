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

// Customer-floor verification hook. Passing `-PjacksonFloorOverride=2.16.0`
// (used by the `test-min-floor` CI job) force-resolves every Jackson core +
// datatype dependency to that version, so we can prove the SDK still compiles
// and its tests pass at the declared lower bound. Without an override, normal
// resolution lets the open-ended range below pick the highest available. This
// keeps the published `[2.16.0,)` floor honest rather than aspirational.
val jacksonFloorOverride = findProperty("jacksonFloorOverride") as String?
if (jacksonFloorOverride != null) {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.fasterxml.jackson.core" ||
                requested.group == "com.fasterxml.jackson.datatype") {
                useVersion(jacksonFloorOverride)
            }
        }
    }
}

dependencies {
    // Generated client dependencies (Jackson-based, used by internal/generated/).
    // Declared as an open lower-bound range so consumers on older (but still
    // supported) Jackson can resolve the SDK — Jackson is commonly pinned by a
    // customer's Spring Boot BOM. 2.16.0 is the true floor: the generated
    // RFC3339InstantDeserializer uses jsr310 JavaTimeFeature, added in 2.16.0
    // (2.15.x fails to compile). Verified by the test-min-floor CI job. The
    // build itself resolves to the highest available; the published POM keeps
    // the range so the customer's MVS intersects ours.
    implementation("com.fasterxml.jackson.core:jackson-databind:[2.16.0,)")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:[2.16.0,)")
    implementation("org.openapitools:jackson-databind-nullable:0.2.10")
    compileOnly("jakarta.annotation:jakarta.annotation-api:3.0.0")
    // @ApiStatus.Internal markers on internal seams; CLASS-retention, compile-only
    // (never on the customer's runtime/transitive classpath).
    compileOnly("org.jetbrains:annotations:26.0.2")

    // JSON Logic evaluation for flags runtime
    implementation("io.github.jamsesso:json-logic-java:1.1.0")

    // Logging adapter dependencies — compileOnly so they're not transitive
    compileOnly("ch.qos.logback:logback-classic:1.5.34")
    compileOnly("org.slf4j:slf4j-api:2.0.18")
    compileOnly("org.apache.logging.log4j:log4j-core:2.26.0")
    compileOnly("org.apache.logging.log4j:log4j-api:2.26.0")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Test dependencies for adapter tests
    testImplementation("ch.qos.logback:logback-classic:1.5.34")
    testImplementation("org.slf4j:slf4j-api:2.0.18")
    testImplementation("org.apache.logging.log4j:log4j-core:2.26.0")
    testImplementation("org.apache.logging.log4j:log4j-api:2.26.0")
}

tasks.withType<Javadoc>().configureEach {
    // The com.smplkit.internal package (incl. internal.generated) is implementation
    // detail and must never appear on the published Javadoc surface (P1).
    exclude("com/smplkit/internal/**")
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        // The published artifact must always build a Javadoc jar; don't let
        // doclint strictness (which varies by JDK) fail the release.
        addStringOption("Xdoclint:none", "-quiet")
    }
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
            // Wrapper layer is at 100% line coverage and stays there. All other
            // smplkit SDKs (Python, TS, Go, C#) hold the same bar.
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
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
