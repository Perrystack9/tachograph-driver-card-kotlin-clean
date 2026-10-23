plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "example.consumer"
    compileSdk = 35
    defaultConfig {
        applicationId = "example.consumer.drivercard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("de.fahrertagebuch:card-core:0.1.0-SNAPSHOT")
    implementation("de.fahrertagebuch:card-usb-android:0.1.0-SNAPSHOT")
    testImplementation("junit:junit:4.13.2")
}
// Evidence task: resolve only the published module coordinates and their transitive JARs.
tasks.register("integrationDependencies") {
    doLast {
        listOf("debugCompileClasspath", "debugRuntimeClasspath", "debugUnitTestRuntimeClasspath").forEach { name ->
            println("CONFIGURATION $name")
            configurations.getByName(name).resolvedConfiguration.resolvedArtifacts
                .sortedBy { it.moduleVersion.id.toString() }.forEach {
                    println("ARTIFACT ${it.moduleVersion.id} ${it.file.name}")
                }
        }
    }
}
