// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.ir.analysis.type.TypeEnvironment;
import com.debughelper.tools.r8.ir.optimize.Inliner;
import com.debughelper.tools.r8.ir.optimize.Inliner.InlineAction;
import com.debughelper.tools.r8.ir.optimize.Inliner.Reason;
import com.debughelper.tools.r8.ir.optimize.InliningInfo;
import com.debughelper.tools.r8.graph.Code;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.InvokeMethod;
import com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.debughelper.tools.r8.ir.code.InvokePolymorphic;
import com.debughelper.tools.r8.ir.code.InvokeStatic;
import com.debughelper.tools.r8.ir.conversion.CallSiteInformation;
import com.debughelper.tools.r8.logging.Log;

import java.util.ListIterator;
import java.util.function.Predicate;

final class DefaultInliningOracle implements InliningOracle, InliningStrategy {

  private final com.debughelper.tools.r8.ir.optimize.Inliner inliner;
  private final com.debughelper.tools.r8.graph.DexEncodedMethod method;
  private final com.debughelper.tools.r8.ir.code.IRCode code;
  private final TypeEnvironment typeEnvironment;
  private final com.debughelper.tools.r8.ir.conversion.CallSiteInformation callSiteInformation;
  private final Predicate<com.debughelper.tools.r8.graph.DexEncodedMethod> isProcessedConcurrently;
  private final com.debughelper.tools.r8.ir.optimize.InliningInfo info;
  private final int inliningInstructionLimit;
  private int instructionAllowance;

  DefaultInliningOracle(
      Inliner inliner,
      com.debughelper.tools.r8.graph.DexEncodedMethod method,
      com.debughelper.tools.r8.ir.code.IRCode code,
      TypeEnvironment typeEnvironment,
      CallSiteInformation callSiteInformation,
      Predicate<com.debughelper.tools.r8.graph.DexEncodedMethod> isProcessedConcurrently,
      int inliningInstructionLimit,
      int inliningInstructionAllowance) {
    this.inliner = inliner;
    this.method = method;
    this.code = code;
    this.typeEnvironment = typeEnvironment;
    this.callSiteInformation = callSiteInformation;
    this.isProcessedConcurrently = isProcessedConcurrently;
    info = com.debughelper.tools.r8.logging.Log.ENABLED ? new InliningInfo(method) : null;
    this.inliningInstructionLimit = inliningInstructionLimit;
    this.instructionAllowance = inliningInstructionAllowance;
  }

  @Override
  public void finish() {
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      com.debughelper.tools.r8.logging.Log.debug(getClass(), info.toString());
    }
  }

  private com.debughelper.tools.r8.graph.DexEncodedMethod validateCandidate(com.debughelper.tools.r8.ir.code.InvokeMethod invoke, com.debughelper.tools.r8.graph.DexType invocationContext) {
    com.debughelper.tools.r8.graph.DexEncodedMethod candidate =
        invoke.computeSingleTarget(inliner.appInfo, typeEnvironment, invocationContext);
    if ((candidate == null)
        || (candidate.getCode() == null)
        || inliner.appInfo.definitionFor(candidate.method.getHolder()).isLibraryClass()) {
      if (info != null) {
        info.exclude(invoke, "No inlinee");
      }
      return null;
    }
    // Ignore the implicit receiver argument.
    int numberOfArguments =
        invoke.arguments().size() - (invoke.isInvokeMethodWithReceiver() ? 1 : 0);
    if (numberOfArguments != candidate.method.getArity()) {
      if (info != null) {
        info.exclude(invoke, "Argument number mismatch");
      }
      return null;
    }
    return candidate;
  }

  private Reason computeInliningReason(com.debughelper.tools.r8.graph.DexEncodedMethod target) {
    if (target.getOptimizationInfo().forceInline()) {
      return Reason.FORCE;
    }
    if (inliner.appInfo.hasLiveness()
        && inliner.appInfo.withLiveness().alwaysInline.contains(target)) {
      return Reason.ALWAYS;
    }
    if (callSiteInformation.hasSingleCallSite(target)) {
      return Reason.SINGLE_CALLER;
    }
    if (isDoubleInliningTarget(target)) {
      return Reason.DUAL_CALLER;
    }
    return Reason.SIMPLE;
  }

  private boolean canInlineStaticInvoke(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.graph.DexEncodedMethod target) {
    // Only proceed with inlining a static invoke if:
    // - the holder for the target equals the holder for the method, or
    // - the target method always triggers class initialization of its holder before any other side
    //   effect (hence preserving class initialization semantics).
    // - there is no non-trivial class initializer.
    com.debughelper.tools.r8.graph.DexType targetHolder = target.method.getHolder();
    if (method.method.getHolder() == targetHolder) {
      return true;
    }
    com.debughelper.tools.r8.graph.DexClass clazz = inliner.appInfo.definitionFor(targetHolder);
    assert clazz != null;
    if (target.getOptimizationInfo().triggersClassInitBeforeAnySideEffect()) {
      return true;
    }
    return classInitializationHasNoSideffects(targetHolder);
  }

  /**
   * Check for class initializer side effects when loading this class, as inlining might remove the
   * load operation.
   * <p>
   * See https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-5.html#jvms-5.5.
   * <p>
   * For simplicity, we are conservative and consider all interfaces, not only the ones with default
   * methods.
   */
  private boolean classInitializationHasNoSideffects(com.debughelper.tools.r8.graph.DexType classToCheck) {
    com.debughelper.tools.r8.graph.DexClass clazz = inliner.appInfo.definitionFor(classToCheck);
    if ((clazz == null)
        || clazz.hasNonTrivialClassInitializer()
        || clazz.defaultValuesForStaticFieldsMayTriggerAllocation()) {
      return false;
    }
    for (com.debughelper.tools.r8.graph.DexType iface : clazz.interfaces.values) {
      if (!classInitializationHasNoSideffects(iface)) {
        return false;
      }
    }
    return clazz.superType == null || classInitializationHasNoSideffects(clazz.superType);
  }

  private synchronized boolean isDoubleInliningTarget(com.debughelper.tools.r8.graph.DexEncodedMethod candidate) {
    // 10 is found from measuring.
    return inliner.isDoubleInliningTarget(callSiteInformation, candidate)
        && candidate.getCode().estimatedSizeForInliningAtMost(10);
  }

  private boolean passesInliningConstraints(com.debughelper.tools.r8.ir.code.InvokeMethod invoke, com.debughelper.tools.r8.graph.DexEncodedMethod candidate,
                                            Reason reason) {
    if (method == candidate) {
      // Cannot handle recursive inlining at this point.
      // Force inlined method should never be recursive.
      assert !candidate.getOptimizationInfo().forceInline();
      if (info != null) {
        info.exclude(invoke, "direct recursion");
      }
      return false;
    }

    if (reason != Reason.FORCE && isProcessedConcurrently.test(candidate)) {
      if (info != null) {
        info.exclude(invoke, "is processed in parallel");
      }
      return false;
    }

    // Abort inlining attempt if method -> target access is not right.
    if (!inliner.hasInliningAccess(method, candidate)) {
      if (info != null) {
        info.exclude(invoke, "target does not have right access");
      }
      return false;
    }

    DexClass holder = inliner.appInfo.definitionFor(candidate.method.getHolder());
    if (holder.isInterface()) {
      // Art978_virtual_interfaceTest correctly expects an IncompatibleClassChangeError exception at
      // runtime.
      if (info != null) {
        info.exclude(invoke, "Do not inline target if method holder is an interface class");
      }
      return false;
    }

    if (holder.isLibraryClass()) {
      // Library functions should not be inlined.
      return false;
    }

    // Don't inline if target is synchronized.
    if (candidate.accessFlags.isSynchronized()) {
      if (info != null) {
        info.exclude(invoke, "target is synchronized");
      }
      return false;
    }

    // Attempt to inline a candidate that is only called twice.
    if ((reason == Reason.DUAL_CALLER) && (inliner.doubleInlining(method, candidate) == null)) {
      if (info != null) {
        info.exclude(invoke, "target is not ready for double inlining");
      }
      return false;
    }

    if (reason == Reason.SIMPLE) {
      // If we are looking for a simple method, only inline if actually simple.
      Code code = candidate.getCode();
      if (!code.estimatedSizeForInliningAtMost(inliningInstructionLimit)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public InlineAction computeForInvokeWithReceiver(
          InvokeMethodWithReceiver invoke, com.debughelper.tools.r8.graph.DexType invocationContext) {
    com.debughelper.tools.r8.graph.DexEncodedMethod candidate = validateCandidate(invoke, invocationContext);
    if (candidate == null || inliner.isBlackListed(candidate.method)) {
      return null;
    }

    // We can only inline an instance method call if we preserve the null check semantic (which
    // would throw NullPointerException if the receiver is null). Therefore we can inline only if
    // one of the following conditions is true:
    // * the candidate inlinee checks null receiver before any side effect
    // * the receiver is known to be non-null
    boolean receiverIsNeverNull =
        !typeEnvironment.getLatticeElement(invoke.getReceiver()).isNullable();
    if (!receiverIsNeverNull
        && !candidate.getOptimizationInfo().checksNullReceiverBeforeAnySideEffect()) {
      if (info != null) {
        info.exclude(invoke, "receiver for candidate can be null");
      }
      return null;
    }

    Reason reason = computeInliningReason(candidate);
    if (!candidate.isInliningCandidate(method, reason, inliner.appInfo)) {
      // Abort inlining attempt if the single target is not an inlining candidate.
      if (info != null) {
        info.exclude(invoke, "target is not identified for inlining");
      }
      return null;
    }

    if (!passesInliningConstraints(invoke, candidate, reason)) {
      return null;
    }

    if (info != null) {
      info.include(invoke.getType(), candidate);
    }
    return new InlineAction(candidate, invoke, reason);
  }

  @Override
  public InlineAction computeForInvokeStatic(InvokeStatic invoke, com.debughelper.tools.r8.graph.DexType invocationContext) {
    com.debughelper.tools.r8.graph.DexEncodedMethod candidate = validateCandidate(invoke, invocationContext);
    if (candidate == null || inliner.isBlackListed(candidate.method)) {
      return null;
    }

    Reason reason = computeInliningReason(candidate);
    // Determine if this should be inlined no matter how big it is.
    if (!candidate.isInliningCandidate(method, reason, inliner.appInfo)) {
      // Abort inlining attempt if the single target is not an inlining candidate.
      if (info != null) {
        info.exclude(invoke, "target is not identified for inlining");
      }
      return null;
    }

    // Abort inlining attempt if we can not guarantee class for static target has been initialized.
    if (!canInlineStaticInvoke(method, candidate)) {
      if (info != null) {
        info.exclude(invoke, "target is static but we cannot guarantee class has been initialized");
      }
      return null;
    }

    if (!passesInliningConstraints(invoke, candidate, reason)) {
      return null;
    }

    if (info != null) {
      info.include(invoke.getType(), candidate);
    }
    return new InlineAction(candidate, invoke, reason);
  }

  @Override
  public InlineAction computeForInvokePolymorphic(
          InvokePolymorphic invoke, com.debughelper.tools.r8.graph.DexType invocationContext) {
    // TODO: No inlining of invoke polymorphic for now.
    if (info != null) {
      info.exclude(invoke, "inlining through invoke signature polymorpic is not supported");
    }
    return null;
  }

  @Override
  public void ensureMethodProcessed(com.debughelper.tools.r8.graph.DexEncodedMethod target, com.debughelper.tools.r8.ir.code.IRCode inlinee) {
    if (!target.isProcessed()) {
      if (com.debughelper.tools.r8.logging.Log.ENABLED) {
        Log.verbose(getClass(), "Forcing extra inline on " + target.toSourceString());
      }
      inliner.performInlining(
          target, inlinee, typeEnvironment, isProcessedConcurrently, callSiteInformation);
    }
  }

  @Override
  public boolean isValidTarget(com.debughelper.tools.r8.ir.code.InvokeMethod invoke, DexEncodedMethod target, com.debughelper.tools.r8.ir.code.IRCode inlinee) {
    return !target.isInstanceInitializer()
        || inliner.legalConstructorInline(method, invoke, inlinee);
  }

  @Override
  public boolean exceededAllowance() {
    return instructionAllowance < 0;
  }

  @Override
  public void markInlined(com.debughelper.tools.r8.ir.code.IRCode inlinee) {
    instructionAllowance -= inliner.numberOfInstructions(inlinee);
  }

  @Override
  public ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> updateTypeInformationIfNeeded(IRCode inlinee,
                                                                                                 ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blockIterator, com.debughelper.tools.r8.ir.code.BasicBlock block, BasicBlock invokeSuccessor) {
    if (inliner.options.enableNonNullTracking) {
      // Move the cursor back to where the inlinee blocks are added.
      blockIterator = code.blocks.listIterator(code.blocks.indexOf(block));
      // Kick off the tracker to add non-null IRs only to the inlinee blocks.
      new NonNullTracker()
          .addNonNullInPart(code, blockIterator, inlinee.blocks::contains);
      // Move the cursor forward to where the inlinee blocks end.
      blockIterator = code.blocks.listIterator(code.blocks.indexOf(invokeSuccessor));
    }
    // Update type env for inlined blocks.
    typeEnvironment.analyzeBlocks(inlinee.topologicallySortedBlocks());
    // TODO(b/69964136): need a test where refined env in inlinee affects the caller.
    return blockIterator;
  }

  @Override
  public DexType getReceiverTypeIfKnown(InvokeMethod invoke) {
    return null; // Maybe improve later.
  }
}
