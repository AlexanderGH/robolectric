package org.robolectric.internal.bytecode;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.robolectric.testing.AChild;
import org.robolectric.testing.AnUninstrumentedClass;
import org.robolectric.testing.CdsRecordedChild;
import org.robolectric.testing.CdsRecordedParent;

@RunWith(JUnit4.class)
public class CdsClassListRecorderTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private String originalProperty;
  private Path recordDir;
  private File jar;

  @Before
  public void setUp() throws Exception {
    originalProperty = System.getProperty(CdsClassListRecorder.RECORD_DIR_PROPERTY);
    recordDir = temporaryFolder.newFolder("record").toPath();
    jar = temporaryFolder.newFile("classes.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
      for (Class<?> clazz :
          new Class<?>[] {CdsRecordedParent.class, CdsRecordedChild.class, AChild.class}) {
        String name = clazz.getName().replace('.', '/') + ".class";
        out.putNextEntry(new ZipEntry(name));
        try (InputStream in = clazz.getClassLoader().getResourceAsStream(name)) {
          in.transferTo(out);
        }
        out.closeEntry();
      }
    }
  }

  @After
  public void restoreProperty() {
    if (originalProperty == null) {
      System.clearProperty(CdsClassListRecorder.RECORD_DIR_PROPERTY);
    } else {
      System.setProperty(CdsClassListRecorder.RECORD_DIR_PROPERTY, originalProperty);
    }
  }

  @Test
  public void recordsClassesDefinedUnchangedFromJars() throws Exception {
    System.setProperty(CdsClassListRecorder.RECORD_DIR_PROPERTY, recordDir.toString());
    try (SandboxClassLoader loader = sandbox()) {
      loader.loadClass(CdsRecordedChild.class.getName());
      // Instrumented, so its bytes differ from the jar's.
      loader.loadClass(AChild.class.getName());
      // Read from the test classes directory, like the parents of AChild.
      loader.loadClass(AnUninstrumentedClass.class.getName());
    }

    List<Path> files;
    try (var list = Files.list(recordDir)) {
      files = list.collect(Collectors.toList());
    }
    assertThat(files).hasSize(1);
    assertThat(Files.readAllLines(files.get(0)))
        .containsExactly(
            "org/robolectric/testing/CdsRecordedParent\tjava/lang/Object@J\tjava/lang/Runnable@J\t"
                + jar.getAbsolutePath(),
            "org/robolectric/testing/CdsRecordedChild\torg/robolectric/testing/CdsRecordedParent@S\t"
                + "org/robolectric/internal/bytecode/ShadowedObject@A\t"
                + jar.getAbsolutePath())
        .inOrder();
  }

  @Test
  public void recordsNothingByDefault() throws Exception {
    System.clearProperty(CdsClassListRecorder.RECORD_DIR_PROPERTY);
    try (SandboxClassLoader loader = sandbox()) {
      loader.loadClass(CdsRecordedChild.class.getName());
    }
    try (var list = Files.list(recordDir)) {
      assertThat(list.count()).isEqualTo(0);
    }
  }

  private SandboxClassLoader sandbox() throws Exception {
    return new SandboxClassLoader(
        getClass().getClassLoader(),
        InstrumentationConfiguration.newBuilder().build(),
        new UrlResourceProvider(jar.toURI().toURL()),
        new ClassInstrumentor(new ShadowDecorator()));
  }
}
