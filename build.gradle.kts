plugins {
    kotlin("jvm") version "2.3.20"
    `maven-publish`
}

group   = "com.logfriends"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))

    // ByteBuddy - 바이트코드 계측
    implementation("net.bytebuddy:byte-buddy:1.15.11")
    implementation("net.bytebuddy:byte-buddy-agent:1.15.11")

    // Spring Boot AutoConfigure - 컴파일 전용 (Spring 없는 환경도 지원)
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.5.9")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.junit.platform:junit-platform-launcher:1.10.1")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("logfriends.benchmark", System.getProperty("logfriends.benchmark", "false"))
    if (System.getProperty("logfriends.benchmark") == "true") {
        testLogging.showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(21)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
