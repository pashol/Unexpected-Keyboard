import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.io.FileOutputStream
import java.util.zip.ZipFile

plugins {
  id("com.android.application") version "8.13.2"
}

dependencies {
  // Following versions of androidx.window require sdk version 23
  implementation("androidx.window:window-java:1.4.0")
  implementation("androidx.core:core:1.16.0") // Version 1.17.0 available with sdk 36
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test:runner:1.6.2")
}

android {
  namespace = "juloo.keyboard2"
  compileSdkVersion = "android-36"

  defaultConfig {
    applicationId = "juloo.keyboard2.pashol"
    minSdk = 21
    targetSdk { version = release(36) }
    versionCode = 55
    versionName = "2.0.4"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    externalNativeBuild {
      ndkBuild {
        arguments += "NDK_APPLICATION_MK=vendor/Application.mk"
      }
    }
  }

  sourceSets {
    named("main") {
      manifest.srcFile("AndroidManifest.xml")
      java.srcDirs("srcs/juloo.keyboard2", "vendor/cdict/java/juloo.cdict", "vendor/latinime/java")
      res.srcDirs("res", "build/generated-resources")
      assets.srcDirs("assets", "build/generated-assets")
    }

    named("test") {
      java.srcDirs("test")
    }

    named("androidTest") {
      java.srcDirs("androidTest")
      res.srcDirs("androidTest/res")
      assets.srcDirs("test/fixtures")
    }
  }

  externalNativeBuild {
    ndkBuild {
      path = file("vendor/Android.mk")
    }
  }

  signingConfigs {
    // Debug builds will always be signed. If no environment variables are set, a default
    // keystore will be initialized by the task initDebugKeystore and used. This keystore
    // can be uploaded to GitHub secrets by following instructions in CONTRIBUTING.md
    // in order to always receive correctly signed debug APKs from the CI.
    named("debug") {
      storeFile = file(System.getenv("DEBUG_KEYSTORE") ?: "debug.keystore")
      storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD") ?: "debug0"
      keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "debug"
      keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "debug0"
    }

    create("release") {
      val ks = System.getenv("RELEASE_KEYSTORE")
      if (ks != null) {
        storeFile = file(ks)
        storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("RELEASE_KEY_ALIAS")
        keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    named("release") {
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro")
      isShrinkResources = true
      isDebuggable = false
      resValue("string", "app_name", "@string/app_name_release")
      signingConfig = signingConfigs["release"]
    }

    named("debug") {
      isMinifyEnabled = false
      isShrinkResources = false
      isDebuggable = true
      applicationIdSuffix = ".debug"
      resValue("string", "app_name", "@string/app_name_debug")
      resValue("bool", "debug_logs", "true")
      signingConfig = signingConfigs["debug"]
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
}


// This raises an error with an informative message instead of the confusing
// ndk-build errors that occur when submodules are not initialized.
gradle.projectsEvaluated {
  if (!file("vendor/cdict/java").exists())
    throw GradleException("Git submodules not initialized. Run 'git submodule update --init'")
}

val buildKeyboardFont by tasks.registering(Exec::class) {
  val `in` = projectDir.resolve("srcs/special_font")
  val out = layout.projectDirectory.file("assets/special_font.ttf")
  inputs.dir(`in`)
  outputs.file(out)
  doFirst { println("\nBuilding assets/special_font.ttf") }
  workingDir = `in`
  val svgFiles = `in`.listFiles()!!.filter {
    it.isFile && it.name.endsWith(".svg")
  }.toTypedArray()
  commandLine("fontforge", "-lang=ff", "-script", "build.pe", out.asFile.absolutePath, *svgFiles)
}

val genEmojis by tasks.registering(Exec::class) {
  doFirst { println("\nGenerating res/raw/emojis.txt") }
  workingDir = projectDir
  commandLine("python", "gen_emoji.py")
}

val genLayoutsList by tasks.registering(Exec::class) {
  inputs.dir(projectDir.resolve("srcs/layouts"))
  outputs.file(projectDir.resolve("res/values/layouts.xml"))
  doFirst { println("\nGenerating res/values/layouts.xml") }
  workingDir = projectDir
  commandLine("python", "gen_layouts.py")
}

val genMethodXml by tasks.registering(Exec::class) {
  val out = projectDir.resolve("res/xml/method.xml")
  inputs.file(projectDir.resolve("gen_method_xml.py"))
  inputs.file(projectDir.resolve("res/values/dictionaries.xml"))
  outputs.file(out)
  doFirst { println("\nGenerating res/xml/method.xml") }
  doFirst { standardOutput = FileOutputStream(out) }
  workingDir = projectDir
  commandLine("python", "gen_method_xml.py")
}

val checkKeyboardLayouts by tasks.registering(Exec::class) {
  inputs.dir(projectDir.resolve("srcs/layouts"))
  inputs.file(projectDir.resolve("srcs/juloo.keyboard2/KeyValue.java"))
  outputs.file(projectDir.resolve("check_layout.output"))
  doFirst { println("\nChecking layouts") }
  workingDir = projectDir
  commandLine("python", "check_layout.py")
}

val compileComposeSequences by tasks.registering(Exec::class) {
  val `in` = projectDir.resolve("srcs/compose")
  val out = projectDir.resolve("srcs/juloo.keyboard2/ComposeKeyData.java")
  inputs.dir(`in`)
  outputs.file(out)
  doFirst { println("\nGenerating $out") }
  val sequences = `in`.listFiles { it: File ->
    !it.name.endsWith(".py") && !it.name.endsWith(".md")
  }!!.map { it.absolutePath }.toTypedArray()
  workingDir = projectDir
  commandLine("python", `in`.resolve("compile.py").absolutePath, *sequences)
  doFirst { standardOutput = FileOutputStream(out) }
}

tasks.withType(Test::class).configureEach {
  dependsOn(genLayoutsList, checkKeyboardLayouts, compileComposeSequences, genMethodXml)
}

val verifyLanguagePackFixture by tasks.registering(Exec::class) {
  group = "verification"
  description = "Regenerates and verifies the LatinIME fixture from the pinned AOSP checkout."
  workingDir = projectDir
  commandLine("python3", "-m", "unittest", "tools.prediction.integration_test_build_language_pack")
}

val copyLatinimeNotice by tasks.registering(Copy::class) {
  from("vendor/latinime/NOTICE")
  into("build/generated-assets/latinime")
}

val copyLatinimeDevelopmentFixture by tasks.registering(Exec::class) {
  inputs.file("test/fixtures/latinime/language_packs.json")
  inputs.dir("test/fixtures/latinime")
  outputs.dir("build/generated-assets/latinime")
  workingDir = projectDir
  commandLine(
      "python3", "tools/prediction/copy_development_language_packs.py",
      "--registry", "test/fixtures/latinime/language_packs.json",
      "--output", "build/generated-assets/latinime",
  )
}

tasks.named("preBuild") {
  dependsOn(copyLatinimeDevelopmentFixture)
}

val verifyReleaseEnvironment by tasks.registering(Exec::class) {
  group = "verification"
  description = "Checks the signing, SDK, and NDK prerequisites for release verification."
  workingDir = projectDir
  commandLine("/bin/sh", "tools/verify_release_environment.sh")
}

tasks.configureEach {
  if (name == "packageRelease") {
    dependsOn(verifyReleaseEnvironment)
  }
}

val verifyLatinimeNative by tasks.registering(Exec::class) {
  group = "verification"
  description = "Verifies every ABI in the assembled release APK has compliant LatinIME native code."
  dependsOn("assembleRelease")
  workingDir = projectDir
  commandLine("tools/verify_latinime_native.sh")
}

val verifyReleasePackaging by tasks.registering(Exec::class) {
  group = "verification"
  description = "Assembles and verifies the LatinIME NOTICE in the release APK."
  dependsOn(verifyReleaseEnvironment, "assembleRelease")
  workingDir = projectDir
  commandLine("python3", "tools/verify_release_notice.py")
}

val verifyLatinimeJniFacade by tasks.registering {
  group = "verification"
  description = "Verifies the API-21 facade and JNI-only descriptor class in the release APK."
  dependsOn("assembleRelease")
  inputs.file("srcs/juloo.keyboard2/prediction/LatinimeDictionary.java")
  inputs.file("build/outputs/apk/release/Unexpected-Keyboard-release.apk")
  doLast {
    val source = inputs.files.single { it.name == "LatinimeDictionary.java" }
    val apk = inputs.files.single { it.name == "Unexpected-Keyboard-release.apk" }
    check(!source.readText().contains(".codePoints()"))
    ZipFile(apk).use { archive ->
      val present = archive.entries().asSequence()
          .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
          .any { String(archive.getInputStream(it).readBytes(), Charsets.ISO_8859_1)
              .contains("com/android/inputmethod/latin/utils/WordInputEventForPersonalization") }
      check(present) { "Release APK is missing WordInputEventForPersonalization" }
    }
  }
}

tasks.named("check") {
  dependsOn(verifyLanguagePackFixture)
  dependsOn(verifyLatinimeNative)
  dependsOn(verifyReleasePackaging)
  dependsOn(verifyLatinimeJniFacade)
}

val initDebugKeystore by tasks.registering(Exec::class) {
  doFirst { println("Initializing default debug keystore") }
  isEnabled = !file("debug.keystore").exists()
  // A shell script might be needed if this line requires input from the user
  commandLine("keytool", "-genkeypair", "-dname", "cn=d, ou=e, o=b, c=ug", "-alias", "debug", "-keypass", "debug0", "-keystore", "debug.keystore", "-keyalg", "rsa", "-storepass", "debug0", "-validity", "10000")
}

// latn_qwerty_us is used as a raw resource by the custom layout option.
val copyRawQwertyUS by tasks.registering(Copy::class) {
  from("srcs/layouts/latn_qwerty_us.xml")
  into("build/generated-resources/raw")
}

val copyLayoutDefinitions by tasks.registering(Copy::class) {
  from("srcs/layouts")
  include("*.xml")
  into("build/generated-resources/xml")
}

tasks.named("preBuild") {
  dependsOn(initDebugKeystore, copyRawQwertyUS, copyLayoutDefinitions, copyLatinimeNotice)
  // 'mustRunAfter' defines ordering between tasks (which is required by
  // Gradle) but doesn't create a dependency. These rules update files that are
  // checked in the repository that don't need to be updated during regular
  // builds.
  mustRunAfter(genEmojis, genLayoutsList, compileComposeSequences, genMethodXml)
}
