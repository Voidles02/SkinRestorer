plugins {
    kotlin("jvm") version "2.2.20"
}

group = "com.KDI"
version = "12-Bh-Alpa.v1.23mc"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}