// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.code.CfInvoke;
import com.debughelper.tools.r8.code.InvokeDirectRange;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.objectweb.asm.Opcodes;

public class InvokeDirect extends InvokeMethodWithReceiver {

  private final boolean itf;

  public InvokeDirect(DexMethod target, Value result, List<Value> arguments) {
    this(target, result, arguments, false);
  }

  public InvokeDirect(DexMethod target, Value result, List<Value> arguments, boolean itf) {
    super(target, result, arguments);
    this.itf = itf;
  }

  @Override
  public Invoke.Type getType() {
    return Invoke.Type.DIRECT;
  }

  @Override
  protected String getTypeString() {
    return "Direct";
  }

  @Override
  public DexEncodedMethod computeSingleTarget(AppInfoWithLiveness appInfo) {
    return appInfo.lookupDirectTarget(getInvokedMethod());
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.debughelper.tools.r8.code.Instruction instruction;
    int argumentRegisters = requiredArgumentRegisters();
    builder.requestOutgoingRegisters(argumentRegisters);
    if (needsRangedInvoke(builder)) {
      assert argumentsConsecutive(builder);
      int firstRegister = argumentRegisterValue(0, builder);
      instruction = new InvokeDirectRange(firstRegister, argumentRegisters, getInvokedMethod());
    } else {
      int[] individualArgumentRegisters = new int[5];
      int argumentRegistersCount = fillArgumentRegisters(builder, individualArgumentRegisters);
      instruction = new com.debughelper.tools.r8.code.InvokeDirect(
          argumentRegistersCount,
          getInvokedMethod(),
          individualArgumentRegisters[0],  // C
          individualArgumentRegisters[1],  // D
          individualArgumentRegisters[2],  // E
          individualArgumentRegisters[3],  // F
          individualArgumentRegisters[4]); // G
    }
    addInvokeAndMoveResult(instruction, builder);
  }

  /**
   * Two invokes of a constructor are only allowed to be considered equal if the object
   * they are initializing is the same. Art rejects code that has objects created by
   * different new-instance instructions flow to one constructor invoke.
   */
  public boolean sameConstructorReceiverValue(Invoke other) {
    if (!getInvokedMethod().name.toString().equals(Constants.INSTANCE_INITIALIZER_NAME)) {
      return true;
    }
    return inValues.get(0) == other.inValues.get(0);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isInvokeDirect() && super.identicalNonValueNonPositionParts(other);
  }

  @Override
  public boolean isInvokeDirect() {
    return true;
  }

  @Override
  public InvokeDirect asInvokeDirect() {
    return this;
  }

  @Override
  public DexEncodedMethod lookupSingleTarget(AppInfoWithLiveness appInfo,
      DexType invocationContext) {
    return appInfo.lookupDirectTarget(getInvokedMethod());
  }

  @Override
  public Collection<DexEncodedMethod> lookupTargets(AppInfoWithSubtyping appInfo,
      DexType invocationContext) {
    DexEncodedMethod target = appInfo.lookupDirectTarget(getInvokedMethod());
    return target == null ? Collections.emptyList() : Collections.singletonList(target);
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return inliningConstraintForSinlgeTargetInvoke(info, invocationContext);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfInvoke(Opcodes.INVOKESPECIAL, getInvokedMethod(), itf));
  }
}
