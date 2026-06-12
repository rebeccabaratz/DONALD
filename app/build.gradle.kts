import java.util.Base64
import java.net.URL
import java.net.HttpURLConnection

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.ruengagent.jpxqms"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      val localKeystore = file("${rootDir}/debug.keystore")
      if (!localKeystore.exists()) {
        val base64File = file("${rootDir}/debug.keystore.base64")
        val base64Content = if (base64File.exists()) {
          base64File.readText().trim()
        } else {
          "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"
        }
        try {
          localKeystore.writeBytes(Base64.getDecoder().decode(base64Content))
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
      storeFile = localKeystore
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Dynamically generate the .env file from system environment variables at build time
val envFile = file("${rootDir}/.env")
val geminiApiKeyFromSystem = System.getenv("GEMINI_API_KEY")
if (geminiApiKeyFromSystem != null && geminiApiKeyFromSystem.trim().isNotEmpty() && geminiApiKeyFromSystem.trim() != "MY_GEMINI_API_KEY") {
    val apiKeyCleaned = geminiApiKeyFromSystem.trim()
    val masked = if (apiKeyCleaned.length > 8) {
        "${apiKeyCleaned.take(4)}...${apiKeyCleaned.takeLast(4)} (length: ${apiKeyCleaned.length})"
    } else {
        "*** (length: ${apiKeyCleaned.length})"
    }
    println("BUILD CONFIG: System environment GEMINI_API_KEY detected! Writing to .env: $masked")
    envFile.writeText("GEMINI_API_KEY=$apiKeyCleaned\n")
} else {
    println("BUILD CONFIG: System environment GEMINI_API_KEY is null, empty or placeholder in OS environment.")
    if (!envFile.exists() || envFile.readText().contains("MY_GEMINI_API_KEY")) {
        println("BUILD CONFIG: .env does not exist or contains placeholder. Writing fallback.")
        envFile.writeText("GEMINI_API_KEY=MY_GEMINI_API_KEY\n")
    } else {
        println("BUILD CONFIG: Existing .env file found and preserved: ${envFile.readLines().firstOrNull()?.take(15)}...")
    }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.coil.compose)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register<Copy>("packageApkRaw") {
    val sourceFile = file("${rootDir}/.build-outputs/app-debug.apk")
    val fallbackFile = file("${rootDir}/app/build/outputs/apk/debug/app-debug.apk")
    val finalSource = if (sourceFile.exists() && fallbackFile.exists()) {
        if (fallbackFile.lastModified() > sourceFile.lastModified()) fallbackFile else sourceFile
    } else if (fallbackFile.exists()) {
        fallbackFile
    } else if (sourceFile.exists()) {
        sourceFile
    } else {
        null
    }

    if (finalSource != null) {
        from(finalSource.parentFile) {
            include(finalSource.name)
            rename { "app-debug.apk" }
        }
    } else {
        from("${rootDir}/app/build/outputs/apk/debug") {
            include("app-debug.apk")
        }
    }
    into(rootDir)
}

tasks.register<Zip>("packageApkZip") {
    archiveFileName.set("app-debug.zip")
    destinationDirectory.set(rootDir)
    
    val sourceFile = file("${rootDir}/.build-outputs/app-debug.apk")
    val fallbackFile = file("${rootDir}/app/build/outputs/apk/debug/app-debug.apk")
    val finalSource = if (sourceFile.exists() && fallbackFile.exists()) {
        if (fallbackFile.lastModified() > sourceFile.lastModified()) fallbackFile else sourceFile
    } else if (fallbackFile.exists()) {
        fallbackFile
    } else if (sourceFile.exists()) {
        sourceFile
    } else {
        null
    }

    if (finalSource != null) {
        from(finalSource.parentFile) {
            include(finalSource.name)
            rename { "app-debug.apk" }
        }
    } else {
        from("${rootDir}/app/build/outputs/apk/debug") {
            include("app-debug.apk")
        }
    }
}

tasks.register("splitApkBase64") {
    doLast {
        val rootDirFile = rootDir
        val sourceFile = file("${rootDirFile}/app-debug.apk")
        if (!sourceFile.exists()) {
            println("No app-debug.apk found at root!")
            return@doLast
        }
        val bytes = sourceFile.readBytes()
        val encoder = Base64.getEncoder()
        val base64String = encoder.encodeToString(bytes)
        
        val chunkSize = 10000000
        val outputDir = file("${rootDirFile}/apk_chunks")
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        
        var index = 1
        var offset = 0
        while (offset < base64String.length) {
            val end = Math.min(offset + chunkSize, base64String.length)
            val chunk = base64String.substring(offset, end)
            val partFile = file("${outputDir}/apk_part_${index}.txt")
            partFile.writeText(chunk)
            println("Created apk_part_${index}.txt of size ${partFile.length()} bytes")
            index++
            offset += chunkSize
        }
        println("All done! Split into ${index - 1} text chunks in the 'apk_chunks' folder.")
    }
}

tasks.register("uploadApkToHost") {
    doLast {
        val rootDirFile = rootDir
        val sourceFile = file("${rootDirFile}/app-debug.apk")
        if (!sourceFile.exists()) {
            println("No app-debug.apk found at root!")
            return@doLast
        }
        println("Uploading app-debug.apk to transfer.sh...")
        try {
            val connection = URL("https://transfer.sh/app-debug.apk").openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            sourceFile.inputStream().use { input ->
                connection.outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            
            val responseCode = connection.responseCode
            if (responseCode == 200 || responseCode == 201) {
                val downloadUrl = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                println("SUCCESS: APP_DOWNLOAD_URL = $downloadUrl")
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                println("Failed to upload to transfer.sh: $responseCode - $errorResponse")
            }
        } catch (e: Exception) {
            println("Error uploading: ${e.message}")
        }
    }
}

tasks.register("uploadApkCurl") {
    doLast {
        val rootDirFile = rootDir
        val sourceFile = file("${rootDirFile}/app-debug.apk")
        if (!sourceFile.exists()) {
            println("No app-debug.apk found at root!")
            return@doLast
        }
        
        println("File app-debug.apk found. Size: ${sourceFile.length()} bytes.")
        println("Attempting upload using curl to file.io...")
        
        // 1. Upload to tmpfiles.org
        try {
            val process = ProcessBuilder("curl", "-m", "50", "-k", "-F", "file=@${sourceFile.absolutePath}", "https://tmpfiles.org/api/v1/upload")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            println("=== Tmpfiles.org Response ===")
            println(output.trim())
            println("=============================\n")
        } catch (e: Exception) {
            println("Tmpfiles.org upload failed: ${e.message}")
        }
    }
}




