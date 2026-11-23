package org.robolectric.internal.bytecode;

import java.io.IOException;
import java.io.Writer;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Records the classes a {@link SandboxClassLoader} defines with the unmodified bytes of a jar
 * entry, so that a build tool can put them in a JDK class data sharing (CDS) archive.
 *
 * <p>Enabled by setting the {@code robolectric.cds.recordDir} system property to a directory. Each
 * sandbox class loader writes one file there, {@code classes-<pid>-<n>.txt}, with a line per class
 * in definition order:
 *
 * <pre>{@code
 * name <TAB> super <TAB> interfaces <TAB> source jar
 * }</pre>
 *
 * <p>Names are internal names ({@code android/app/Activity}). The super class and each of the
 * comma-separated interfaces is suffixed with {@code @S} if this sandbox defined it, {@code @J} if
 * the boot or platform class loader did, or {@code @A} for any other loader. The interfaces are
 * {@code -} if there are none.
 *
 * <p>The JDK matches an archived class to a {@code defineClass} call by its name and the CRC of its
 * bytes, so a stale archive misses rather than defining the wrong class. That is why only classes
 * whose bytes come unchanged from a jar are recorded.
 */
final class CdsClassListRecorder {
  static final String RECORD_DIR_PROPERTY = "robolectric.cds.recordDir";

  private static final AtomicInteger LOADER_COUNTER = new AtomicInteger();

  private final Path file;
  private final ClassLoader sandbox;
  private Writer writer;
  private boolean failed;

  private CdsClassListRecorder(Path file, ClassLoader sandbox) {
    this.file = file;
    this.sandbox = sandbox;
  }

  /** Returns a recorder for {@code sandbox}, or null if recording is not enabled. */
  @Nullable
  static CdsClassListRecorder create(ClassLoader sandbox) {
    String dir = System.getProperty(RECORD_DIR_PROPERTY);
    if (dir == null || dir.isEmpty()) {
      return null;
    }
    String name =
        "classes-"
            + ProcessHandle.current().pid()
            + "-"
            + LOADER_COUNTER.getAndIncrement()
            + ".txt";
    return new CdsClassListRecorder(Paths.get(dir, name), sandbox);
  }

  /**
   * Records {@code clazz}, which the sandbox defined with the unmodified bytes of {@code source}.
   */
  synchronized void record(Class<?> clazz, @Nullable URL source) {
    if (failed) {
      return;
    }
    String jar = jarPath(source, clazz.getName().replace('.', '/') + ".class");
    if (jar == null) {
      return;
    }
    StringBuilder line = new StringBuilder(clazz.getName().replace('.', '/')).append('\t');
    Class<?> superclass = clazz.getSuperclass();
    // Interfaces have no super class, but their class files name java.lang.Object.
    line.append(superclass == null ? "java/lang/Object@J" : describe(superclass)).append('\t');
    Class<?>[] interfaces = clazz.getInterfaces();
    if (interfaces.length == 0) {
      line.append('-');
    }
    for (int i = 0; i < interfaces.length; i++) {
      line.append(i == 0 ? "" : ",").append(describe(interfaces[i]));
    }
    line.append('\t').append(jar).append('\n');
    try {
      if (writer == null) {
        Files.createDirectories(file.getParent());
        writer =
            Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      }
      // Flush every line: sandboxes are not closed before the JVM exits.
      writer.write(line.toString());
      writer.flush();
    } catch (IOException e) {
      failed = true;
      System.err.println("Robolectric: cannot record CDS class list to " + file + ": " + e);
    }
  }

  private String describe(Class<?> type) {
    ClassLoader loader = type.getClassLoader();
    String kind;
    if (loader == sandbox) {
      kind = "@S";
    } else if (loader == null || loader == ClassLoader.getPlatformClassLoader()) {
      kind = "@J";
    } else {
      kind = "@A";
    }
    return type.getName().replace('.', '/') + kind;
  }

  /** Returns the path of the jar file that {@code url} is the entry {@code entryName} of. */
  @Nullable
  private static String jarPath(@Nullable URL url, String entryName) {
    if (url == null || !"jar".equals(url.getProtocol())) {
      return null;
    }
    String spec = url.getPath();
    int separator = spec.indexOf("!/");
    // Nested jars and multi-release entries are not where the JDK will look for the class.
    if (separator < 0 || !spec.substring(separator + 2).equals(entryName)) {
      return null;
    }
    try {
      URL jarUrl = new URL(spec.substring(0, separator));
      if (!"file".equals(jarUrl.getProtocol())) {
        return null;
      }
      String path = Paths.get(jarUrl.toURI()).toString();
      return path.indexOf('\t') < 0 && path.indexOf('\n') < 0 ? path : null;
    } catch (IOException | URISyntaxException | IllegalArgumentException e) {
      return null;
    }
  }
}
