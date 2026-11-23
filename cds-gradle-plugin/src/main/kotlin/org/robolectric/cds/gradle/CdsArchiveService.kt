package org.robolectric.cds.gradle

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.api.logging.Logger
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.process.ExecOperations

/** A test JVM's view of a CDS archive: the archive and the jar it needs on the boot class path. */
internal data class CdsArchive(val archive: File, val bootClassPath: File) {
  fun jvmArgs(): List<String> =
    listOf("-XX:SharedArchiveFile=${archive.path}", "-Xbootclasspath/a:${bootClassPath.path}")
}

/** What a test task needs a CDS archive for. */
internal class CdsRequest(
  val javaExecutable: File,
  val classpath: List<File>,
  val workingDir: File,
  val sdk: Int,
  val cacheDir: File,
  /** The test JVM's arguments, minus the ones that the training run must not get. */
  val trainingJvmArgs: List<String>,
)

/**
 * Creates and shares the CDS archives in a cache directory, keyed by the JDK, Robolectric and the
 * SDK. An archive is created once by any build on the machine: creation holds a file lock, works in
 * a temporary directory, and moves it into place, so test JVMs never see a partial archive.
 */
abstract class CdsArchiveService : BuildService<BuildServiceParameters.None> {
  @get:Inject internal abstract val execOperations: ExecOperations

  /** Returns the archive for [request], creating it if needed, or null if there can't be one. */
  internal fun archiveFor(request: CdsRequest, logger: Logger): CdsArchive? {
    val robolectric = RobolectricClasspath.scan(request.classpath) ?: return null
    if (!robolectric.supportsCds) {
      logger.info("Robolectric CDS: this Robolectric version does not support CDS archives")
      return null
    }
    val key = archiveKey(request, robolectric)
    val cacheDir = request.cacheDir
    val archiveDir = File(cacheDir, key)
    val failedMarker = File(cacheDir, "$key.failed")
    // Cheap checks before taking the lock, for the common case.
    existingArchive(archiveDir)?.let {
      return it
    }
    if (failedMarker.exists()) {
      logger.info("Robolectric CDS: not using an archive, see $failedMarker")
      return null
    }
    cacheDir.mkdirs()
    return withLock(File(cacheDir, "$key.lock")) {
      existingArchive(archiveDir)?.let {
        return@withLock it
      }
      if (failedMarker.exists()) return@withLock null
      val start = System.nanoTime()
      logger.lifecycle(
        "Robolectric CDS: creating an archive for SDK ${request.sdk} in $archiveDir",
      )
      val workDir = Files.createTempDirectory(cacheDir.toPath(), "$key.tmp").toFile()
      try {
        val failure = createArchive(request, robolectric, workDir)
        if (failure != null) {
          workDir.copyLogsTo(failedMarker, failure)
          logger.warn(
            "Robolectric CDS: could not create an archive, so tests run without one: $failure. " +
              "See $failedMarker, and delete it to try again.",
          )
          return@withLock null
        }
        Files.move(workDir.toPath(), archiveDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        logger.lifecycle(
          "Robolectric CDS: created the archive in ${(System.nanoTime() - start) / 1_000_000} ms",
        )
        existingArchive(archiveDir)
      } finally {
        workDir.deleteRecursively()
      }
    }
  }

  private fun existingArchive(archiveDir: File): CdsArchive? {
    val properties = File(archiveDir, ARCHIVE_PROPERTIES)
    if (!properties.isFile) return null
    val bootClassPath = File(properties.readText().substringAfter("bootClassPath=").trim())
    val archive = File(archiveDir, ARCHIVE)
    return if (archive.isFile && bootClassPath.isFile) CdsArchive(archive, bootClassPath) else null
  }

  /** Creates the archive in [workDir], and returns null, or why it failed. */
  private fun createArchive(
    request: CdsRequest,
    robolectric: RobolectricClasspath,
    workDir: File,
  ): String? {
    val java = request.javaExecutable.path
    val bootClassPath = writeBootClassPathJar(request.cacheDir, robolectric.bootClasses)

    // 1. Training: run a Robolectric test that records the classes the sandbox defines.
    val recordDir = File(workDir, "record")
    val loadedClassList = File(workDir, "loaded.classlist")
    val training =
      FileOutputStream(File(workDir, TRAINING_LOG)).use { log ->
        execOperations.javaexec {
          executable = java
          classpath(request.classpath)
          mainClass.set(RobolectricClasspath.TRAINING_CLASS)
          workingDir = request.workingDir
          jvmArgs(request.trainingJvmArgs)
          jvmArgs(
            "-Drobolectric.cds.recordDir=${recordDir.path}",
            "-Drobolectric.enabledSdks=${request.sdk}",
            "-XX:DumpLoadedClassList=${loadedClassList.path}",
          )
          standardOutput = log
          errorOutput = log
          isIgnoreExitValue = true
        }
      }
    if (training.exitValue != 0) return "the training run failed"
    val records =
      recordDir.listFiles()?.maxByOrNull { it.length() }
        ?: return "the training run recorded no classes"

    // 2. The class list.
    val classList =
      ClassListGenerator.generate(
        records.readLines(),
        loadedClassList.readLines(),
        robolectric.bootClasses.keys,
      )
    val classListFile = File(workDir, "archive.classlist")
    classListFile.writeText(classList.lines.joinToString("\n", postfix = "\n"))

    // 3. The dump. Its class path is empty, so that the test JVM can have any class path.
    val emptyDir = File(workDir, "empty").apply { mkdir() }
    val archive = File(workDir, ARCHIVE)
    val dump =
      exec(
        java,
        listOf(
          "-Xshare:dump",
          "-XX:SharedClassListFile=${classListFile.path}",
          "-XX:SharedArchiveFile=${archive.path}",
          "-Xbootclasspath/a:${bootClassPath.path}",
          "-cp",
          "",
        ),
        emptyDir,
        File(workDir, DUMP_LOG),
      )
    if (dump != 0 || !archive.isFile) return "the dump failed"

    // 4. Check that a JVM maps the archive. One that can't would run without any CDS archive.
    val check =
      exec(
        java,
        listOf(
          "-Xshare:on",
          "-XX:SharedArchiveFile=${archive.path}",
          "-Xbootclasspath/a:${bootClassPath.path}",
          "-version",
        ),
        emptyDir,
        File(workDir, CHECK_LOG),
      )
    if (check != 0) return "the JVM does not map the archive"

    emptyDir.delete()
    loadedClassList.delete()
    recordDir.deleteRecursively()
    File(workDir, "classes.txt")
      .writeText(
        "jdk=${classList.jdkClasses} sandbox=${classList.sandboxClasses} " +
          "skipped=${classList.skipped}\n",
      )
    File(workDir, ARCHIVE_PROPERTIES).writeText("bootClassPath=${bootClassPath.path}\n")
    return null
  }

  private fun exec(java: String, args: List<String>, dir: File, logFile: File): Int =
    FileOutputStream(logFile).use { log ->
      execOperations
        .exec {
          executable = java
          args(args)
          workingDir = dir
          standardOutput = log
          errorOutput = log
          isIgnoreExitValue = true
        }
        .exitValue
    }

  /**
   * Writes the jar that the dump and the test JVMs append to the boot class path. CDS checks its
   * path, size and modification time, so it is shared by content and never rewritten.
   */
  private fun writeBootClassPathJar(cacheDir: File, classes: Map<String, ByteArray>): File {
    val digest = MessageDigest.getInstance("SHA-256")
    classes.forEach { (name, bytes) ->
      digest.update(name.toByteArray())
      digest.update(bytes)
    }
    val jar = File(cacheDir, "boot/${digest.digest().toHex().take(32)}.jar")
    if (jar.isFile) return jar
    jar.parentFile.mkdirs()
    val temp = File.createTempFile("boot", ".tmp", jar.parentFile)
    try {
      ZipOutputStream(FileOutputStream(temp)).use { zip ->
        classes.forEach { (name, bytes) ->
          zip.putNextEntry(ZipEntry("$name.class"))
          zip.write(bytes)
          zip.closeEntry()
        }
      }
      try {
        Files.move(temp.toPath(), jar.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (e: FileAlreadyExistsException) {
        // Another build wrote the same jar.
      } catch (e: AtomicMoveNotSupportedException) {
        if (!jar.exists()) throw e
      }
    } finally {
      temp.delete()
    }
    return jar
  }

  private fun File.copyLogsTo(marker: File, failure: String) {
    val logs =
      listOf(TRAINING_LOG, DUMP_LOG, CHECK_LOG)
        .map { File(this, it) }
        .filter { it.isFile }
        .joinToString("\n") { "==> ${it.name} <==\n" + it.readText().takeLast(20_000) }
    marker.writeText("$failure\n\n$logs")
  }

  private companion object {
    const val FORMAT_VERSION = "1"
    const val ARCHIVE = "archive.jsa"
    const val ARCHIVE_PROPERTIES = "archive.properties"
    const val TRAINING_LOG = "training.log"
    const val DUMP_LOG = "dump.log"
    const val CHECK_LOG = "check.log"

    /** Serializes archive creation within this JVM, where file locks don't. */
    val jvmLocks = ConcurrentHashMap<String, Any>()

    fun archiveKey(request: CdsRequest, robolectric: RobolectricClasspath): String {
      val javaHome = request.javaExecutable.canonicalFile.parentFile.parentFile
      // The release file has the JDK's version, vendor and architecture.
      val release = File(javaHome, "release").takeIf { it.isFile }?.readText() ?: ""
      val digest = MessageDigest.getInstance("SHA-256")
      listOf(FORMAT_VERSION, javaHome.path, release, robolectric.fingerprint, "${request.sdk}")
        .forEach {
          digest.update(it.toByteArray())
          digest.update(0)
        }
      return "sdk${request.sdk}-" + digest.digest().toHex().take(24)
    }

    fun <T> withLock(lockFile: File, action: () -> T): T =
      synchronized(jvmLocks.computeIfAbsent(lockFile.canonicalPath) { Any() }) {
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
          .use { channel ->
            val lock = lockAcquiringOverlaps(channel)
            try {
              action()
            } finally {
              lock.release()
            }
          }
      }

    // Another class loader in this JVM, such as another build's copy of this plugin, can hold the
    // lock, which FileChannel reports instead of blocking.
    private fun lockAcquiringOverlaps(channel: FileChannel): FileLock {
      while (true) {
        try {
          return channel.lock()
        } catch (e: OverlappingFileLockException) {
          Thread.sleep(100)
        }
      }
    }
  }
}
