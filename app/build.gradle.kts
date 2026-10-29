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
// Gradle sync/IDE import and tests (testDebugUnitTest, compile*) still work without a .env.
// Exact names only: a prefix match also hits internal tasks such as
// bundleDebugClassesToCompileJar, which unit tests depend on.
val apkTaskName = Regex("^(assemble|bundle|install)(Debug|Release)?$")
gradle.taskGraph.whenReady {
    val buildsApk = allTasks.any { task ->
        task.project == project && apkTaskName.matches(task.name)
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
        versionCode = 9
        versionName = "1.6.3"

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

    // Exported Room schemas, read by MigrationTestHelper in androidTest
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
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
    androidTestImplementation(libs.room.testing)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // OkHttp Client
    implementation(libs.okhttp)

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}