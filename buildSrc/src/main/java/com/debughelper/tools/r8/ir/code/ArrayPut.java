// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.code.CfArrayStore;
import com.debughelper.tools.r8.code.Aput;
import com.debughelper.tools.r8.code.AputBoolean;
import com.debughelper.tools.r8.code.AputByte;
import com.debughelper.tools.r8.code.AputChar;
import com.debughelper.tools.r8.code.AputObject;
import com.debughelper.tools.r8.code.AputShort;
import com.debughelper.tools.r8.code.AputWide;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.MemberType;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.ir.regalloc.RegisterAllocator;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.utils.InternalOptions;
import java.util.Arrays;

public class ArrayPut extends com.debughelper.tools.r8.ir.code.Instruction {

  // Input values are ordered according to the stack order of the Java bytecode astore.
  private static final int ARRAY_INDEX = 0;
  private static final int INDEX_INDEX = 1;
  private static final int VALUE_INDEX = 2;

  private final com.debughelper.tools.r8.ir.code.MemberType type;

  public ArrayPut(MemberType type, Value array, Value index, Value value) {
    super(null, Arrays.asList(array, index, value));
    assert type != null;
    assert array.type.isObject();
    assert index.type.isSingle();
    this.type = type;
  }

  public Value array() {
    return inValues.get(ARRAY_INDEX);
  }

  public Value index() {
    return inValues.get(INDEX_INDEX);
  }

  public Value value() {
    return inValues.get(VALUE_INDEX);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int value = builder.allocatedRegister(value(), getNumber());
    int array = builder.allocatedRegister(array(), getNumber());
    int index = builder.allocatedRegister(index(), getNumber());
    com.debughelper.tools.r8.code.Instruction instruction;
    switch (type) {
      case INT:
      case FLOAT:
      case INT_OR_FLOAT:
        instruction = new Aput(value, array, index);
        break;
      case LONG:
      case DOUBLE:
      case LONG_OR_DOUBLE:
        instruction = new AputWide(value, array, index);
        break;
      case OBJECT:
        instruction = new AputObject(value, array, index);
        break;
      case BOOLEAN:
        instruction = new AputBoolean(value, array, index);
        break;
      case BYTE:
        instruction = new AputByte(value, array, index);
        break;
      case CHAR:
        instruction = new AputChar(value, array, index);
        break;
      case SHORT:
        instruction = new AputShort(value, array, index);
        break;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
    builder.add(this, instruction);
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    assert false : "ArrayPut instructions define no values.";
    return 0;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean instructionInstanceCanThrow() {
    if (index().isConstant() && !array().isPhi() && array().definition.isNewArrayEmpty()) {
      Value newArraySizeValue = array().definition.asNewArrayEmpty().size();
      if (newArraySizeValue.isConstant()) {
        int newArraySize = newArraySizeValue.getConstInstruction().asConstNumber().getIntValue();
        int index = index().getConstInstruction().asConstNumber().getIntValue();
        return newArraySize <= 0 || index < 0 || newArraySize <= index;
      }
    }
    return true;
  }

  @Override
  public boolean canBeDeadCode(IRCode code, InternalOptions options) {
    // ArrayPut has side-effects on input values.
    return false;
  }

  @Override
  public boolean identicalAfterRegisterAllocation(com.debughelper.tools.r8.ir.code.Instruction other, RegisterAllocator allocator) {
    // We cannot share ArrayPut instructions without knowledge of the type of the array input.
    // If multiple primitive array types flow to the same ArrayPut instruction the art verifier
    // gets confused.
    return false;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    return other.isArrayPut() && other.asArrayPut().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asArrayPut().type.ordinal();
  }

  @Override
  public boolean isArrayPut() {
    return true;
  }

  @Override
  public ArrayPut asArrayPut() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.ALWAYS;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.loadInValues(this, it);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfArrayStore(type));
  }

  @Override
  public boolean throwsNpeIfValueIsNull(Value value) {
    return array() == value;
  }
}
