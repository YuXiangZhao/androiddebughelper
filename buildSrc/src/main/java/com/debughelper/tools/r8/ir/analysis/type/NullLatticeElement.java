// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.analysis.type;

public class NullLatticeElement extends TypeLatticeElement {
  private static final NullLatticeElement INSTANCE = new NullLatticeElement();

  private NullLatticeElement() {
    super(true);
  }

  @Override
  public boolean mustBeNull() {
    return true;
  }

  @Override
  TypeLatticeElement asNullable() {
    return this;
  }

  public static NullLatticeElement getInstance() {
    return INSTANCE;
  }

  @Override
  public String toString() {
    return "NULL";
  }
}
