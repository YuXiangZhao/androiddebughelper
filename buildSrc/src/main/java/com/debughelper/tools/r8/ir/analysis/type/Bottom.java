// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.analysis.type;

public class Bottom extends TypeLatticeElement {
  private static final Bottom INSTANCE = new Bottom();

  private Bottom() {
    super(true);
  }

  @Override
  TypeLatticeElement asNullable() {
    return this;
  }

  static Bottom getInstance() {
    return INSTANCE;
  }

  @Override
  boolean isBottom() {
    return true;
  }

  @Override
  public String toString() {
    return "BOTTOM (empty)";
  }
}
