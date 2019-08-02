// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.graph.UseRegistry;

public class IgetWide extends Format22c {

  public static final int OPCODE = 0x53;
  public static final String NAME = "IgetWide";
  public static final String SMALI_NAME = "iget-wide";

  /*package*/ IgetWide(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getFieldMap());
  }

  public IgetWide(int destRegister, int objectRegister, com.debughelper.tools.r8.graph.DexField field) {
    super(destRegister, objectRegister, field);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getSmaliName() {
    return SMALI_NAME;
  }

  @Override
  public int getOpcode() {
    return OPCODE;
  }

  @Override
  public com.debughelper.tools.r8.graph.DexField getField() {
    return (DexField) CCCC;
  }

  @Override
  public void registerUse(UseRegistry registry) {
    registry.registerInstanceFieldRead(getField());
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addInstanceGet(A, B, getField());
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
