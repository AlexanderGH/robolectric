plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.robolectric.android.project)
  id("org.robolectric.cds")
}

// Opt in with -Probolectric.cds=true: the test JVMs get a CDS archive of SDK 36's classes, or of
// the SDK in -Probolectric.cds.sdk.
robolectricCds {
  enabled = providers.gradleProperty("robolectric.cds").map { it.toBoolean() }.orElse(false)
  sdk = providers.gradleProperty("robolectric.cds.sdk").map { it.toInt() }.orElse(36)
}

android {
  compileSdk = 37
  namespace = "org.robolectric.integrationtests.androidx"

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  testOptions {
    targetSdk = 36
    unitTests.isIncludeAndroidResources = true
  }
}

dependencies {
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.window)

  // Testing dependencies
  testImplementation(project(":testapp"))
  testImplementation(project(":robolectric"))
  testImplementation(libs.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.recyclerview)
  testImplementation(libs.material)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.espresso.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.window.core)
  testImplementation(libs.androidx.window.testing)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.truth)
}
