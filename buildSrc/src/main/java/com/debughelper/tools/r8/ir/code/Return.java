// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.code.CfReturn;
import com.debughelper.tools.r8.cf.code.CfReturnVoid;
import com.debughelper.tools.r8.code.MoveType;
import com.debughelper.tools.r8.code.ReturnObject;
import com.debughelper.tools.r8.code.ReturnVoid;
import com.debughelper.tools.r8.code.ReturnWide;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;

public class Return extends JumpInstruction {

  // Need to keep track of the original return type, as a null value will have MoveType.SINGLE.
  final private ValueType returnType;

  public Return() {
    super(null);
    returnType = null;
  }

  public Return(Value value, ValueType returnType) {
    super(null, value);
    assert returnType != ValueType.INT_OR_FLOAT_OR_NULL;
    this.returnType = returnType;
  }

  public boolean isReturnVoid() {
    return inValues.size() == 0;
  }

  public ValueType getReturnType() {
    return returnType;
  }

  public Value returnValue() {
    assert !isReturnVoid();
    return inValues.get(0);
  }

  public com.debughelper.tools.r8.code.Instruction createDexInstruction(DexBuilder builder) {
    if (isReturnVoid()) {
      return new ReturnVoid();
    } else {
      int register = builder.allocatedRegister(returnValue(), getNumber());
      switch (MoveType.fromValueType(returnType)) {
        case OBJECT:
          assert returnValue().outType().isObject();
          return new ReturnObject(register);
        case SINGLE:
          assert returnValue().outType().isSingle();
          return new com.debughelper.tools.r8.code.Return(register);
        case WIDE:
          assert returnValue().outType().isWide();
          return new ReturnWide(register);
        default:
          throw new Unreachable();
      }
    }
  }

  @Override
  public void buildDex(DexBuilder builder) {
    builder.addReturn(this, createDexInstruction(builder));
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    if (!other.isReturn()) {
      return false;
    }
    Return o = other.asReturn();
    if (isReturnVoid()) {
      return o.isReturnVoid();
    }
    return o.returnValue().type == returnValue().type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    if (isReturnVoid()) {
      return other.asReturn().isReturnVoid() ? 0 : -1;
    } else {
      return returnValue().type.ordinal() - other.asReturn().returnValue().type.ordinal();
    }
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    assert false : "Return defines no values.";
    return 0;
  }

  @Override
  public boolean isReturn() {
    return true;
  }

  @Override
  public Return asReturn() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.ALWAYS;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    if (!isReturnVoid()) {
      helper.loadInValues(this, it);
    }
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(isReturnVoid() ? new CfReturnVoid() : new CfReturn(returnType));
  }
}
