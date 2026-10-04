import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "1.9.23"
    `java-library`
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "com.errorgap"
version = "0.4.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
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

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    coordinates("com.errorgap", "errorgap-android", version.toString())
    pom {
        name.set("Errorgap Android / Kotlin")
        description.set("Kotlin notifier for Errorgap error tracking.")
        url.set("https://github.com/errorgaphq/errorgap-android")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("errorgap")
                name.set("Errorgap")
                email.set("support@errorgap.com")
            }
        }
        scm {
            url.set("https://github.com/errorgaphq/errorgap-android")
            connection.set("scm:git:https://github.com/errorgaphq/errorgap-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/errorgaphq/errorgap-android.git")
        }
    }
}
