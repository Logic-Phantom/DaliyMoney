// DailyRoll 순수 Kotlin 코어(도메인·파서·포맷터) 빠른 JVM 테스트용 독립 빌드.
// Android SDK 없이 도메인 코드가 android.* 에 의존하지 않는지도 함께 검증한다.
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "dailyroll-core-jvm"
