// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.code.CfNop;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.utils.InternalOptions;

public class DebugPosition extends com.debughelper.tools.r8.ir.code.Instruction {

  public DebugPosition() {
    super(null);
  }

  @Override
  public boolean isDebugPosition() {
    return true;
  }

  @Override
  public DebugPosition asDebugPosition() {
    return this;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    assert getPosition().isSome() && !getPosition().synthetic;
    builder.addDebugPosition(this);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    return other.isDebugPosition();
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other.isDebugPosition();
    return 0;
  }

  @Override
  public int maxInValueRegister() {
    throw new Unreachable();
  }

  @Override
  public int maxOutValueRegister() {
    throw new Unreachable();
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.ALWAYS;
  }

  @Override
  public boolean canBeDeadCode(IRCode code, InternalOptions options) {
    return false;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    // Nothing to do for positions which are not actual instructions.
  }

  @Override
  public void buildCf(CfBuilder builder) {
    assert getPosition().isSome() && !getPosition().synthetic;
    // All redundant debug positions are removed. Remaining ones must force a pc advance.
    builder.add(new CfNop());
  }
}
