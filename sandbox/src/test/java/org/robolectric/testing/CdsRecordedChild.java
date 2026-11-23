package org.robolectric.testing;

import org.robolectric.internal.bytecode.ShadowedObject;

/** A class that looks preinstrumented, so the sandbox defines it as it is. */
public class CdsRecordedChild extends CdsRecordedParent implements ShadowedObject {
  @Override
  public Object $$robo$getData() {
    return null;
  }
}
