// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.TypeVerificationHelper;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.BasicBlock.EdgeType;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.utils.CfgPrinter;
import com.debughelper.tools.r8.utils.ListUtils;
import com.debughelper.tools.r8.utils.StringUtils;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class Phi extends Value {

  private final com.debughelper.tools.r8.ir.code.BasicBlock block;
  private final List<Value> operands = new ArrayList<>();
  private Set<Value> debugValues = null;

  // Trivial phis are eliminated during IR construction. When a trivial phi is eliminated
  // we need to update all references to it. A phi can be referenced from phis, instructions
  // and current definition mappings. This list contains the current definitions mappings that
  // contain this phi.
  private List<Map<Integer, Value>> definitionUsers = new ArrayList<>();

  public Phi(int number, com.debughelper.tools.r8.ir.code.BasicBlock block, ValueType type, DebugLocalInfo local) {
    super(number, type, local);
    this.block = block;
    block.addPhi(this);
  }

  @Override
  public boolean isPhi() {
    return true;
  }

  @Override
  public Phi asPhi() {
    return this;
  }

  public com.debughelper.tools.r8.ir.code.BasicBlock getBlock() {
    return block;
  }

  public void addOperands(IRBuilder builder, int register) {
    // Phi operands are only filled in once to complete the phi. Some phis are incomplete for a
    // period of time to break cycles. When the cycle has been resolved they are completed
    // exactly once by adding the operands.
    assert operands.isEmpty();
    boolean canBeNull = false;
    if (block.getPredecessors().size() == 0) {
      throwUndefinedValueError();
    }
    for (BasicBlock pred : block.getPredecessors()) {
      EdgeType edgeType = pred.getEdgeType(block);
      // Since this read has been delayed we must provide the local info for the value.
      Value operand = builder.readRegister(register, type, pred, edgeType, getLocalInfo());
      operand.constrainType(type);
      canBeNull |= operand.canBeNull();
      appendOperand(operand);
    }
    if (!canBeNull) {
      markNeverNull();
    }
    removeTrivialPhi();
  }

  public void addOperands(List<Value> operands) {
    addOperands(operands, true);
  }

  public void addOperands(List<Value> operands, boolean removeTrivialPhi) {
    // Phi operands are only filled in once to complete the phi. Some phis are incomplete for a
    // period of time to break cycles. When the cycle has been resolved they are completed
    // exactly once by adding the operands.
    assert this.operands.isEmpty();
    boolean canBeNull = false;
    if (operands.size() == 0) {
      throwUndefinedValueError();
    }
    for (Value operand : operands) {
      canBeNull |= operand.canBeNull();
      appendOperand(operand);
    }
    if (!canBeNull) {
      markNeverNull();
    }
    if (removeTrivialPhi) {
      removeTrivialPhi();
    }
  }

  public void addDebugValue(Value value) {
    assert value.hasLocalInfo();
    if (debugValues == null) {
      debugValues = new HashSet<>();
    }
    debugValues.add(value);
    value.addDebugPhiUser(this);
  }

  private void throwUndefinedValueError() {
    throw new CompilationError(
        "Undefined value encountered during compilation. "
            + "This is typically caused by invalid dex input that uses a register "
            + "that is not define on all control-flow paths leading to the use.");
  }

  private void appendOperand(Value operand) {
    operands.add(operand);
    operand.addPhiUser(this);
  }

  public Value getOperand(int predIndex) {
    return operands.get(predIndex);
  }

  public List<Value> getOperands() {
    return operands;
  }

  public void removeOperand(int index) {
    operands.get(index).removePhiUser(this);
    operands.remove(index);
  }

  public void removeOperandsByIndex(List<Integer> operandsToRemove) {
    if (operandsToRemove.isEmpty()) {
      return;
    }
    List<Value> copy = new ArrayList<>(operands);
    operands.clear();
    int current = 0;
    for (int i : operandsToRemove) {
      operands.addAll(copy.subList(current, i));
      copy.get(i).removePhiUser(this);
      current = i + 1;
    }
    operands.addAll(copy.subList(current, copy.size()));
  }

  public void replaceOperandAt(int predIndex, Value newValue) {
    Value current = operands.get(predIndex);
    operands.set(predIndex, newValue);
    newValue.addPhiUser(this);
    current.removePhiUser(this);
  }

  void replaceOperand(Value current, Value newValue) {
    for (int i = 0; i < operands.size(); i++) {
      if (operands.get(i) == current) {
        operands.set(i, newValue);
        newValue.addPhiUser(this);
      }
    }
  }

  void replaceDebugValue(Value current, Value newValue) {
    assert current.hasLocalInfo();
    assert current.getLocalInfo() == newValue.getLocalInfo();
    if (debugValues.remove(current)) {
      addDebugValue(newValue);
    }
  }

  public boolean isTrivialPhi() {
    Value same = null;
    for (Value op : operands) {
      if (op == same || op == this) {
        // Have only seen one value other than this.
        continue;
      }
      if (same != null) {
        // Merged at least two values and is therefore not trivial.
        return false;
      }
      same = op;
    }
    return true;
  }

  public void removeTrivialPhi() {
    Value same = null;
    for (Value op : operands) {
      if (op == same || op == this) {
        // Have only seen one value other than this.
        continue;
      }
      if (same != null) {
        // Merged at least two values and is therefore not trivial.
        assert !isTrivialPhi();
        return;
      }
      same = op;
    }
    assert isTrivialPhi();
    if (same == null) {
      // When doing if-simplification we remove blocks and we can end up with cyclic phis
      // of the form v1 = phi(v1, v1) in dead blocks. If we encounter that case we just
      // leave the phi in there and check at the end that there are no trivial phis.
      return;
    }
    // Removing this phi, so get rid of it as a phi user from all of the operands to avoid
    // recursively getting back here with the same phi. If the phi has itself as an operand
    // that also removes the self-reference.
    for (Value op : operands) {
      op.removePhiUser(this);
    }
    // If IR construction is taking place, update the definition users.
    if (definitionUsers != null) {
      for (Map<Integer, Value> user : definitionUsers) {
        for (Entry<Integer, Value> entry : user.entrySet()) {
          if (entry.getValue() == this) {
            entry.setValue(same);
            if (same.isPhi()) {
              same.asPhi().addDefinitionsUser(user);
            }
          }
        }
      }
    }
    {
      Set<Phi> phiUsersToSimplify = uniquePhiUsers();
      // Replace this phi with the unique value in all users.
      replaceUsers(same);
      // Try to simplify phi users that might now have become trivial.
      for (Phi user : phiUsersToSimplify) {
        user.removeTrivialPhi();
      }
    }
    // Get rid of the phi itself.
    block.removePhi(this);
  }

  public String printPhi() {
    StringBuilder builder = new StringBuilder();
    builder.append("v");
    builder.append(number);
    if (hasLocalInfo()) {
      builder.append("(").append(getLocalInfo()).append(")");
    }
    builder.append(" <- phi");
    StringUtils.append(builder, ListUtils.map(operands, Value::toString));
    builder.append(" : ").append(type);
    return builder.toString();
  }

  public void print(CfgPrinter printer) {
    int uses = numberOfPhiUsers() + numberOfUsers();
    printer
        .print("0 ")                 // bci
        .append(uses)                // use
        .append(" v").append(number) // tid
        .append(" Phi");
    for (Value operand : operands) {
      printer.append(" v").append(operand.number);
    }
  }

  public void addDefinitionsUser(Map<Integer, Value> currentDefinitions) {
    definitionUsers.add(currentDefinitions);
  }

  public void removeDefinitionsUser(Map<Integer, Value> currentDefinitions) {
    definitionUsers.remove(currentDefinitions);
  }

  public void clearDefinitionsUsers() {
    definitionUsers = null;
  }

  /**
   * Determine if the only possible values for the phi are the integers 0 or 1.
   */
  @Override
  public boolean knownToBeBoolean() {
    return knownToBeBoolean(new HashSet<>());
  }

  private boolean knownToBeBoolean(HashSet<Phi> active) {
    active.add(this);

    for (Value operand : operands) {
      if (!operand.isPhi()) {
        if (operand.isConstNumber()) {
          ConstNumber number = operand.getConstInstruction().asConstNumber();
          if (!number.isIntegerOne() && !number.isIntegerZero()) {
            return false;
          }
        } else {
          return false;
        }
      }
    }

    for (Value operand : operands) {
      if (operand.isPhi() && !active.contains(operand.asPhi())) {
        if (!operand.asPhi().knownToBeBoolean(active)) {
          return false;
        }
      }
    }

    return true;
  }

  @Override
  public boolean isConstant() {
    return false;
  }

  @Override
  public boolean needsRegister() {
    return true;
  }

  public Set<Value> getDebugValues() {
    return debugValues != null ? debugValues : ImmutableSet.of();
  }

  public boolean usesValueOneTime(Value usedValue) {
    return operands.indexOf(usedValue) == operands.lastIndexOf(usedValue);
  }

  public DexType computeVerificationType(TypeVerificationHelper helper) {
    assert outType().isObject();
    Set<DexType> operandTypes = new HashSet<>(operands.size());
    for (Value operand : operands) {
      DexType operandType = helper.getType(operand);
      if (operandType != null) {
        operandTypes.add(operandType);
      }
    }
    return helper.join(operandTypes);
  }
}
