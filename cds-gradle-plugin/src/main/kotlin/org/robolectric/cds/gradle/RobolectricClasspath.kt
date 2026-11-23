package org.robolectric.cds.gradle

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** What the CDS archive needs to know about the Robolectric classes on a test class path. */
internal class RobolectricClasspath
private constructor(
  /**
   * A hash of the class path entries in [FINGERPRINTED_CLASSES], which changes with Robolectric.
   */
  val fingerprint: String,
  /** The bytes of each of [BOOT_CLASSES] that the class path has, by internal name. */
  val bootClasses: Map<String, ByteArray>,
  /** Whether this Robolectric records CDS class lists and has the training test. */
  val supportsCds: Boolean,
) {
  companion object {
    const val TRAINING_CLASS = "org.robolectric.android.internal.CdsTraining"

    /**
     * The interfaces that Robolectric adds to instrumented classes. They are put on the boot class
     * path of the dump and test JVMs, because archived classes can only implement built-in classes,
     * which must be on the dump's class path, and Gradle's test class path starts with class
     * directories, which CDS does not allow on it. Both are empty interfaces that only refer to JDK
     * classes, and Robolectric's sandbox loads them from its parent, so moving them to the boot
     * class loader does not change which class the sandbox sees.
     */
    val BOOT_CLASSES =
      listOf(
        "org/robolectric/internal/bytecode/ShadowedObject",
        "org/robolectric/internal/bytecode/InstrumentedInterface",
      )

    /**
     * Classes whose class path entries are hashed into the archive key: the sandbox, the runner
     * (which picks the SDK jars) and the shadows. Robolectric's other modules change with them in a
     * release. Not hashing every entry keeps the key stable when tests or app code change.
     */
    private val FINGERPRINTED_CLASSES =
      listOf(
        "org/robolectric/internal/bytecode/SandboxClassLoader.class",
        "org/robolectric/RobolectricTestRunner.class",
        "org/robolectric/Shadows.class",
      )

    private val CDS_SUPPORT_CLASSES =
      listOf(
        TRAINING_CLASS.replace('.', '/') + ".class",
        "org/robolectric/internal/bytecode/CdsClassListRecorder.class",
      )

    /** Returns null if [classpath] has no Robolectric sandbox. */
    fun scan(classpath: List<File>): RobolectricClasspath? {
      val digest = MessageDigest.getInstance("SHA-256")
      val bootClasses = LinkedHashMap<String, ByteArray>()
      val supportClasses = HashSet<String>()
      for (entry in classpath) {
        when {
          entry.isFile && entry.name.endsWith(".jar") ->
            scanJar(entry, digest, bootClasses, supportClasses)
          entry.isDirectory -> scanDirectory(entry, digest, bootClasses, supportClasses)
        }
      }
      if (bootClasses.size != BOOT_CLASSES.size) return null
      return RobolectricClasspath(
        fingerprint = digest.digest().toHex(),
        bootClasses = bootClasses,
        supportsCds = supportClasses.containsAll(CDS_SUPPORT_CLASSES),
      )
    }

    private fun scanJar(
      jar: File,
      digest: MessageDigest,
      bootClasses: MutableMap<String, ByteArray>,
      supportClasses: MutableSet<String>,
    ) {
      ZipFile(jar).use { zip ->
        if (FINGERPRINTED_CLASSES.any { zip.getEntry(it) != null }) {
          digest.update(jar.name.toByteArray())
          digest.update(jar)
        }
        for (name in BOOT_CLASSES) {
          val entry = zip.getEntry("$name.class") ?: continue
          bootClasses.putIfAbsent(name, zip.getInputStream(entry).use { it.readBytes() })
        }
        CDS_SUPPORT_CLASSES.filterTo(supportClasses) { zip.getEntry(it) != null }
      }
    }

    private fun scanDirectory(
      dir: File,
      digest: MessageDigest,
      bootClasses: MutableMap<String, ByteArray>,
      supportClasses: MutableSet<String>,
    ) {
      if (FINGERPRINTED_CLASSES.any { File(dir, it).isFile }) {
        File(dir, "org/robolectric")
          .walkTopDown()
          .filter { it.isFile }
          .sortedBy { it.path }
          .forEach {
            digest.update(it.relativeTo(dir).path.toByteArray())
            digest.update(it)
          }
      }
      for (name in BOOT_CLASSES) {
        val file = File(dir, "$name.class")
        if (file.isFile) bootClasses.putIfAbsent(name, file.readBytes())
      }
      CDS_SUPPORT_CLASSES.filterTo(supportClasses) { File(dir, it).isFile }
    }
  }
}

private fun MessageDigest.update(file: File) {
  file.inputStream().use { input ->
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      update(buffer, 0, read)
    }
  }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
