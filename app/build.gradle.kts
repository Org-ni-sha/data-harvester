plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

import java.util.Properties
import java.io.FileInputStream

val gitBranch: String = try {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--abbrev-ref", "HEAD"))
    val result = process.inputStream.bufferedReader().readText().trim()
    if (result.isEmpty()) "main" else result
} catch (e: Exception) {
    "main"
}

// Cloud sync secrets come from the gitignored .env at the repo root (see .env.example).
val envProperties = Properties().apply {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) FileInputStream(envFile).use { load(it) }
}
val missingEnvKeys = listOf("GATEWAY_URL", "API_KEY", "DB_NAME")
    .filter { envProperties.getProperty(it).isNullOrBlank() }

// An APK built without these silently fails every sync, so refuse to build one.
// Gradle sync/IDE import still works without a .env.
gradle.taskGraph.whenReady {
    val buildsApk = allTasks.any { task ->
        task.project == project && listOf("assemble", "bundle", "install").any { task.name.startsWith(it) }
    }
    if (buildsApk && missingEnvKeys.isNotEmpty()) {
        throw GradleException(
            "Missing ${missingEnvKeys.joinToString()} in .env — copy .env.example to .env and fill it in."
        )
    }
}

android {
    namespace = "com.capstone.dataharvester"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.capstone.dataharvester"
        minSdk = 23
        targetSdk = 36
        versionCode = 8
        versionName = "1.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")

        buildConfigField("String", "GATEWAY_URL", envProperties.getProperty("GATEWAY_URL") ?: "\"\"")
        buildConfigField("String", "API_KEY", envProperties.getProperty("API_KEY") ?: "\"\"")
        buildConfigField("String", "DB_NAME", envProperties.getProperty("DB_NAME") ?: "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

base {
    archivesName.set("DATAra-Harvester")
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.cardview)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // OkHttp Client
    implementation(libs.okhttp)

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}