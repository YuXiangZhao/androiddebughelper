// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.TypeVerificationHelper;
import com.debughelper.tools.r8.cf.code.CfCheckCast;
import com.debughelper.tools.r8.code.MoveObject;
import com.debughelper.tools.r8.code.MoveObjectFrom16;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import java.util.function.Function;

public class CheckCast extends com.debughelper.tools.r8.ir.code.Instruction {

  private final DexType type;

  // A CheckCast dex instruction takes only one register containing a value and changes
  // the associated type information for that value. In the IR we let the CheckCast
  // instruction define a new value. During register allocation we then need to arrange it
  // so that the source and destination are assigned the same register.
  public CheckCast(Value dest, Value value, DexType type) {
    super(dest, value);
    if (value.isNeverNull()) {
      dest.markNeverNull();
    }
    this.type = type;
  }

  public DexType getType() {
    return type;
  }

  public Value object() {
    return inValues().get(0);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    // The check cast instruction in dex doesn't write a new register. Therefore,
    // if the register allocator could not put input and output in the same register
    // we have to insert a move before the check cast instruction.
    int inRegister = builder.allocatedRegister(inValues.get(0), getNumber());
    if (outValue == null) {
      builder.add(this, new com.debughelper.tools.r8.code.CheckCast(inRegister, type));
    } else {
      int outRegister = builder.allocatedRegister(outValue, getNumber());
      if (inRegister == outRegister) {
        builder.add(this, new com.debughelper.tools.r8.code.CheckCast(outRegister, type));
      } else {
        com.debughelper.tools.r8.code.CheckCast cast =
            new com.debughelper.tools.r8.code.CheckCast(outRegister, type);
        if (outRegister <= Constants.U4BIT_MAX && inRegister <= Constants.U4BIT_MAX) {
          builder.add(this, new MoveObject(outRegister, inRegister), cast);
        } else {
          builder.add(this, new MoveObjectFrom16(outRegister, inRegister), cast);
        }
      }
    }
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    return other.isCheckCast() && other.asCheckCast().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.slowCompareTo(other.asCheckCast().type);
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean isCheckCast() {
    return true;
  }

  @Override
  public CheckCast asCheckCast() {
    return this;
  }

  @Override
  public String toString() {
    return super.toString() + "; " + type;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.classIsVisible(invocationContext, type, info);
  }

  @Override
  public TypeLatticeElement evaluate(
      AppInfo appInfo, Function<Value, TypeLatticeElement> getLatticeElement) {
    return getLatticeElement.apply(object()).checkCast(appInfo, type);
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.loadInValues(this, it);
    helper.storeOutValue(this, it);
  }

  @Override
  public boolean hasInvariantVerificationType() {
    return true;
  }

  @Override
  public DexType computeVerificationType(TypeVerificationHelper helper) {
    return type;
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfCheckCast(type));
  }
}
