// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.UseRegistry;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.cf.CfPrinter;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfInstanceOf extends CfInstruction {

  private final DexType type;

  public CfInstanceOf(DexType type) {
    this.type = type;
  }

  public DexType getType() {
    return type;
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitTypeInsn(Opcodes.INSTANCEOF, lens.lookupInternalName(type));
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  @Override
  public void registerUse(UseRegistry registry, DexType clazz) {
    registry.registerTypeReference(type);
  }

  @Override
  public boolean canThrow() {
    return true;
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    int value = state.pop().register;
    builder.addInstanceOf(state.push(builder.getFactory().booleanType).register, value, type);
  }
}
