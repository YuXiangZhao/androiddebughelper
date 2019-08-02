// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.TypeVerificationHelper;
import com.debughelper.tools.r8.code.FilledNewArray;
import com.debughelper.tools.r8.code.FilledNewArrayRange;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import java.util.List;
import java.util.function.Function;

public class InvokeNewArray extends Invoke {

  private final DexType type;

  public InvokeNewArray(DexType type, Value result, List<Value> arguments) {
    super(result, arguments);
    this.type = type;
  }

  @Override
  public DexType getReturnType() {
    return getArrayType();
  }

  public DexType getArrayType() {
    return type;
  }

  @Override
  public Type getType() {
    return Type.NEW_ARRAY;
  }

  @Override
  protected String getTypeString() {
    return "NewArray";
  }

  @Override
  public String toString() {
    return super.toString() + "; type: " + type.toSourceString();
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.debughelper.tools.r8.code.Instruction instruction;
    int argumentRegisters = requiredArgumentRegisters();
    builder.requestOutgoingRegisters(argumentRegisters);
    if (needsRangedInvoke(builder)) {
      assert argumentsConsecutive(builder);
      int firstRegister = argumentRegisterValue(0, builder);
      instruction = new FilledNewArrayRange(firstRegister, argumentRegisters, type);
    } else {
      int[] individualArgumentRegisters = new int[5];
      int argumentRegistersCount = fillArgumentRegisters(builder, individualArgumentRegisters);
      instruction = new FilledNewArray(
          argumentRegistersCount,
          type,
          individualArgumentRegisters[0],  // C
          individualArgumentRegisters[1],  // D
          individualArgumentRegisters[2],  // E
          individualArgumentRegisters[3],  // F
          individualArgumentRegisters[4]); // G
    }
    addInvokeAndMoveResult(instruction, builder);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    return other.isInvokeNewArray() && type == other.asInvokeNewArray().type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    if (!other.isInvokeNewArray()) {
      return -1;
    }
    return type.slowCompareTo(other.asInvokeNewArray().type);
  }

  @Override
  public boolean isInvokeNewArray() {
    return true;
  }

  @Override
  public InvokeNewArray asInvokeNewArray() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.classIsVisible(invocationContext, type, info);
  }

  @Override
  public TypeLatticeElement evaluate(
      AppInfo appInfo, Function<Value, TypeLatticeElement> getLatticeElement) {
    return TypeLatticeElement.newArray(type, false);
  }

  @Override
  public boolean hasInvariantVerificationType() {
    throw cfUnsupported();
  }

  @Override
  public DexType computeVerificationType(TypeVerificationHelper helper) {
    throw cfUnsupported();
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    throw cfUnsupported();
  }

  @Override
  public void buildCf(CfBuilder builder) {
    throw cfUnsupported();
  }

  private static Unreachable cfUnsupported() {
    throw new Unreachable("InvokeNewArray (non-empty) not supported when compiling to classfiles.");
  }
}
