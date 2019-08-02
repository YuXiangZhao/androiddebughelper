// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.naming;

import static com.debughelper.tools.r8.naming.IdentifierNameStringUtils.identifyIdentiferNameString;
import static com.debughelper.tools.r8.naming.IdentifierNameStringUtils.inferMemberOrTypeFromNameString;
import static com.debughelper.tools.r8.naming.IdentifierNameStringUtils.isReflectionMethod;

import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexItemBasedString;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.DexValue.DexValueString;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.ConstString;
import com.debughelper.tools.r8.ir.code.FieldInstruction;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.InstancePut;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.code.InvokeMethod;
import com.debughelper.tools.r8.ir.code.StaticPut;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.shaking.Enqueuer;
import com.google.common.collect.Streams;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class IdentifierNameStringMarker {
  private final AppInfo appInfo;
  private final DexItemFactory dexItemFactory;
  private final Set<DexItem> identifierNameStrings;

  public IdentifierNameStringMarker(Enqueuer.AppInfoWithLiveness appInfo) {
    this.appInfo = appInfo;
    this.dexItemFactory = appInfo.dexItemFactory;
    // Note that this info is only available at AppInfoWithLiveness.
    this.identifierNameStrings = appInfo.identifierNameStrings;
  }

  public void decoupleIdentifierNameStringsInFields() {
    for (DexProgramClass clazz : appInfo.classes()) {
      clazz.forEachField(this::decoupleIdentifierNameStringInField);
    }
  }

  private void decoupleIdentifierNameStringInField(DexEncodedField encodedField) {
    if (!identifierNameStrings.contains(encodedField.field)) {
      return;
    }
    if (!encodedField.accessFlags.isStatic()) {
      return;
    }
    DexValue staticValue = encodedField.getStaticValue();
    if (!(staticValue instanceof DexValueString)) {
      return;
    }
    DexString original = ((DexValueString) staticValue).getValue();
    DexItemBasedString itemBasedString = inferMemberOrTypeFromNameString(appInfo, original);
    if (itemBasedString != null) {
      encodedField.setStaticValue(new DexValueString(itemBasedString));
    }
  }

  public void decoupleIdentifierNameStringsInMethod(DexEncodedMethod encodedMethod, IRCode code) {
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocks = code.listIterator();
    while (blocks.hasNext()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = blocks.next();
      InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        Instruction instruction = iterator.next();
        // v_n <- "x.y.z" // in.definition
        // ...
        // ... <- ... v_n ..
        // ...
        // this.fld <- v_n // fieldPut
        //
        //   ~>
        //
        // ...
        // v_n' <- DexItemBasedString("Lx/y/z;") // decoupled
        // this.fld <- v_n' // fieldPut
        if (instruction.isStaticPut() || instruction.isInstancePut()) {
          FieldInstruction fieldPut = instruction.asFieldInstruction();
          DexField field = fieldPut.getField();
          if (!identifierNameStrings.contains(field)) {
            continue;
          }
          com.debughelper.tools.r8.ir.code.Value in = instruction.isStaticPut()
              ? instruction.asStaticPut().inValue()
              : instruction.asInstancePut().value();
          if (!in.isConstString()) {
            continue;
          }
          DexString original = in.getConstInstruction().asConstString().getValue();
          DexItemBasedString itemBasedString = inferMemberOrTypeFromNameString(appInfo, original);
          if (itemBasedString == null) {
            continue;
          }
          // Move the cursor back to $fieldPut
          assert iterator.peekPrevious() == fieldPut;
          iterator.previous();
          // Prepare $decoupled just before $fieldPut
          com.debughelper.tools.r8.ir.code.Value newIn = code.createValue(in.outType(), in.getLocalInfo());
          com.debughelper.tools.r8.ir.code.ConstString decoupled = new com.debughelper.tools.r8.ir.code.ConstString(newIn, itemBasedString);
          decoupled.setPosition(fieldPut.getPosition());
          // If the current block has catch handler, split into two blocks.
          // Because const-string we're about to add is also a throwing instr, we need to split
          // before adding it.
          com.debughelper.tools.r8.ir.code.BasicBlock blockWithFieldInstruction =
              block.hasCatchHandlers() ? iterator.split(code, blocks) : block;
          if (blockWithFieldInstruction != block) {
            // If we split, add const-string at the end of the currently visiting block.
            iterator = block.listIterator(block.getInstructions().size());
            iterator.previous();
            iterator.add(decoupled);
            // Restore the cursor and block.
            iterator = blockWithFieldInstruction.listIterator();
            assert iterator.peekNext() == fieldPut;
            iterator.next();
            block = blockWithFieldInstruction;
          } else {
            // Otherwise, just add it to the current block at the position of the iterator.
            iterator.add(decoupled);
            // Restore the cursor.
            assert iterator.peekNext() == fieldPut;
            iterator.next();
          }
          if (instruction.isStaticPut()) {
            com.debughelper.tools.r8.ir.code.StaticPut staticPut = instruction.asStaticPut();
            iterator.replaceCurrentInstruction(
                new StaticPut(staticPut.getType(), newIn, field));
          } else {
            assert instruction.isInstancePut();
            com.debughelper.tools.r8.ir.code.InstancePut instancePut = instruction.asInstancePut();
            iterator.replaceCurrentInstruction(
                new InstancePut(instancePut.getType(), field, instancePut.object(), newIn));
          }
          encodedMethod.markUseIdentifierNameString();
        } else if (instruction.isInvokeMethod()) {
          InvokeMethod invoke = instruction.asInvokeMethod();
          DexMethod invokedMethod = invoke.getInvokedMethod();
          if (!identifierNameStrings.contains(invokedMethod)) {
            continue;
          }
          List<com.debughelper.tools.r8.ir.code.Value> ins = invoke.arguments();
          com.debughelper.tools.r8.ir.code.Value[] changes = new com.debughelper.tools.r8.ir.code.Value[ins.size()];
          if (isReflectionMethod(dexItemFactory, invokedMethod)) {
            DexItemBasedString itemBasedString = identifyIdentiferNameString(appInfo, invoke);
            if (itemBasedString == null) {
              continue;
            }
            DexType returnType = invoke.getReturnType();
            boolean isClassForName =
                returnType.descriptor == dexItemFactory.classDescriptor;
            boolean isReferenceFieldUpdater =
                returnType.descriptor == dexItemFactory.referenceFieldUpdaterDescriptor;
            int positionOfIdentifier = isClassForName ? 0 : (isReferenceFieldUpdater ? 2 : 1);
            com.debughelper.tools.r8.ir.code.Value in = invoke.arguments().get(positionOfIdentifier);
            // Move the cursor back to $invoke
            assert iterator.peekPrevious() == invoke;
            iterator.previous();
            // Prepare $decoupled just before $invoke
            com.debughelper.tools.r8.ir.code.Value newIn = code.createValue(in.outType(), in.getLocalInfo());
            com.debughelper.tools.r8.ir.code.ConstString decoupled = new com.debughelper.tools.r8.ir.code.ConstString(newIn, itemBasedString);
            decoupled.setPosition(invoke.getPosition());
            changes[positionOfIdentifier] = newIn;
            // If the current block has catch handler, split into two blocks.
            // Because const-string we're about to add is also a throwing instr, we need to split
            // before adding it.
            com.debughelper.tools.r8.ir.code.BasicBlock blockWithInvoke =
                block.hasCatchHandlers() ? iterator.split(code, blocks) : block;
            if (blockWithInvoke != block) {
              // If we split, add const-string at the end of the currently visiting block.
              iterator = block.listIterator(block.getInstructions().size());
              iterator.previous();
              iterator.add(decoupled);
              // Restore the cursor and block.
              iterator = blockWithInvoke.listIterator();
              assert iterator.peekNext() == invoke;
              iterator.next();
              block = blockWithInvoke;
            } else {
              // Otherwise, just add it to the current block at the position of the iterator.
              iterator.add(decoupled);
              // Restore the cursor.
              assert iterator.peekNext() == invoke;
              iterator.next();
            }
          } else {
            // For general invoke. Multiple arguments can be string literals to be renamed.
            for (int i = 0; i < ins.size(); i++) {
              com.debughelper.tools.r8.ir.code.Value in = ins.get(i);
              if (!in.isConstString()) {
                continue;
              }
              DexString original = in.getConstInstruction().asConstString().getValue();
              DexItemBasedString itemBasedString =
                  inferMemberOrTypeFromNameString(appInfo, original);
              if (itemBasedString == null) {
                continue;
              }
              // Move the cursor back to $invoke
              assert iterator.peekPrevious() == invoke;
              iterator.previous();
              // Prepare $decoupled just before $invoke
              com.debughelper.tools.r8.ir.code.Value newIn = code.createValue(in.outType(), in.getLocalInfo());
              com.debughelper.tools.r8.ir.code.ConstString decoupled = new ConstString(newIn, itemBasedString);
              decoupled.setPosition(invoke.getPosition());
              changes[i] = newIn;
              // If the current block has catch handler, split into two blocks.
              // Because const-string we're about to add is also a throwing instr, we need to split
              // before adding it.
              BasicBlock blockWithInvoke =
                  block.hasCatchHandlers() ? iterator.split(code, blocks) : block;
              if (blockWithInvoke != block) {
                // If we split, add const-string at the end of the currently visiting block.
                iterator = block.listIterator(block.getInstructions().size());
                iterator.previous();
                iterator.add(decoupled);
                // Restore the cursor and block.
                iterator = blockWithInvoke.listIterator();
                assert iterator.peekNext() == invoke;
                iterator.next();
                block = blockWithInvoke;
              } else {
                // Otherwise, just add it to the current block at the position of the iterator.
                iterator.add(decoupled);
                // Restore the cursor.
                assert iterator.peekNext() == invoke;
                iterator.next();
              }
            }
          }
          if (!Arrays.stream(changes).allMatch(Objects::isNull)) {
            List<Value> newIns =
                Streams.mapWithIndex(
                    ins.stream(),
                    (in, index) -> changes[(int) index] != null ? changes[(int) index] : in)
                .collect(Collectors.toList());
            iterator.replaceCurrentInstruction(
                Invoke.create(
                    invoke.getType(),
                    invokedMethod,
                    invokedMethod.proto,
                    invoke.outValue(),
                    newIns));
            encodedMethod.markUseIdentifierNameString();
          }
        }
      }
    }
  }
}
