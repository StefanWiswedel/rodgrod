// Pure Kotlin/JVM module containing all session logic that does not need Android:
// VAD state machine, scheduler, session composer, runner, scorers, storage (SQL via a tiny Db interface).
// It is compiled both by the root JVM build (for fast local tests) and by the Android build.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Android ships org.json in the framework; on the JVM we need the library.
    compileOnly("org.json:json:20250517")
    testImplementation("org.json:json:20250517")
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Content validation tests read ../content
    systemProperty("rodgrod.contentDir", rootDir.resolve("content").absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
