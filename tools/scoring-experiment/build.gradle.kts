plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
}
application {
    mainClass.set("dk.rodgrod.tools.ScoringExperimentKt")
}
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    standardInput = System.`in`
}
