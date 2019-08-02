// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.graph.UseRegistry;

public class IputObject extends Format22c {

  public static final int OPCODE = 0x5b;
  public static final String NAME = "IputObject";
  public static final String SMALI_NAME = "iput-object";

  /*package*/ IputObject(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getFieldMap());
  }

  public IputObject(int valueRegister, int objectRegister, com.debughelper.tools.r8.graph.DexField field) {
    super(valueRegister, objectRegister, field);
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
  public void registerUse(UseRegistry registry) {
    registry.registerInstanceFieldWrite(getField());
  }

  @Override
  public com.debughelper.tools.r8.graph.DexField getField() {
    return (DexField) CCCC;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addInstancePut(A, B, getField());
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
