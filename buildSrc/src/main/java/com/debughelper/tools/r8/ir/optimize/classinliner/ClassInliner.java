// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.classinliner;

import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility;
import com.debughelper.tools.r8.ir.optimize.Inliner.InliningInfo;
import com.debughelper.tools.r8.ir.optimize.Inliner.Reason;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.ConstNumber;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.InstanceGet;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.InvokeDirect;
import com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.debughelper.tools.r8.ir.code.NewInstance;
import com.debughelper.tools.r8.ir.code.Phi;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.google.common.collect.Streams;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class ClassInliner {
  private final com.debughelper.tools.r8.graph.DexItemFactory factory;
  private final int totalMethodInstructionLimit;
  private final ConcurrentHashMap<com.debughelper.tools.r8.graph.DexType, Boolean> knownClasses = new ConcurrentHashMap<>();

  public interface InlinerAction {
    void inline(Map<com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver, InliningInfo> methods);
  }

  public ClassInliner(DexItemFactory factory, int totalMethodInstructionLimit) {
    this.factory = factory;
    this.totalMethodInstructionLimit = totalMethodInstructionLimit;
  }

  // Process method code and inline eligible class instantiations, in short:
  //
  // - collect all 'new-instance' instructions in the original code. Note that class
  // inlining, if happens, mutates code and may add new 'new-instance' instructions.
  // Processing them as well is possible, but does not seem to bring much value.
  //
  // - for each 'new-instance' we check if it is eligible for inlining, i.e:
  //     -> the class of the new instance is 'eligible' (see computeClassEligible(...))
  //     -> the instance is initialized with 'eligible' constructor (see comments in
  //        CodeRewriter::identifyClassInlinerEligibility(...))
  //     -> has only 'eligible' uses, i.e:
  //          * as a receiver of a field read/write for a field defined in same class
  //            as method.
  //          * as a receiver of virtual or interface call with single target being
  //            an eligible method according to identifyClassInlinerEligibility(...);
  //            NOTE: if method receiver is used as a return value, the method call
  //            should ignore return value
  //
  // - inline eligible 'new-instance' instructions, i.e:
  //     -> force inline methods called on the instance (including the initializer);
  //        (this may introduce additional instance field reads/writes on the receiver)
  //     -> replace instance field reads with appropriate values calculated based on
  //        fields writes
  //     -> remove the call to superclass initializer
  //     -> remove all field writes
  //     -> remove 'new-instance' instructions
  //
  // For example:
  //
  // Original code:
  //   class C {
  //     static class L {
  //       final int x;
  //       L(int x) {
  //         this.x = x;
  //       }
  //       int getX() {
  //         return x;
  //       }
  //     }
  //     static int method1() {
  //       return new L(1).x;
  //     }
  //     static int method2() {
  //       return new L(1).getX();
  //     }
  //   }
  //
  // Code after class C is 'inlined':
  //   class C {
  //     static int method1() {
  //       return 1;
  //     }
  //     static int method2() {
  //       return 1;
  //     }
  //   }
  //
  public final void processMethodCode(
      com.debughelper.tools.r8.graph.AppInfoWithSubtyping appInfo,
      com.debughelper.tools.r8.graph.DexEncodedMethod method,
      com.debughelper.tools.r8.ir.code.IRCode code,
      Predicate<com.debughelper.tools.r8.graph.DexEncodedMethod> isProcessedConcurrently,
      InlinerAction inliner) {

    // Collect all the new-instance instructions in the code before inlining.
    List<com.debughelper.tools.r8.ir.code.NewInstance> newInstances = Streams.stream(code.instructionIterator())
        .filter(com.debughelper.tools.r8.ir.code.Instruction::isNewInstance)
        .map(com.debughelper.tools.r8.ir.code.Instruction::asNewInstance)
        .collect(Collectors.toList());

    for (com.debughelper.tools.r8.ir.code.NewInstance newInstance : newInstances) {
      com.debughelper.tools.r8.ir.code.Value eligibleInstance = newInstance.outValue();
      if (eligibleInstance == null) {
        continue;
      }

      com.debughelper.tools.r8.graph.DexType eligibleClass = newInstance.clazz;
      if (!isClassEligible(appInfo, eligibleClass)) {
        continue;
      }

      Map<com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver, InliningInfo> methodCalls = checkInstanceUsersEligibility(
          appInfo, method, isProcessedConcurrently, newInstance, eligibleInstance, eligibleClass);
      if (methodCalls == null) {
        continue;
      }

      if (getTotalEstimatedMethodSize(methodCalls) >= totalMethodInstructionLimit) {
        continue;
      }

      // Inline the class instance.
      forceInlineAllMethodInvocations(inliner, methodCalls);
      removeSuperClassInitializerAndFieldReads(code, newInstance, eligibleInstance);
      removeFieldWrites(eligibleInstance, eligibleClass);
      removeInstruction(newInstance);

      // Restore normality.
      code.removeAllTrivialPhis();
      assert code.isConsistentSSA();
    }
  }

  private Map<com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver, InliningInfo> checkInstanceUsersEligibility(
          com.debughelper.tools.r8.graph.AppInfoWithSubtyping appInfo, com.debughelper.tools.r8.graph.DexEncodedMethod method,
          Predicate<com.debughelper.tools.r8.graph.DexEncodedMethod> isProcessedConcurrently,
          com.debughelper.tools.r8.ir.code.NewInstance newInstanceInsn, com.debughelper.tools.r8.ir.code.Value receiver, com.debughelper.tools.r8.graph.DexType clazz) {

    // No Phi users.
    if (receiver.numberOfPhiUsers() > 0) {
      return null; // Not eligible.
    }

    Map<com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver, InliningInfo> methodCalls = new IdentityHashMap<>();

    for (com.debughelper.tools.r8.ir.code.Instruction user : receiver.uniqueUsers()) {
      // Field read/write.
      if (user.isInstanceGet() ||
          (user.isInstancePut() && user.asInstancePut().value() != receiver)) {
        if (user.asFieldInstruction().getField().clazz == newInstanceInsn.clazz) {
          // Eligible field read or write. Note: as long as we consider only classes eligible
          // if they directly extend java.lang.Object we don't need to check if the field
          // really exists in the class.
          continue;
        }
        return null; // Not eligible.
      }

      // Eligible constructor call.
      if (user.isInvokeDirect()) {
        InliningInfo inliningInfo = isEligibleConstructorCall(appInfo, method,
            user.asInvokeDirect(), receiver, clazz, isProcessedConcurrently);
        if (inliningInfo != null) {
          methodCalls.put(user.asInvokeDirect(), inliningInfo);
          continue;
        }
        return null; // Not eligible.
      }

      // Eligible virtual method call.
      if (user.isInvokeVirtual() || user.isInvokeInterface()) {
        InliningInfo inliningInfo = isEligibleMethodCall(
            appInfo, method, user.asInvokeMethodWithReceiver(),
            receiver, clazz, isProcessedConcurrently);
        if (inliningInfo != null) {
          methodCalls.put(user.asInvokeMethodWithReceiver(), inliningInfo);
          continue;
        }
        return null;  // Not eligible.
      }

      return null;  // Not eligible.
    }
    return methodCalls;
  }

  // Remove call to superclass initializer, replace field reads with appropriate
  // values, insert phis when needed.
  private void removeSuperClassInitializerAndFieldReads(
          com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.NewInstance newInstance, com.debughelper.tools.r8.ir.code.Value eligibleInstance) {
    Map<com.debughelper.tools.r8.graph.DexField, FieldValueHelper> fieldHelpers = new IdentityHashMap<>();
    for (com.debughelper.tools.r8.ir.code.Instruction user : eligibleInstance.uniqueUsers()) {
      // Remove the call to superclass constructor.
      if (user.isInvokeDirect() &&
          user.asInvokeDirect().getInvokedMethod() == factory.objectMethods.constructor) {
        removeInstruction(user);
        continue;
      }

      if (user.isInstanceGet()) {
        // Replace a field read with appropriate value.
        replaceFieldRead(code, newInstance, user.asInstanceGet(), fieldHelpers);
        continue;
      }

      if (user.isInstancePut()) {
        // Skip in this iteration since these instructions are needed to
        // properly calculate what value should field reads be replaced with.
        continue;
      }

      throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected usage left after method inlining: " + user);
    }
  }

  private void removeFieldWrites(com.debughelper.tools.r8.ir.code.Value receiver, com.debughelper.tools.r8.graph.DexType clazz) {
    for (com.debughelper.tools.r8.ir.code.Instruction user : receiver.uniqueUsers()) {
      if (!user.isInstancePut()) {
        throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected usage left after field reads removed: " + user);
      }
      if (user.asInstancePut().getField().clazz != clazz) {
        throw new Unreachable("Unexpected field write left after field reads removed: " + user);
      }
      removeInstruction(user);
    }
  }

  private int getTotalEstimatedMethodSize(Map<com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver, InliningInfo> methodCalls) {
    int totalSize = 0;
    for (InliningInfo info : methodCalls.values()) {
      totalSize += info.target.getCode().estimatedSizeForInlining();
    }
    return totalSize;
  }

  private void replaceFieldRead(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.NewInstance newInstance,
                                InstanceGet fieldRead, Map<com.debughelper.tools.r8.graph.DexField, FieldValueHelper> fieldHelpers) {

    com.debughelper.tools.r8.ir.code.Value value = fieldRead.outValue();
    if (value != null) {
      FieldValueHelper helper = fieldHelpers.computeIfAbsent(
          fieldRead.getField(), field -> new FieldValueHelper(field, code, newInstance));
      com.debughelper.tools.r8.ir.code.Value newValue = helper.getValueForFieldRead(fieldRead.getBlock(), fieldRead);
      value.replaceUsers(newValue);
      for (FieldValueHelper fieldValueHelper : fieldHelpers.values()) {
        fieldValueHelper.replaceValue(value, newValue);
      }
      assert value.numberOfAllUsers() == 0;
    }
    removeInstruction(fieldRead);
  }

  // Describes and caches what values are supposed to be used instead of field reads.
  private static final class FieldValueHelper {
    final com.debughelper.tools.r8.graph.DexField field;
    final com.debughelper.tools.r8.ir.code.IRCode code;
    final com.debughelper.tools.r8.ir.code.NewInstance newInstance;

    private com.debughelper.tools.r8.ir.code.Value defaultValue = null;
    private final Map<com.debughelper.tools.r8.ir.code.BasicBlock, com.debughelper.tools.r8.ir.code.Value> ins = new IdentityHashMap<>();
    private final Map<com.debughelper.tools.r8.ir.code.BasicBlock, com.debughelper.tools.r8.ir.code.Value> outs = new IdentityHashMap<>();

    private FieldValueHelper(DexField field, IRCode code, NewInstance newInstance) {
      this.field = field;
      this.code = code;
      this.newInstance = newInstance;
    }

    void replaceValue(com.debughelper.tools.r8.ir.code.Value oldValue, com.debughelper.tools.r8.ir.code.Value newValue) {
      for (Entry<com.debughelper.tools.r8.ir.code.BasicBlock, com.debughelper.tools.r8.ir.code.Value> entry : ins.entrySet()) {
        if (entry.getValue() == oldValue) {
          entry.setValue(newValue);
        }
      }
      for (Entry<com.debughelper.tools.r8.ir.code.BasicBlock, com.debughelper.tools.r8.ir.code.Value> entry : outs.entrySet()) {
        if (entry.getValue() == oldValue) {
          entry.setValue(newValue);
        }
      }
    }

    com.debughelper.tools.r8.ir.code.Value getValueForFieldRead(com.debughelper.tools.r8.ir.code.BasicBlock block, com.debughelper.tools.r8.ir.code.Instruction valueUser) {
      assert valueUser != null;
      com.debughelper.tools.r8.ir.code.Value value = getValueDefinedInTheBlock(block, valueUser);
      return value != null ? value : getOrCreateInValue(block);
    }

    private com.debughelper.tools.r8.ir.code.Value getOrCreateOutValue(com.debughelper.tools.r8.ir.code.BasicBlock block) {
      com.debughelper.tools.r8.ir.code.Value value = outs.get(block);
      if (value != null) {
        return value;
      }

      value = getValueDefinedInTheBlock(block, null);
      if (value == null) {
        // No value defined in the block.
        value = getOrCreateInValue(block);
      }

      assert value != null;
      outs.put(block, value);
      return value;
    }

    private com.debughelper.tools.r8.ir.code.Value getOrCreateInValue(com.debughelper.tools.r8.ir.code.BasicBlock block) {
      com.debughelper.tools.r8.ir.code.Value value = ins.get(block);
      if (value != null) {
        return value;
      }

      List<com.debughelper.tools.r8.ir.code.BasicBlock> predecessors = block.getPredecessors();
      if (predecessors.size() == 1) {
        value = getOrCreateOutValue(predecessors.get(0));
        ins.put(block, value);
      } else {
        // Create phi, add it to the block, cache in ins map for future use.
        com.debughelper.tools.r8.ir.code.Phi phi = new Phi(code.valueNumberGenerator.next(),
            block, com.debughelper.tools.r8.ir.code.ValueType.fromDexType(field.type), null);
        ins.put(block, phi);

        List<com.debughelper.tools.r8.ir.code.Value> operands = new ArrayList<>();
        for (com.debughelper.tools.r8.ir.code.BasicBlock predecessor : block.getPredecessors()) {
          operands.add(getOrCreateOutValue(predecessor));
        }
        // Add phi, but don't remove trivial phis; since we cache the phi
        // we just created for future use we should delay removing trivial
        // phis until we are done with replacing fields reads.
        phi.addOperands(operands, false);
        value = phi;
      }

      assert value != null;
      return value;
    }

    private com.debughelper.tools.r8.ir.code.Value getValueDefinedInTheBlock(BasicBlock block, com.debughelper.tools.r8.ir.code.Instruction stopAt) {
      InstructionListIterator iterator = stopAt == null ?
          block.listIterator(block.getInstructions().size()) : block.listIterator(stopAt);

      com.debughelper.tools.r8.ir.code.Instruction valueProducingInsn = null;
      while (iterator.hasPrevious()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = iterator.previous();
        assert instruction != null;

        if (instruction == newInstance ||
            (instruction.isInstancePut() &&
                instruction.asInstancePut().getField() == field &&
                instruction.asInstancePut().object() == newInstance.outValue())) {
          valueProducingInsn = instruction;
          break;
        }
      }

      if (valueProducingInsn == null) {
        return null;
      }
      if (valueProducingInsn.isInstancePut()) {
        return valueProducingInsn.asInstancePut().value();
      }

      assert newInstance == valueProducingInsn;
      if (defaultValue == null) {
        // If we met newInstance it means that default value is supposed to be used.
        defaultValue = code.createValue(ValueType.fromDexType(field.type));
        com.debughelper.tools.r8.ir.code.ConstNumber defaultValueInsn = new ConstNumber(defaultValue, 0);
        defaultValueInsn.setPosition(newInstance.getPosition());
        LinkedList<com.debughelper.tools.r8.ir.code.Instruction> instructions = block.getInstructions();
        instructions.add(instructions.indexOf(newInstance) + 1, defaultValueInsn);
        defaultValueInsn.setBlock(block);
      }
      return defaultValue;
    }
  }

  private void forceInlineAllMethodInvocations(
      InlinerAction inliner, Map<com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver, InliningInfo> methodCalls) {
    if (!methodCalls.isEmpty()) {
      inliner.inline(methodCalls);
    }
  }

  private void removeInstruction(Instruction instruction) {
    instruction.inValues().forEach(v -> v.removeUser(instruction));
    instruction.getBlock().removeInstruction(instruction);
  }

  private com.debughelper.tools.r8.graph.DexEncodedMethod findSingleTarget(
          com.debughelper.tools.r8.graph.AppInfo appInfo, com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver invoke, com.debughelper.tools.r8.graph.DexType instanceType) {

    // We don't use computeSingleTarget(...) on invoke since it sometimes fails to
    // find the single target, while this code may be more successful since we exactly
    // know what is the actual type of the receiver.

    // Note that we also intentionally limit ourselves to methods directly defined in
    // the instance's class. This may be improved later.

    com.debughelper.tools.r8.graph.DexClass clazz = appInfo.definitionFor(instanceType);
    if (clazz != null) {
      com.debughelper.tools.r8.graph.DexMethod callee = invoke.getInvokedMethod();
      for (com.debughelper.tools.r8.graph.DexEncodedMethod candidate : clazz.virtualMethods()) {
        if (candidate.method.name == callee.name && candidate.method.proto == callee.proto) {
          return candidate;
        }
      }
    }
    return null;
  }

  private InliningInfo isEligibleConstructorCall(
      com.debughelper.tools.r8.graph.AppInfoWithSubtyping appInfo,
      com.debughelper.tools.r8.graph.DexEncodedMethod method,
      InvokeDirect initInvoke,
      com.debughelper.tools.r8.ir.code.Value receiver,
      com.debughelper.tools.r8.graph.DexType inlinedClass,
      Predicate<com.debughelper.tools.r8.graph.DexEncodedMethod> isProcessedConcurrently) {

    // Must be a constructor of the exact same class.
    DexMethod init = initInvoke.getInvokedMethod();
    if (!factory.isConstructor(init)) {
      return null;
    }
    // Must be a constructor called on the receiver.
    if (initInvoke.inValues().lastIndexOf(receiver) != 0) {
      return null;
    }

    assert init.holder == inlinedClass
        : "Inlined constructor? [invoke: " + initInvoke +
        ", expected class: " + inlinedClass + "]";

    com.debughelper.tools.r8.graph.DexEncodedMethod definition = appInfo.definitionFor(init);
    if (definition == null || isProcessedConcurrently.test(definition)) {
      return null;
    }

    if (!definition.isInliningCandidate(method, Reason.SIMPLE, appInfo)) {
      // We won't be able to inline it here.

      // Note that there may be some false negatives here since the method may
      // reference private fields of its class which are supposed to be replaced
      // with arguments after inlining. We should try and improve it later.

      // Using -allowaccessmodification mitigates this.
      return null;
    }

    return definition.getOptimizationInfo().getClassInlinerEligibility() != null
        ? new InliningInfo(definition, inlinedClass) : null;
  }

  private InliningInfo isEligibleMethodCall(
      AppInfoWithSubtyping appInfo,
      com.debughelper.tools.r8.graph.DexEncodedMethod method,
      InvokeMethodWithReceiver invoke,
      Value receiver,
      com.debughelper.tools.r8.graph.DexType inlinedClass,
      Predicate<com.debughelper.tools.r8.graph.DexEncodedMethod> isProcessedConcurrently) {

    if (invoke.inValues().lastIndexOf(receiver) > 0) {
      return null; // Instance passed as an argument.
    }

    com.debughelper.tools.r8.graph.DexEncodedMethod singleTarget =
        findSingleTarget(appInfo, invoke, inlinedClass);
    if (singleTarget == null || isProcessedConcurrently.test(singleTarget)) {
      return null;
    }
    if (method == singleTarget) {
      return null; // Don't inline itself.
    }

    com.debughelper.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility eligibility =
        singleTarget.getOptimizationInfo().getClassInlinerEligibility();
    if (eligibility == null) {
      return null;
    }

    // If the method returns receiver and the return value is actually
    // used in the code the method is not eligible.
    if (eligibility.returnsReceiver &&
        invoke.outValue() != null && invoke.outValue().numberOfAllUsers() > 0) {
      return null;
    }

    if (!singleTarget.isInliningCandidate(method, Reason.SIMPLE, appInfo)) {
      // We won't be able to inline it here.

      // Note that there may be some false negatives here since the method may
      // reference private fields of its class which are supposed to be replaced
      // with arguments after inlining. We should try and improve it later.

      // Using -allowaccessmodification mitigates this.
      return null;
    }

    return new InliningInfo(singleTarget, inlinedClass);
  }

  private boolean isClassEligible(com.debughelper.tools.r8.graph.AppInfo appInfo, com.debughelper.tools.r8.graph.DexType clazz) {
    Boolean eligible = knownClasses.get(clazz);
    if (eligible == null) {
      Boolean computed = computeClassEligible(appInfo, clazz);
      Boolean existing = knownClasses.putIfAbsent(clazz, computed);
      assert existing == null || existing == computed;
      eligible = existing == null ? computed : existing;
    }
    return eligible;
  }

  // Class is eligible for this optimization. Eligibility implementation:
  //   - is not an abstract class or interface
  //   - directly extends java.lang.Object
  //   - does not declare finalizer
  //   - does not trigger any static initializers
  private boolean computeClassEligible(AppInfo appInfo, DexType clazz) {
    DexClass definition = appInfo.definitionFor(clazz);
    if (definition == null || definition.isLibraryClass() ||
        definition.accessFlags.isAbstract() || definition.accessFlags.isInterface()) {
      return false;
    }

    // Must directly extend Object.
    if (definition.superType != factory.objectType) {
      return false;
    }

    // Class must not define finalizer.
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : definition.virtualMethods()) {
      if (method.method.name == factory.finalizeMethodName &&
          method.method.proto == factory.objectMethods.finalize.proto) {
        return false;
      }
    }

    // Check for static initializers in this class or any of interfaces it implements.
    return !appInfo.canTriggerStaticInitializer(clazz);
  }
}
