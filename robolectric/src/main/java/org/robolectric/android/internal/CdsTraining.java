package org.robolectric.android.internal;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * A test that loads the classes most Robolectric tests need, for build tools that create a JDK
 * class data sharing (CDS) archive of them.
 *
 * <p>Run it with {@code robolectric.cds.recordDir} set to record the classes the sandbox defines,
 * and with {@code robolectric.enabledSdks} set to the one SDK to train for. It uses a plain {@code
 * Application}, so that the code under test, such as the app's {@code Application}, does not run.
 * Unlike {@code org.robolectric.internal}, this package is loaded by the sandbox, which a test
 * class has to be.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Config.ALL_SDKS, manifest = Config.NONE, application = Application.class)
public final class CdsTraining {

  /** Runs the training, and exits with a non-zero status if it fails or runs no test. */
  public static void main(String[] args) {
    Result result = JUnitCore.runClasses(CdsTraining.class);
    for (Failure failure : result.getFailures()) {
      System.err.println(failure.getTrace());
    }
    if (result.getRunCount() == 0) {
      System.err.println(
          "No test ran for robolectric.enabledSdks="
              + System.getProperty("robolectric.enabledSdks"));
    }
    System.exit(result.getRunCount() > 0 && result.wasSuccessful() ? 0 : 1);
  }

  @Test
  public void activity() {
    Activity activity =
        Robolectric.buildActivity(Activity.class, new Intent().putExtra("key", 1))
            .setup(new Bundle())
            .get();
    LinearLayout layout = new LinearLayout(activity);
    TextView text = new TextView(activity);
    text.setText(activity.getString(android.R.string.ok));
    layout.addView(text);
    Button button = new Button(activity);
    button.setOnClickListener(v -> v.setVisibility(View.GONE));
    button.performClick();
    layout.addView(button);
    FrameLayout frame = new FrameLayout(activity);
    LayoutInflater.from(activity)
        .inflate(android.R.layout.simple_list_item_1, frame, /* attachToRoot= */ true);
    layout.addView(frame);
    activity.setContentView(layout);
    new Handler(Looper.getMainLooper()).post(() -> text.setText("done"));
    ShadowLooper.idleMainLooper();
  }

  @Test
  public void application() {
    Context context = RuntimeEnvironment.getApplication();
    SharedPreferences preferences = context.getSharedPreferences("cds", Context.MODE_PRIVATE);
    preferences.edit().putString("key", "value").commit();
    context.getResources().getDisplayMetrics();
    context.getPackageManager().getInstalledPackages(0);
  }
}
