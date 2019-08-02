// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;

public abstract class ConstInstruction extends Instruction {

  public ConstInstruction(Value out) {
    super(out);
  }

  @Override
  public ConstInstruction getOutConstantConstInstruction() {
    return this;
  }

  @Override
  public boolean isConstInstruction() {
    return true;
  }

  @Override
  public ConstInstruction asConstInstruction() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.ALWAYS;
  }
}
