plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}
android {
    namespace = "de.fahrertagebuch.card.usb"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("test").resources.srcDir("../card-core/src/test/resources")
    publishing { singleVariant("release") { withSourcesJar() } }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":card-core"))
    testImplementation("junit:junit:4.13.2")
}
afterEvaluate {
    publishing { publications { create<MavenPublication>("release") { from(components["release"]) } } }
}
tasks.withType<Zip>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    eachFile { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("NOTICE")) { into("META-INF") }
}

// Publication metadata modified in 2026: project URL and neutral description; dependencies are unchanged.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Driver Card Library — Android USB")
            description.set("Android USB Host CCID transport for the Driver Card Library. Certificate trust is not evaluated. No original ERCA keys are bundled; applications supply Gen1 public recovery material.")
            url.set("https://github.com/Perrystack9/tachograph-driver-card-kotlin-clean")
            licenses {
                license {
                    name.set("Apache License, Version 2.0 (project-owned code)")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
        }
    }
}
