plugins {
    kotlin("jvm") version "2.2.0"
}

val moduleSrc = rootDir.resolve("../../modules/dailyroll-native/android/src")

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDirs(
                moduleSrc.resolve("main/java/com/dailyroll/core/domain"),
                moduleSrc.resolve("main/java/com/dailyroll/core/money"),
            )
        }
        test {
            // 도메인 테스트만(저장소 테스트는 Robolectric 이 필요해 Android 빌드에서 실행)
            kotlin.srcDirs(moduleSrc.resolve("test/java/com/dailyroll/core/domain"))
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
