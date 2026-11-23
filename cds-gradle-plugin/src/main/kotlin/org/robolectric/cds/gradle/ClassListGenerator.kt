package org.robolectric.cds.gradle

/**
 * Builds the class list that `java -Xshare:dump -XX:SharedClassListFile` reads.
 *
 * The list has three parts:
 * 1. The JDK classes from `-XX:DumpLoadedClassList`, by name.
 * 2. The classes Robolectric's sandbox recorded, as unregistered classes with their super types and
 *    source jar. A super type that the sandbox did not define has to be a built-in class at dump
 *    time: a JDK class or one of [bootClasses], which the dump appends to the boot class path.
 * 3. The `@lambda-form-invoker` lines from `-XX:DumpLoadedClassList`, which pregenerate LambdaForm
 *    holder classes.
 */
internal object ClassListGenerator {
  class Result(
    val lines: List<String>,
    val jdkClasses: Int,
    val sandboxClasses: Int,
    val skipped: Int,
  )

  private class Recorded(
    val superType: String,
    val interfaces: List<String>,
    val source: String,
  )

  /**
   * @param records the lines of one file written by Robolectric's `robolectric.cds.recordDir`.
   * @param loadedClassList the file written by `-XX:DumpLoadedClassList` in the same JVM.
   * @param bootClasses the internal names of the classes the archive puts on the boot class path.
   */
  fun generate(
    records: List<String>,
    loadedClassList: List<String>,
    bootClasses: Set<String>,
  ): Result {
    val recorded = LinkedHashMap<String, Recorded>()
    for (line in records) {
      val fields = line.split('\t')
      if (fields.size != 4) continue
      val interfaces = if (fields[2] == "-") emptyList() else fields[2].split(',')
      recorded.putIfAbsent(fields[0], Recorded(fields[1], interfaces, fields[3]))
    }

    val ids = HashMap<String, Int>()
    val unarchivable = HashSet<String>()
    val sandboxLines = ArrayList<String>()

    fun emitBuiltin(name: String): Int =
      ids.getOrPut(name) {
        sandboxLines.add("$name id: ${ids.size}")
        ids.size
      }

    // Returns the id of a sandbox class, or -1 if it cannot be archived.
    fun emitSandboxClass(name: String): Int {
      ids[name]?.let {
        return it
      }
      if (name in unarchivable) return -1
      val record = recorded[name]
      // A super type is resolved before its subtype is defined, so it is recorded first. There is
      // no record if the sandbox instrumented it or read it from a directory.
      if (record == null) {
        unarchivable.add(name)
        return -1
      }
      fun emitSuperType(type: String): Int {
        val typeName = type.substringBeforeLast('@')
        return when (type.substringAfterLast('@')) {
          "S" -> emitSandboxClass(typeName)
          "J" -> emitBuiltin(typeName)
          "A" -> if (typeName in bootClasses) emitBuiltin(typeName) else -1
          else -> -1
        }
      }
      val superId = emitSuperType(record.superType)
      val interfaceIds = record.interfaces.map { emitSuperType(it) }
      if (superId < 0 || interfaceIds.any { it < 0 }) {
        unarchivable.add(name)
        return -1
      }
      val id = ids.size
      ids[name] = id
      val interfacesPart =
        if (interfaceIds.isEmpty()) "" else " interfaces: " + interfaceIds.joinToString(" ")
      sandboxLines.add("$name id: $id super: $superId$interfacesPart source: ${record.source}")
      return id
    }

    var skipped = 0
    for (name in recorded.keys) {
      if (emitSandboxClass(name) < 0) skipped++
    }

    val jdkLines = ArrayList<String>()
    val lambdaFormLines = ArrayList<String>()
    for (line in loadedClassList) {
      when {
        line.startsWith("@lambda-form-invoker") ->
          if (isUsableLambdaFormInvoker(line)) lambdaFormLines.add(line)
        line.isBlank() || line.startsWith("#") || line.startsWith("@") -> {}
        else -> {
          val name = line.substringBefore(' ')
          if (name !in ids && isJdkClass(name)) jdkLines.add(name)
        }
      }
    }

    return Result(
      lines = jdkLines + sandboxLines + lambdaFormLines,
      jdkClasses = jdkLines.size,
      sandboxClasses = sandboxLines.count { it.contains(" source: ") },
      skipped = skipped,
    )
  }

  private val jdkPackages = listOf("java/", "javax/", "jdk/", "sun/", "com/sun/")

  // -XX:DumpLoadedClassList also lists the class path classes, which the dump cannot find because
  // its class path is empty, and classes the JDK generates at run time.
  private fun isJdkClass(name: String): Boolean =
    jdkPackages.any { name.startsWith(it) } &&
      !name.startsWith("jdk/proxy") &&
      !name.startsWith("com/sun/proxy/") &&
      !name.contains("\$\$") &&
      !name.contains("\$Species_")

  /**
   * JDK 21 fails to regenerate the `Invokers$Holder` class, and then all of the LambdaForm holder
   * classes, with "Invoker type parameter must start and end with Object" if one of the invoker
   * types does not. So keep only the ones whose parameters start and end with `L`, as in `L3_V`.
   */
  internal fun isUsableLambdaFormInvoker(line: String): Boolean {
    if (!line.contains("Invokers\$Holder")) return true
    val basicType = line.substringAfterLast(' ')
    val parameters = basicType.substringBefore('_').filter { !it.isDigit() }
    return parameters.startsWith("L") && parameters.endsWith("L")
  }
}
