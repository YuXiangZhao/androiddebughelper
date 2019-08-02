// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.cf.code.CfInstruction;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DexCallSite;
import com.debughelper.tools.r8.graph.DexMethodHandle;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.DexValue.DexValueDouble;
import com.debughelper.tools.r8.graph.DexValue.DexValueFloat;
import com.debughelper.tools.r8.graph.DexValue.DexValueInt;
import com.debughelper.tools.r8.graph.DexValue.DexValueLong;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodType;
import com.debughelper.tools.r8.graph.DexValue.DexValueString;
import com.debughelper.tools.r8.graph.DexValue.DexValueType;
import com.debughelper.tools.r8.graph.UseRegistry;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.ir.code.ValueType;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

public class CfInvokeDynamic extends CfInstruction {

  private final DexCallSite callSite;

  public CfInvokeDynamic(DexCallSite callSite) {
    this.callSite = callSite;
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    DexMethodHandle bootstrapMethod = callSite.bootstrapMethod;
    List<DexValue> bootstrapArgs = callSite.bootstrapArgs;
    Object[] bsmArgs = new Object[bootstrapArgs.size()];
    for (int i = 0; i < bootstrapArgs.size(); i++) {
      bsmArgs[i] = decodeBootstrapArgument(bootstrapArgs.get(i), lens);
    }
    Handle bsmHandle = bootstrapMethod.toAsmHandle(lens);
    DexString methodName = lens.lookupMethodName(callSite);
    visitor.visitInvokeDynamicInsn(
        methodName.toString(), callSite.methodProto.toDescriptorString(lens), bsmHandle, bsmArgs);
  }

  private Object decodeBootstrapArgument(DexValue dexValue, NamingLens lens) {
    if (dexValue instanceof DexValueInt) {
      return ((DexValueInt) dexValue).getValue();
    } else if (dexValue instanceof DexValueLong) {
      return ((DexValueLong) dexValue).getValue();
    } else if (dexValue instanceof DexValueFloat) {
      return ((DexValueFloat) dexValue).getValue();
    } else if (dexValue instanceof DexValueDouble) {
      return ((DexValueDouble) dexValue).getValue();
    } else if (dexValue instanceof DexValueString) {
      return ((DexValueString) dexValue).getValue();
    } else if (dexValue instanceof DexValueType) {
      return Type.getType(lens.lookupDescriptor(((DexValueType) dexValue).value).toString());
    } else if (dexValue instanceof DexValueMethodType) {
      return Type.getMethodType(((DexValueMethodType) dexValue).value.toDescriptorString(lens));
    } else if (dexValue instanceof DexValueMethodHandle) {
      return ((DexValueMethodHandle) dexValue).value.toAsmHandle(lens);
    } else {
      throw new Unreachable(
          "Unsupported bootstrap argument of type " + dexValue.getClass().getSimpleName());
    }
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  public DexCallSite getCallSite() {
    return callSite;
  }

  @Override
  public void registerUse(UseRegistry registry, DexType clazz) {
    registry.registerCallSite(callSite);
  }

  @Override
  public boolean canThrow() {
    return true;
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    DexType[] parameterTypes = callSite.methodProto.parameters.values;
    List<Integer> registers = new ArrayList<>(parameterTypes.length);
    for (int register : state.popReverse(parameterTypes.length)) {
      registers.add(register);
    }
    List<com.debughelper.tools.r8.ir.code.ValueType> types = new ArrayList<>(parameterTypes.length);
    for (DexType value : parameterTypes) {
      types.add(ValueType.fromDexType(value));
    }
    builder.addInvokeCustom(callSite, types, registers);
    if (!callSite.methodProto.returnType.isVoidType()) {
      builder.addMoveResult(state.push(callSite.methodProto.returnType).register);
    }
  }
}
