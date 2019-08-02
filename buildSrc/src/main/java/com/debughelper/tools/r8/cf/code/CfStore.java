// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.CfState.Slot;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.ir.code.ValueType;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfStore extends CfInstruction {

  private final int var;
  private final com.debughelper.tools.r8.ir.code.ValueType type;

  public CfStore(com.debughelper.tools.r8.ir.code.ValueType type, int var) {
    this.var = var;
    this.type = type;
  }

  private int getStoreType() {
    switch (type) {
      case OBJECT:
        return Opcodes.ASTORE;
      case INT:
        return Opcodes.ISTORE;
      case FLOAT:
        return Opcodes.FSTORE;
      case LONG:
        return Opcodes.LSTORE;
      case DOUBLE:
        return Opcodes.DSTORE;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitVarInsn(getStoreType(), var);
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  public ValueType getType() {
    return type;
  }

  public int getLocalIndex() {
    return var;
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    Slot pop = state.pop();
    builder.addMove(type, state.write(var, pop).register, pop.register);
  }

  @Override
  public boolean emitsIR() {
    return false;
  }
}
