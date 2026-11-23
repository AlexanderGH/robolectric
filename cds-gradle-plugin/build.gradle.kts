import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { `kotlin-dsl` }

group = "org.robolectric"

gradlePlugin {
  plugins {
    register("robolectricCds") {
      id = "org.robolectric.cds"
      displayName = "Robolectric CDS"
      description =
        "Speeds up the first Robolectric test in every test JVM with a JDK class data sharing archive"
      implementationClass = "org.robolectric.cds.gradle.RobolectricCdsPlugin"
    }
  }
}

dependencies {
  testImplementation(libs.junit4)
  testImplementation(libs.truth)
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

tasks.validatePlugins {
  failOnWarning = true
  enableStricterValidation = true
}
