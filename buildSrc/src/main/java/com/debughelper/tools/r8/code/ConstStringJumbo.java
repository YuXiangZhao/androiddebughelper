// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format31c;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.naming.ClassNameMapper;

public class ConstStringJumbo extends com.debughelper.tools.r8.code.Format31c {

  public static final int OPCODE = 0x1b;
  public static final String NAME = "ConstStringJumbo";
  public static final String SMALI_NAME = "const-string/jumbo";

  ConstStringJumbo(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getStringMap());
  }

  public ConstStringJumbo(int register, com.debughelper.tools.r8.graph.DexString string) {
    super(register, string);
  }

  public DexString getString() {
    return BBBBBBBB;
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
  public String toString(com.debughelper.tools.r8.naming.ClassNameMapper naming) {
    return formatString("v" + AA + ", \"" + BBBBBBBB.toString() + "\"");
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString("v" + AA + ", \"" + BBBBBBBB.toString() + "\"");
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addConstString(AA, BBBBBBBB);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
