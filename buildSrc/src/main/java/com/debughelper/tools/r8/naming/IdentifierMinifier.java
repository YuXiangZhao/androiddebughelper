// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.naming;

import static com.debughelper.tools.r8.utils.DescriptorUtils.descriptorToJavaType;

import com.debughelper.tools.r8.code.ConstString;
import com.debughelper.tools.r8.code.ConstStringJumbo;
import com.debughelper.tools.r8.code.Instruction;
import com.debughelper.tools.r8.graph.Code;
import com.debughelper.tools.r8.graph.DexCode;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexItemBasedString;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.DexValue.DexValueString;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.shaking.Enqueuer;
import com.debughelper.tools.r8.shaking.ProguardClassFilter;

import java.util.Map;
import java.util.Set;

class IdentifierMinifier {

  private final Enqueuer.AppInfoWithLiveness appInfo;
  private final com.debughelper.tools.r8.shaking.ProguardClassFilter adaptClassStrings;
  private final NamingLens lens;
  private final Set<DexItem> identifierNameStrings;

  IdentifierMinifier(
      Enqueuer.AppInfoWithLiveness appInfo,
      ProguardClassFilter adaptClassStrings,
      NamingLens lens) {
    this.appInfo = appInfo;
    this.adaptClassStrings = adaptClassStrings;
    this.lens = lens;
    this.identifierNameStrings = appInfo.identifierNameStrings;
  }

  void run() {
    if (!adaptClassStrings.isEmpty()) {
      adaptClassStrings();
    }
    if (!identifierNameStrings.isEmpty()) {
      replaceIdentifierNameString();
    }
  }

  private void adaptClassStrings() {
    for (DexProgramClass clazz : appInfo.classes()) {
      if (!adaptClassStrings.matches(clazz.type)) {
        continue;
      }
      clazz.forEachField(this::adaptClassStringsInField);
      clazz.forEachMethod(this::adaptClassStringsInMethod);
    }
  }

  private void adaptClassStringsInField(DexEncodedField encodedField) {
    if (!encodedField.accessFlags.isStatic()) {
      return;
    }
    DexValue staticValue = encodedField.getStaticValue();
    if (!(staticValue instanceof DexValueString)) {
      return;
    }
    DexString original = ((DexValueString) staticValue).getValue();
    DexString renamed = getRenamedStringLiteral(original);
    if (renamed != original) {
      encodedField.setStaticValue(new DexValueString(renamed));
    }
  }

  private void adaptClassStringsInMethod(DexEncodedMethod encodedMethod) {
    // Abstract methods do not have code_item.
    if (encodedMethod.accessFlags.isAbstract()) {
      return;
    }
    Code code = encodedMethod.getCode();
    if (code == null) {
      return;
    }
    assert code.isDexCode();
    DexCode dexCode = code.asDexCode();
    for (Instruction instr : dexCode.instructions) {
      if (instr instanceof ConstString) {
        ConstString cnst = (ConstString) instr;
        DexString dexString = cnst.getString();
        cnst.BBBB = getRenamedStringLiteral(dexString);
      } else if (instr instanceof ConstStringJumbo) {
        ConstStringJumbo cnst = (ConstStringJumbo) instr;
        DexString dexString = cnst.getString();
        cnst.BBBBBBBB = getRenamedStringLiteral(dexString);
      }
    }
  }

  private DexString getRenamedStringLiteral(DexString originalLiteral) {
    String originalString = originalLiteral.toString();
    Map<String, DexType> renamedYetMatchedTypes =
        lens.getRenamedItems(
            DexType.class,
            type -> type.toSourceString().equals(originalString),
            DexType::toSourceString);
    DexType type = renamedYetMatchedTypes.get(originalString);
    if (type != null) {
      DexString renamed = lens.lookupDescriptor(type);
      // Create a new DexString only when the corresponding string literal will be replaced.
      if (renamed != originalLiteral) {
        return appInfo.dexItemFactory.createString(descriptorToJavaType(renamed.toString()));
      }
    }
    return originalLiteral;
  }

  private void replaceIdentifierNameString() {
    for (DexProgramClass clazz : appInfo.classes()) {
      // Some const strings could be moved to field's static value (from <clinit>).
      clazz.forEachField(this::replaceIdentifierNameStringInField);
      clazz.forEachMethod(this::replaceIdentifierNameStringInMethod);
    }
  }

  private void replaceIdentifierNameStringInField(DexEncodedField encodedField) {
    if (!encodedField.accessFlags.isStatic()) {
      return;
    }
    DexValue staticValue = encodedField.getStaticValue();
    if (!(staticValue instanceof DexValueString)) {
      return;
    }
    DexString original = ((DexValueString) staticValue).getValue();
    if (original instanceof DexItemBasedString) {
      encodedField.setStaticValue(new DexValueString(materialize((DexItemBasedString) original)));
    }
  }

  private void replaceIdentifierNameStringInMethod(DexEncodedMethod encodedMethod) {
    if (!encodedMethod.getOptimizationInfo().useIdentifierNameString()) {
      return;
    }
    // Abstract methods do not have code_item.
    if (encodedMethod.accessFlags.isAbstract()) {
      return;
    }
    Code code = encodedMethod.getCode();
    if (code == null) {
      return;
    }
    assert code.isDexCode();
    DexCode dexCode = code.asDexCode();
    for (Instruction instr : dexCode.instructions) {
      if (instr instanceof ConstString
          && ((ConstString) instr).getString() instanceof DexItemBasedString) {
        ConstString cnst = (ConstString) instr;
        DexItemBasedString itemBasedString = (DexItemBasedString) cnst.getString();
        cnst.BBBB = materialize(itemBasedString);
      } else if (instr instanceof ConstStringJumbo
          && ((ConstStringJumbo) instr).getString() instanceof DexItemBasedString) {
        ConstStringJumbo cnst = (ConstStringJumbo) instr;
        DexItemBasedString itemBasedString = (DexItemBasedString) cnst.getString();
        cnst.BBBBBBBB = materialize(itemBasedString);
      }
    }
  }

  private DexString materialize(DexItemBasedString itemBasedString) {
    if (itemBasedString.basedOn instanceof DexType) {
      DexString renamed = lens.lookupDescriptor((DexType) itemBasedString.basedOn);
      if (!renamed.toString().equals(itemBasedString.toString())) {
        return appInfo.dexItemFactory.createString(descriptorToJavaType(renamed.toString()));
      }
      return renamed;
    } else if (itemBasedString.basedOn instanceof DexMethod) {
      return lens.lookupName((DexMethod) itemBasedString.basedOn);
    } else {
      assert itemBasedString.basedOn instanceof DexField;
      return lens.lookupName((DexField) itemBasedString.basedOn);
    }
  }

}
