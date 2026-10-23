plugins { kotlin("jvm"); `java-library`; `maven-publish` }
kotlin { jvmToolchain(17) }
java { withSourcesJar() }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    testImplementation("junit:junit:4.13.2")
}
publishing { publications { create<MavenPublication>("library") { from(components["java"]) } } }
tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("NOTICE")) { into("META-INF") }
    from(rootProject.file("licenses/BOUNCY-CASTLE.txt")) { into("META-INF/licenses") }
}

// Publication metadata modified in 2026: project URL and neutral description; dependencies are unchanged.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Driver Card Library — Kotlin Core")
            description.set("Driver card EF parsing, APDU/CCID protocols and signature mathematics. Certificate trust is not evaluated. No original ERCA keys are bundled; applications supply Gen1 public recovery material.")
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
