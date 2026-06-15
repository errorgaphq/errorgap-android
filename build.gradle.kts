plugins {
    kotlin("jvm") version "1.9.23"
    `java-library`
    `maven-publish`
}

group = "io.errorgap"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Errorgap Android / Kotlin")
                description.set("Kotlin notifier for Errorgap error tracking.")
                url.set("https://gitlab.jgrubbs.net/jGRUBBS/errorgap-android")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
