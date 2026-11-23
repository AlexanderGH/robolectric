package org.robolectric.cds.gradle

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ClassListGeneratorTest {
  private val shadowedObject = "org/robolectric/internal/bytecode/ShadowedObject"

  @Test
  fun `sandbox classes are unregistered classes after their built-in super types`() {
    val result =
      ClassListGenerator.generate(
        records =
          listOf(
            "android/content/Context\tjava/lang/Object@J\t$shadowedObject@A\t/sdk.jar",
            "android/content/ContextWrapper\tandroid/content/Context@S\t$shadowedObject@A\t/sdk.jar",
            "android/view/Window\tjava/lang/Object@J\t-\t/sdk.jar",
          ),
        loadedClassList = emptyList(),
        bootClasses = setOf(shadowedObject),
      )

    assertThat(result.lines)
      .containsExactly(
        "java/lang/Object id: 0",
        "$shadowedObject id: 1",
        "android/content/Context id: 2 super: 0 interfaces: 1 source: /sdk.jar",
        "android/content/ContextWrapper id: 3 super: 2 interfaces: 1 source: /sdk.jar",
        "android/view/Window id: 4 super: 0 source: /sdk.jar",
      )
      .inOrder()
    assertThat(result.sandboxClasses).isEqualTo(3)
    assertThat(result.skipped).isEqualTo(0)
  }

  @Test
  fun `classes with super types that the dump can't load are skipped with their subclasses`() {
    val result =
      ClassListGenerator.generate(
        records =
          listOf(
            // An interface on the class path, which the dump does not have.
            "org/robolectric/Shadows\tjava/lang/Object@J\torg/robolectric/internal/ShadowProvider@A\t/a.jar",
            // A super class that the sandbox instrumented or read from a directory.
            "com/example/Child\tcom/example/Parent@S\t-\t/a.jar",
            "com/example/GrandChild\tcom/example/Child@S\t-\t/a.jar",
            "com/example/Fine\tjava/lang/Object@J\t-\t/a.jar",
          ),
        loadedClassList = emptyList(),
        bootClasses = setOf(shadowedObject),
      )

    assertThat(result.lines)
      .containsExactly("java/lang/Object id: 0", "com/example/Fine id: 1 super: 0 source: /a.jar")
      .inOrder()
    assertThat(result.skipped).isEqualTo(3)
  }

  @Test
  fun `JDK classes come first and once, without class path classes`() {
    val result =
      ClassListGenerator.generate(
        records = listOf("android/view/View\tjava/lang/Object@J\t-\t/sdk.jar"),
        loadedClassList =
          listOf(
            "# NOTE: Do not modify this file.",
            "java/lang/Object id: 0",
            "java/lang/String id: 1",
            "javax/inject/Inject id: 2",
            "jdk/proxy2/\$Proxy12 id: 3",
            "java/lang/invoke/BoundMethodHandle\$Species_LLLL id: 4",
            "com/google/common/collect/ImmutableList id: 5",
            "@lambda-proxy java/lang/Thread run ()Ljava/lang/Runnable;",
          ),
        bootClasses = emptySet(),
      )

    assertThat(result.lines)
      .containsExactly(
        "java/lang/String",
        "javax/inject/Inject",
        "java/lang/Object id: 0",
        "android/view/View id: 1 super: 0 source: /sdk.jar",
      )
      .inOrder()
  }

  @Test
  fun `lambda form invokers JDK 21 can't regenerate are dropped`() {
    val invoker = "@lambda-form-invoker [LF_RESOLVE] java.lang.invoke.Invokers\$Holder"
    val direct = "@lambda-form-invoker [LF_RESOLVE] java.lang.invoke.DirectMethodHandle\$Holder"
    val result =
      ClassListGenerator.generate(
        records = emptyList(),
        loadedClassList =
          listOf(
            "$invoker invokeExact_MT L3_L",
            "$invoker linkToTargetMethod LL_V",
            "$invoker invokeExact_MT L10I_V",
            "$invoker invoker LIL_I",
            "$invoker invoker I_L",
            "$direct invokeStatic LI_V",
          ),
        bootClasses = emptySet(),
      )

    assertThat(result.lines)
      .containsExactly(
        "$invoker invokeExact_MT L3_L",
        "$invoker linkToTargetMethod LL_V",
        "$invoker invoker LIL_I",
        "$direct invokeStatic LI_V",
      )
      .inOrder()
  }
}
