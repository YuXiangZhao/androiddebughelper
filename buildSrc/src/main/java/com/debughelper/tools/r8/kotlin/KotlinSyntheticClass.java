// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.kotlin;

import com.debughelper.tools.r8.kotlin.KotlinInfo;

public final class KotlinSyntheticClass extends KotlinInfo {
  public enum Flavour {
    KotlinStyleLambda,
    JavaStyleLambda,
    Unclassified
  }

  private final Flavour flavour;

  KotlinSyntheticClass(Flavour flavour) {
    this.flavour = flavour;
  }

  public boolean isLambda() {
    return flavour == Flavour.KotlinStyleLambda || flavour == Flavour.JavaStyleLambda;
  }

  public boolean isKotlinStyleLambda() {
    return flavour == Flavour.KotlinStyleLambda;
  }

  public boolean isJavaStyleLambda() {
    return flavour == Flavour.JavaStyleLambda;
  }

  @Override
  public final Kind getKind() {
    return Kind.Synthetic;
  }

  @Override
  public final boolean isSyntheticClass() {
    return true;
  }

  @Override
  public KotlinSyntheticClass asSyntheticClass() {
    return this;
  }
}
