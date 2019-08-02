// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.cf.code.CfLabel;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.CfState.Slot;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.cf.CfPrinter;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

public class CfSwitch extends CfInstruction {

  public enum Kind { LOOKUP, TABLE }

  private final Kind kind;
  private final com.debughelper.tools.r8.cf.code.CfLabel defaultTarget;
  private final int[] keys;
  private final List<com.debughelper.tools.r8.cf.code.CfLabel> targets;

  public CfSwitch(Kind kind, com.debughelper.tools.r8.cf.code.CfLabel defaultTarget, int[] keys, List<com.debughelper.tools.r8.cf.code.CfLabel> targets) {
    this.kind = kind;
    this.defaultTarget = defaultTarget;
    this.keys = keys;
    this.targets = targets;
    assert kind != Kind.LOOKUP || keys.length == targets.size();
    assert kind != Kind.TABLE || keys.length == 1;
  }

  public Kind getKind() {
    return kind;
  }

  public com.debughelper.tools.r8.cf.code.CfLabel getDefaultTarget() {
    return defaultTarget;
  }

  public IntList getKeys() {
    return new IntArrayList(keys);
  }

  public List<CfLabel> getSwitchTargets() {
    return targets;
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    Label[] labels = new Label[targets.size()];
    for (int i = 0; i < targets.size(); i++) {
      labels[i] = targets.get(i).getLabel();
    }
    switch (kind) {
      case LOOKUP:
        visitor.visitLookupSwitchInsn(defaultTarget.getLabel(), keys, labels);
        break;
      case TABLE: {
        int min = keys[0];
        int max = min + labels.length - 1;
        visitor.visitTableSwitchInsn(min, max, defaultTarget.getLabel(), labels);
      }
    }
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    int[] labelOffsets = new int[targets.size()];
    for (int i = 0; i < targets.size(); i++) {
      labelOffsets[i] = code.getLabelOffset(targets.get(i));
    }
    Slot value = state.pop();
    builder.addSwitch(value.register, keys, code.getLabelOffset(defaultTarget), labelOffsets);
  }
}
