plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

import java.util.Properties
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest

// The branch whose update.json the app reads. The release workflow sets APP_GIT_BRANCH,
// since a CI checkout is not always on a named branch.
val gitBranch: String = System.getenv("APP_GIT_BRANCH")?.takeIf { it.isNotBlank() } ?: try {
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
fun env(key: String): String? = envProperties.getProperty(key)?.takeIf { it.isNotBlank() && it != "\"\"" }

// Uploads go through the ingest endpoint (INGEST_URL) when set: the APK then carries no
// database key at all. Without it the app falls back to the direct gateway, which needs
// GATEWAY_URL / API_KEY / DB_NAME compiled in — fine for local testing, not for release.
val usesIngest = env("INGEST_URL") != null
val missingEnvKeys = if (usesIngest) emptyList() else
    listOf("GATEWAY_URL", "API_KEY", "DB_NAME").filter { env(it) == null }

// Release signing from .env. Every APK that updates an installed app must carry the SAME
// key as the installed one, or Android refuses it ("package appears to be invalid").
val signingStore = env("SIGNING_STORE_FILE")?.trim('"')?.let { rootProject.file(it) }

// An APK built without these silently fails every sync, so refuse to build one.
// Gradle sync/IDE import and tests (testDebugUnitTest, compile*) still work without a .env.
// Exact names only: a prefix match also hits internal tasks such as
// bundleDebugClassesToCompileJar, which unit tests depend on.
val apkTaskName = Regex("^(assemble|bundle|install)(Debug|Release)?$")
gradle.taskGraph.whenReady {
    val buildsApk = allTasks.any { task ->
        task.project == project && apkTaskName.matches(task.name)
    }
    val buildsRelease = allTasks.any { task ->
        task.project == project && (task.name == "assembleRelease" || task.name == "publishUpdate")
    }
    if (buildsRelease && signingStore?.exists() != true) {
        throw GradleException(
            "Release APKs must be signed: set SIGNING_STORE_FILE (and passwords) in .env — see .env.example."
        )
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
        versionCode = 10
        versionName = "1.6.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")

        buildConfigField("String", "INGEST_URL", env("INGEST_URL") ?: "\"\"")
        buildConfigField("String", "INGEST_APP_TOKEN", env("INGEST_APP_TOKEN") ?: "\"\"")
        // With an ingest endpoint the gateway key is NOT compiled in (it could be extracted)
        buildConfigField("String", "GATEWAY_URL", if (usesIngest) "\"\"" else env("GATEWAY_URL") ?: "\"\"")
        buildConfigField("String", "API_KEY", if (usesIngest) "\"\"" else env("API_KEY") ?: "\"\"")
        buildConfigField("String", "DB_NAME", if (usesIngest) "\"\"" else env("DB_NAME") ?: "\"\"")
    }

    signingConfigs {
        create("release") {
            if (signingStore?.exists() == true) {
                storeFile = signingStore
                storePassword = env("SIGNING_STORE_PASSWORD")?.trim('"')
                keyAlias = env("SIGNING_KEY_ALIAS")?.trim('"')
                keyPassword = env("SIGNING_KEY_PASSWORD")?.trim('"')
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }


    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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

/**
 * Publish an over-the-air update: build the signed release APK, copy it to
 * releases/DataHarvester-<version>.apk (gitignored) and write update.json FROM that file
 * (version, size, SHA-256, signing certificate). update.json can then never advertise a version
 * the APK is not, and a versioned file name means no cache can serve an older APK.
 *
 * By default apkUrl points at the GitHub Release v<version> of GITHUB_REPO: upload the
 * APK there first (the task prints the command), THEN commit and push update.json.
 * Pushing update.json first would offer phones a file that does not exist yet.
 * UPDATE_BASE_URL overrides this with any other direct-download folder.
 */
tasks.register("publishUpdate") {
    group = "distribution"
    // The unit tests include MigrationCoverageTest: no update ships a schema version
    // without the migration that carries existing records over
    dependsOn("testReleaseUnitTest", "assembleRelease")
    doLast {
        val versionName = android.defaultConfig.versionName!!
        val versionCode = android.defaultConfig.versionCode!!
        val apk = layout.buildDirectory.file("outputs/apk/release/DATAra-Harvester-release.apk").get().asFile
        check(apk.exists()) { "Signed release APK not found at $apk" }

        val name = "DataHarvester-$versionName.apk"
        val target = rootProject.file("releases/$name")
        target.parentFile.mkdirs()
        apk.copyTo(target, overwrite = true)

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        val sha256 = hex(MessageDigest.getInstance("SHA-256").digest(target.readBytes()))
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            signingStore!!.inputStream().use { load(it, env("SIGNING_STORE_PASSWORD")!!.trim('"').toCharArray()) }
        }
        val cert = keyStore.getCertificate(env("SIGNING_KEY_ALIAS")!!.trim('"'))
        val certSha256 = hex(MessageDigest.getInstance("SHA-256").digest(cert.encoded))

        val repo = env("GITHUB_REPO")?.trim('"') ?: "saybbbb/Data-Harvester"
        val tag = "v$versionName"
        val baseUrl = (env("UPDATE_BASE_URL")?.trim('"')
            ?: "https://github.com/$repo/releases/download/$tag").trimEnd('/')
        rootProject.file("update.json").writeText(
            """
            |{
            |  "latestVersionCode": $versionCode,
            |  "latestVersionName": "$versionName",
            |  "apkUrl": "$baseUrl/$name",
            |  "sizeBytes": ${target.length()},
            |  "sha256": "$sha256",
            |  "signingCertSha256": "$certSha256"
            |}
            |""".trimMargin()
        )
        println("Published $name ($versionCode, ${target.length()} bytes)\n  sha256 $sha256\n  cert   $certSha256")
        println(
            """
            |Next:
            |  1. gh release create $tag "releases/$name" --repo $repo --title "Data Harvester $versionName"
            |     (or on GitHub: Releases > Draft a new release, tag $tag, attach releases/$name)
            |  2. Check that $baseUrl/$name downloads
            |  3. Commit and push update.json (phones are offered the update from then on)
            |""".trimMargin()
        )
    }
}