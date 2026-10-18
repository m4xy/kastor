plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":runtime"))
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.12")
    implementation("com.squareup:kotlinpoet:2.4.0")
    implementation("com.squareup:kotlinpoet-ksp:2.4.0")

    testImplementation(kotlin("test"))
    testImplementation("dev.zacsweers.kctfork:core:0.14.0")
    testImplementation("dev.zacsweers.kctfork:ksp:0.14.0")
}
