plugins { id("com.android.application") }
android {
    namespace = "com.betternarrator"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.betternarrator"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0-preview1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { getByName("release") { proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    androidResources { noCompress += listOf("onnx", "bin") }
}
val checkOfflineVoiceAssets = tasks.register("checkOfflineVoiceAssets") {
    doLast {
        listOf("libs/sherpa-onnx-2.0.2.aar", "src/main/assets/kokoro/model.int8.onnx",
            "src/main/assets/kokoro/voices.bin", "src/main/assets/kokoro/tokens.txt",
            "src/main/assets/kokoro/lexicon-us-en.txt", "src/main/assets/kitten/model.int8.onnx",
            "src/main/assets/kitten/voices.bin", "src/main/assets/kitten/tokens.txt",
            "src/main/assets/kitten/lexicon-en-us.txt").forEach {
            check(file(it).isFile) { "Missing offline voice artifact: $it. Run: python scripts/prepare-offline-voice.py from the project root." }
        }
    }
}
tasks.named("preBuild") { dependsOn(checkOfflineVoiceAssets) }
dependencies {
    implementation(files("libs/sherpa-onnx-2.0.2.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
