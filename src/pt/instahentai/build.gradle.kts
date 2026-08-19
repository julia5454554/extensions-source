plugins {
    id("com.android.application")
    id("kotlinx-serialization")
}

apply {
    from("$rootDir/common.gradle")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":lib-i18n"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
