// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format21s;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.code.WideConstant;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.utils.StringUtils;

public class ConstWide16 extends com.debughelper.tools.r8.code.Format21s implements WideConstant {

  public static final int OPCODE = 0x16;
  public static final String NAME = "ConstWide16";
  public static final String SMALI_NAME = "const-wide/16";

  ConstWide16(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public ConstWide16(int dest, int constant) {
    super(dest, constant);
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
  public long decodedValue() {
    return BBBB;
  }

  @Override
  public String toString(com.debughelper.tools.r8.naming.ClassNameMapper naming) {
    return formatString("v" + AA + ", " + com.debughelper.tools.r8.utils.StringUtils.hexString(decodedValue(), 16) +
        " (" + decodedValue() + ")");
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString(
        "v" + AA + ", " + StringUtils.hexString(decodedValue(), 16) + "L  # " + decodedValue());
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addConst(ValueType.LONG_OR_DOUBLE, AA, decodedValue());
  }
}
