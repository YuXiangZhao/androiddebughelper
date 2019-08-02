// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.cf.code.CfInstruction;
import com.debughelper.tools.r8.errors.Unimplemented;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.UseRegistry;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import org.objectweb.asm.MethodVisitor;

public class CfMultiANewArray extends CfInstruction {

  private final DexType type;
  private final int dimensions;

  public CfMultiANewArray(DexType type, int dimensions) {
    this.type = type;
    this.dimensions = dimensions;
  }

  public DexType getType() {
    return type;
  }

  public int getDimensions() {
    return dimensions;
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitMultiANewArrayInsn(lens.lookupInternalName(type), dimensions);
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
    if (!builder.isGeneratingClassFiles()) {
      // TODO(b/109789539): Implement this case (see JarSourceCode.buildPrelude()/buildPostlude()).
      throw new Unimplemented("CfMultiANewArray to DEX backend");
    }
    int[] dimensions = state.popReverse(this.dimensions);
    builder.addMultiNewArray(type, state.push(type).register, dimensions);
  }
}
