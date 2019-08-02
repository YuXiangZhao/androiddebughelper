// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.code.CfArrayLength;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.analysis.type.PrimitiveTypeLatticeElement;
import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.ir.regalloc.RegisterAllocator;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import java.util.function.Function;

public class ArrayLength extends com.debughelper.tools.r8.ir.code.Instruction {

  public ArrayLength(Value dest, Value array) {
    super(dest, array);
  }

  public Value dest() {
    return outValue;
  }

  public Value array() {
    return inValues.get(0);
  }

  @Override
  public boolean isArrayLength() {
    return true;
  }

  @Override
  public ArrayLength asArrayLength() {
    return this;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int dest = builder.allocatedRegister(dest(), getNumber());
    int array = builder.allocatedRegister(array(), getNumber());
    builder.add(this, new com.debughelper.tools.r8.code.ArrayLength(dest, array));
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U4BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U4BIT_MAX;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean identicalAfterRegisterAllocation(com.debughelper.tools.r8.ir.code.Instruction other, RegisterAllocator allocator) {
    if (super.identicalAfterRegisterAllocation(other, allocator)) {
      // The array length instruction doesn't carry the element type. The art verifier doesn't
      // allow an array length instruction into which arrays of two different base types can
      // flow. Therefore, as a safe approximation we only consider array length instructions
      // equal when they have the same inflowing SSA value.
      // TODO(ager): We could perform conservative type propagation earlier in the pipeline and
      // add a member type to array length instructions.
      return array() == other.asArrayLength().array();
    }
    return false;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    return other.isArrayLength();
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other.isArrayLength();
    return 0;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.ALWAYS;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.loadInValues(this, it);
    helper.storeOutValue(this, it);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfArrayLength());
  }

  @Override
  public TypeLatticeElement evaluate(
      AppInfo appInfo, Function<Value, TypeLatticeElement> getLatticeElement) {
    return PrimitiveTypeLatticeElement.getInstance();
  }

  @Override
  public boolean throwsNpeIfValueIsNull(Value value) {
    return array() == value;
  }
}
