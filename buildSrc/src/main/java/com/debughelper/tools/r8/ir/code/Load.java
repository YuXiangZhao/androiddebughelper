// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.TypeVerificationHelper;
import com.debughelper.tools.r8.cf.code.CfLoad;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;

public class Load extends Instruction {

  public Load(StackValue dest, Value src) {
    super(dest, src);
  }

  @Override
  public boolean isLoad() {
    return true;
  }

  @Override
  public Load asLoad() {
    return this;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isLoad();
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return 0;
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U16BIT_MAX;
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
  public void buildDex(DexBuilder builder) {
    throw new Unreachable("This classfile-specific IR should not be inserted in the Dex backend.");
  }

  @Override
  public void buildCf(CfBuilder builder) {
    Value value = inValues.get(0);
    builder.add(new CfLoad(value.outType(), builder.getLocalRegister(value)));
  }

  @Override
  public DexType computeVerificationType(TypeVerificationHelper helper) {
    return helper.getType(inValues.get(0));
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    // Nothing to do. This is only hit because loads and stores are insert for phis.
  }
}
