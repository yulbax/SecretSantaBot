plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "yulbax"
version = "3.0.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    implementation("org.telegram:telegrambots-longpolling:9.2.0")
    implementation("org.telegram:telegrambots-client:9.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("ch.qos.logback:logback-classic:1.5.29")
    implementation("org.jline:jline:3.25.1")

    // ========== DATABASE (SQLite + Exposed ORM) ==========
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.56.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
}

application {
    mainClass.set("io.github.yulbax.santabot.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
