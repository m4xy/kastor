plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":runtime"))
    ksp(project(":processor"))

    testImplementation(kotlin("test"))
}
