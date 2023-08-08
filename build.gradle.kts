plugins {
    id("java")
    id("application")
}

group = "com.github.brickwall2900"
version = "1.0-SNAPSHOT"

val mainClassName = "MCLauncher"
if (!hasProperty("mainClass")) {
    extra["mainClass"] = mainClassName
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    implementation("com.google.code.gson:gson:2.10.1")

}

tasks.test {
    useJUnitPlatform()
}

task("fatJar", type = Jar::class) {
    // manifest Main-Class attribute is optional.
    // (Used only to provide default main class for executable jar)
    manifest {
        attributes["Main-Class"] = mainClassName // fully qualified class name of default main class
    }
    archiveBaseName = rootProject.name
    tasks.withType(Jar::class){
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks["jar"] as CopySpec)
}