package org.robolectric.cds.gradle

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.registerIfAbsent
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

/** Configures the `org.robolectric.cds` plugin. */
abstract class RobolectricCdsExtension {
  /** Whether test tasks use a CDS archive. Defaults to true. */
  abstract val enabled: Property<Boolean>

  /**
   * The SDK to archive the Android framework classes of. The first sandbox of this SDK in each test
   * JVM benefits. Without it the plugin does nothing.
   */
  abstract val sdk: Property<Int>

  /**
   * Where the archives are shared. Defaults to `caches/robolectric-cds` in the Gradle user home.
   */
  abstract val cacheDir: DirectoryProperty

  /** Extra JVM arguments for the training run. */
  abstract val trainingJvmArgs: ListProperty<String>
}

/**
 * Speeds up the first Robolectric test in each test JVM with a static JDK class data sharing (CDS)
 * archive of the classes that Robolectric's sandbox defines, such as the Android framework.
 *
 * Before a [Test] task runs, the plugin finds or creates the archive for its JDK, Robolectric
 * version and [RobolectricCdsExtension.sdk], and adds it to the test JVMs' arguments. Creating one
 * runs a small Robolectric test once and dumps the classes it loaded. Without an archive, the tests
 * run as they would without the plugin.
 *
 * The archive does not change what tests do, so it is not an input of the test task: it does not
 * affect up-to-date checks or the build cache.
 */
class RobolectricCdsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create<RobolectricCdsExtension>("robolectricCds")
    extension.enabled.convention(true)
    extension.cacheDir.convention(
      project.layout.dir(
        project.provider { File(project.gradle.gradleUserHomeDir, "caches/robolectric-cds") },
      ),
    )

    val service =
      project.gradle.sharedServices.registerIfAbsent(
        "robolectricCdsArchives",
        CdsArchiveService::class,
      ) {}

    val enabled = extension.enabled
    val sdk = extension.sdk
    val cacheDir = extension.cacheDir
    val extraTrainingJvmArgs = extension.trainingJvmArgs
    project.tasks.withType<Test>().configureEach {
      usesService(service)
      doFirst("useRobolectricCdsArchive") {
        val test = this as Test
        if (!enabled.get() || !sdk.isPresent) return@doFirst
        val jvmArgs = test.allJvmArgs
        if (jvmArgs.any { arg -> CONFLICTING_JVM_ARGS.any { arg.startsWith(it) } }) {
          test.logger.info("Robolectric CDS: not used, the test JVM sets its own CDS options")
          return@doFirst
        }
        val request =
          CdsRequest(
            // The executable, if set, overrides the launcher.
            javaExecutable =
              File(test.executable ?: test.javaLauncher.get().executablePath.asFile.path),
            classpath = test.classpath.files.toList(),
            workingDir = test.workingDir,
            sdk = sdk.get(),
            cacheDir = cacheDir.get().asFile,
            trainingJvmArgs =
              jvmArgs.filterNot { arg -> TRAINING_EXCLUDED_JVM_ARGS.any { arg.startsWith(it) } } +
                extraTrainingJvmArgs.get(),
          )
        val archive = service.get().archiveFor(request, test.logger) ?: return@doFirst
        test.logger.info("Robolectric CDS: using ${archive.archive}")
        // Added when the task runs, so that it is not an input: the archive's path is local.
        test.jvmArgumentProviders.add(CdsJvmArguments(archive.jvmArgs()))
      }
    }
  }

  private class CdsJvmArguments(private val args: List<String>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = args
  }

  private companion object {
    /** Options that a test JVM can't combine with the archive's. */
    val CONFLICTING_JVM_ARGS =
      listOf("-Xshare:off", "-XX:SharedArchiveFile", "-XX:ArchiveClassesAtExit", "-Xbootclasspath")

    /**
     * Options the training JVM must not get: agents, which can wait for a debugger or write
     * coverage data, and its own CDS options.
     */
    val TRAINING_EXCLUDED_JVM_ARGS =
      listOf(
        "-javaagent:",
        "-agentlib:",
        "-agentpath:",
        "-Xrunjdwp",
        "-Xdebug",
        "-XX:+AutoCreateSharedArchive",
        "-XX:DumpLoadedClassList",
        "-Drobolectric.enabledSdks=",
      )
  }
}
