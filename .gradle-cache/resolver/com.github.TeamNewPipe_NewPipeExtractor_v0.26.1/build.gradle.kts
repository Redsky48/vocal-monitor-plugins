
plugins { id("java-library") }
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1")
}
tasks.register<Copy>("collectJars") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("collected-jars"))
}
