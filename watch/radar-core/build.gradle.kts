plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.org.json)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("blindside.vectors", rootProject.file("../protocol/vectors/vectors.json").absolutePath)
}
