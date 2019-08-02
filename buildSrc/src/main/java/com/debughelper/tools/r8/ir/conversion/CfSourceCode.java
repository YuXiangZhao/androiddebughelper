// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.conversion;

import static it.unimi.dsi.fastutil.ints.Int2ObjectSortedMaps.emptyMap;

import com.debughelper.tools.r8.cf.code.CfFrame;
import com.debughelper.tools.r8.cf.code.CfFrame.FrameType;
import com.debughelper.tools.r8.graph.CfCode;
import com.debughelper.tools.r8.graph.CfCode.LocalVariableInfo;
import com.debughelper.tools.r8.ir.conversion.CfState.Snapshot;
import com.debughelper.tools.r8.ir.conversion.IRBuilder.BlockInfo;
import com.debughelper.tools.r8.ir.conversion.SourceCode;
import com.debughelper.tools.r8.cf.code.CfGoto;
import com.debughelper.tools.r8.cf.code.CfInstruction;
import com.debughelper.tools.r8.cf.code.CfLabel;
import com.debughelper.tools.r8.cf.code.CfNew;
import com.debughelper.tools.r8.cf.code.CfPosition;
import com.debughelper.tools.r8.cf.code.CfSwitch;
import com.debughelper.tools.r8.cf.code.CfThrow;
import com.debughelper.tools.r8.cf.code.CfTryCatch;
import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.CatchHandlers;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.origin.Origin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import java.util.ArrayList;
import java.util.List;

public class CfSourceCode implements SourceCode {

  private IRBuilder.BlockInfo currentBlockInfo;

  private static class TryHandlerList {

    public final int startOffset;
    public final int endOffset;
    public final List<com.debughelper.tools.r8.graph.DexType> guards;
    public final IntList offsets;

    TryHandlerList(int startOffset, int endOffset, List<com.debughelper.tools.r8.graph.DexType> guards, IntList offsets) {
      this.startOffset = startOffset;
      this.endOffset = endOffset;
      this.guards = guards;
      this.offsets = offsets;
    }

    boolean validFor(int instructionOffset) {
      return startOffset <= instructionOffset && instructionOffset < endOffset;
    }

    boolean isEmpty() {
      assert guards.isEmpty() == offsets.isEmpty();
      return guards.isEmpty();
    }

    static TryHandlerList computeTryHandlers(
        int instructionOffset,
        List<com.debughelper.tools.r8.cf.code.CfTryCatch> tryCatchRanges,
        Reference2IntMap<com.debughelper.tools.r8.cf.code.CfLabel> labelOffsets) {
      int startOffset = Integer.MIN_VALUE;
      int endOffset = Integer.MAX_VALUE;
      List<com.debughelper.tools.r8.graph.DexType> guards = new ArrayList<>();
      IntList offsets = new IntArrayList();
      ReferenceSet<com.debughelper.tools.r8.graph.DexType> seen = new ReferenceOpenHashSet<>();
      for (CfTryCatch tryCatch : tryCatchRanges) {
        int start = labelOffsets.getInt(tryCatch.start);
        int end = labelOffsets.getInt(tryCatch.end);
        if (start > instructionOffset) {
          endOffset = Math.min(endOffset, start);
          continue;
        } else if (instructionOffset >= end) {
          startOffset = Math.max(startOffset, end);
          continue;
        }
        startOffset = Math.max(startOffset, start);
        endOffset = Math.min(endOffset, end);
        boolean seenCatchAll = false;
        for (int i = 0; i < tryCatch.guards.size() && !seenCatchAll; i++) {
          com.debughelper.tools.r8.graph.DexType guard = tryCatch.guards.get(i);
          if (seen.add(guard)) {
            guards.add(guard);
            offsets.add(labelOffsets.getInt(tryCatch.targets.get(i)));
            seenCatchAll = guard == DexItemFactory.catchAllType;
          }
        }
        if (seenCatchAll) {
          break;
        }
      }
      return new TryHandlerList(startOffset, endOffset, guards, offsets);
    }
  }

  private static class LocalVariableList {

    public static final LocalVariableList EMPTY = new LocalVariableList(0, 0, emptyMap());
    public final int startOffset;
    public final int endOffset;
    public final Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo> locals;

    private LocalVariableList(
        int startOffset, int endOffset, Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo> locals) {
      this.startOffset = startOffset;
      this.endOffset = endOffset;
      this.locals = locals;
    }

    static LocalVariableList compute(
        int instructionOffset,
        List<com.debughelper.tools.r8.graph.CfCode.LocalVariableInfo> locals,
        Reference2IntMap<com.debughelper.tools.r8.cf.code.CfLabel> labelOffsets) {
      int startOffset = Integer.MIN_VALUE;
      int endOffset = Integer.MAX_VALUE;
      Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo> currentLocals = null;
      for (com.debughelper.tools.r8.graph.CfCode.LocalVariableInfo local : locals) {
        int start = labelOffsets.getInt(local.getStart());
        int end = labelOffsets.getInt(local.getEnd());
        if (start > instructionOffset) {
          endOffset = Math.min(endOffset, start);
          continue;
        } else if (instructionOffset >= end) {
          startOffset = Math.max(startOffset, end);
          continue;
        }
        if (currentLocals == null) {
          currentLocals = new Int2ObjectOpenHashMap<>();
        }
        startOffset = Math.max(startOffset, start);
        endOffset = Math.min(endOffset, end);
        currentLocals.put(local.getIndex(), local.getLocal());
      }
      return new LocalVariableList(
          startOffset, endOffset, currentLocals == null ? emptyMap() : currentLocals);
    }

    boolean validFor(int instructionOffset) {
      return startOffset <= instructionOffset && instructionOffset < endOffset;
    }

    public com.debughelper.tools.r8.graph.DebugLocalInfo getLocal(int register) {
      return locals.get(register);
    }

    public Int2ObjectOpenHashMap<com.debughelper.tools.r8.graph.DebugLocalInfo> merge(LocalVariableList other) {
      return merge(this, other);
    }

    private static Int2ObjectOpenHashMap<com.debughelper.tools.r8.graph.DebugLocalInfo> merge(
        LocalVariableList a, LocalVariableList b) {
      if (a.locals.size() > b.locals.size()) {
        return merge(b, a);
      }
      Int2ObjectOpenHashMap<com.debughelper.tools.r8.graph.DebugLocalInfo> result = new Int2ObjectOpenHashMap<>();
      for (Entry<com.debughelper.tools.r8.graph.DebugLocalInfo> local : a.locals.int2ObjectEntrySet()) {
        if (local.getValue().equals(b.getLocal(local.getIntKey()))) {
          result.put(local.getIntKey(), local.getValue());
        }
      }
      return result;
    }
  }

  private CfState state;
  private final com.debughelper.tools.r8.graph.CfCode code;
  private final com.debughelper.tools.r8.graph.DexEncodedMethod method;
  private final com.debughelper.tools.r8.ir.code.Position callerPosition;
  private final com.debughelper.tools.r8.origin.Origin origin;

  // Synthetic position with line = 0.
  private final com.debughelper.tools.r8.ir.code.Position preamblePosition;
  private final Reference2IntMap<com.debughelper.tools.r8.cf.code.CfLabel> labelOffsets = new Reference2IntOpenHashMap<>();
  private TryHandlerList cachedTryHandlerList;
  private LocalVariableList cachedLocalVariableList;
  private int currentInstructionIndex;
  private boolean inPrelude;
  private Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo> incomingLocals;
  private Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo> outgoingLocals;
  private Int2ReferenceMap<Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo>> definitelyLiveIncomingLocals =
      new Int2ReferenceOpenHashMap<>();
  private Int2ReferenceMap<CfState.Snapshot> incomingState = new Int2ReferenceOpenHashMap<>();

  public CfSourceCode(
          com.debughelper.tools.r8.graph.CfCode code, DexEncodedMethod method, com.debughelper.tools.r8.ir.code.Position callerPosition, Origin origin) {
    this.code = code;
    this.method = method;
    this.callerPosition = callerPosition;
    this.origin = origin;
    preamblePosition = com.debughelper.tools.r8.ir.code.Position.synthetic(0, method.method, null);
    for (int i = 0; i < code.getInstructions().size(); i++) {
      com.debughelper.tools.r8.cf.code.CfInstruction instruction = code.getInstructions().get(i);
      if (instruction instanceof com.debughelper.tools.r8.cf.code.CfLabel) {
        labelOffsets.put((com.debughelper.tools.r8.cf.code.CfLabel) instruction, instructionOffset(i));
      }
    }
    this.state = new CfState(origin);
  }

  @Override
  public int instructionCount() {
    return code.getInstructions().size();
  }

  @Override
  public int instructionIndex(int instructionOffset) {
    return instructionOffset;
  }

  @Override
  public int instructionOffset(int instructionIndex) {
    return instructionIndex;
  }

  @Override
  public boolean verifyRegister(int register) {
    return true;
  }

  @Override
  public void setUp() {}

  @Override
  public void clear() {}

  @Override
  public int traceInstruction(int instructionIndex, IRBuilder builder) {
    com.debughelper.tools.r8.cf.code.CfInstruction instruction = code.getInstructions().get(instructionIndex);
    if (instruction.canThrow()) {
      TryHandlerList tryHandlers = getTryHandlers(instructionIndex);
      if (!tryHandlers.isEmpty()) {
        // Ensure the block starts at the start of the try-range (don't enqueue, not a target).
        builder.ensureBlockWithoutEnqueuing(tryHandlers.startOffset);
        IntSet seen = new IntOpenHashSet();
        for (int offset : tryHandlers.offsets) {
          if (seen.add(offset)) {
            builder.ensureExceptionalSuccessorBlock(instructionIndex, offset);
          }
        }
        if (!(instruction instanceof com.debughelper.tools.r8.cf.code.CfThrow)) {
          builder.ensureNormalSuccessorBlock(instructionIndex, instructionIndex + 1);
        }
        return instructionIndex;
      }
      // If the throwable instruction is "throw" it closes the block.
      return (instruction instanceof com.debughelper.tools.r8.cf.code.CfThrow) ? instructionIndex : -1;
    }
    if (isControlFlow(instruction)) {
      for (int target : getTargets(instructionIndex)) {
        builder.ensureNormalSuccessorBlock(instructionIndex, target);
      }
      return instructionIndex;
    }
    return -1;
  }

  private TryHandlerList getTryHandlers(int instructionOffset) {
    if (cachedTryHandlerList == null || !cachedTryHandlerList.validFor(instructionOffset)) {
      cachedTryHandlerList =
          TryHandlerList.computeTryHandlers(
              instructionOffset, code.getTryCatchRanges(), labelOffsets);
    }
    return cachedTryHandlerList;
  }

  private LocalVariableList getLocalVariables(int instructionOffset) {
    if (cachedLocalVariableList == null || !cachedLocalVariableList.validFor(instructionOffset)) {
      cachedLocalVariableList =
          LocalVariableList.compute(instructionOffset, code.getLocalVariables(), labelOffsets);
    }
    return cachedLocalVariableList;
  }

  private int[] getTargets(int instructionIndex) {
    com.debughelper.tools.r8.cf.code.CfInstruction instruction = code.getInstructions().get(instructionIndex);
    assert isControlFlow(instruction);
    com.debughelper.tools.r8.cf.code.CfLabel target = instruction.getTarget();
    if (instruction.isReturn() || instruction instanceof com.debughelper.tools.r8.cf.code.CfThrow) {
      assert target == null;
      return new int[] {};
    }
    assert instruction instanceof com.debughelper.tools.r8.cf.code.CfSwitch || target != null
        : "getTargets(): Non-control flow instruction " + instruction.getClass();
    if (instruction instanceof com.debughelper.tools.r8.cf.code.CfSwitch) {
      com.debughelper.tools.r8.cf.code.CfSwitch cfSwitch = (com.debughelper.tools.r8.cf.code.CfSwitch) instruction;
      List<com.debughelper.tools.r8.cf.code.CfLabel> targets = cfSwitch.getSwitchTargets();
      int[] res = new int[targets.size() + 1];
      for (int i = 0; i < targets.size(); i++) {
        res[i] = labelOffsets.getInt(targets.get(i));
      }
      res[targets.size()] = labelOffsets.getInt(cfSwitch.getDefaultTarget());
      return res;
    }
    int targetIndex = labelOffsets.getInt(target);
    if (instruction instanceof CfGoto) {
      return new int[] {targetIndex};
    }
    assert instruction.isConditionalJump();
    return new int[] {instructionIndex + 1, targetIndex};
  }

  @Override
  public void buildPrelude(IRBuilder builder) {
    assert !inPrelude;
    inPrelude = true;
    state.buildPrelude();
    setLocalVariableLists();
    buildArgumentInstructions(builder);
    recordStateForTarget(0, state.getSnapshot());
    // TODO: addDebugLocalUninitialized + addDebugLocalStart for non-argument locals live at 0
    // TODO(b/109789541): Generate method synchronization for DEX backend.
    inPrelude = false;
  }

  private void buildArgumentInstructions(IRBuilder builder) {
    int argumentRegister = 0;
    if (!isStatic()) {
      com.debughelper.tools.r8.graph.DexType type = method.method.holder;
      state.write(argumentRegister, type);
      builder.addThisArgument(argumentRegister++);
    }
    for (com.debughelper.tools.r8.graph.DexType type : method.method.proto.parameters.values) {
      state.write(argumentRegister, type);
      if (type.isBooleanType()) {
        builder.addBooleanNonThisArgument(argumentRegister++);
      } else {
        com.debughelper.tools.r8.ir.code.ValueType valueType = ValueType.fromDexType(type);
        builder.addNonThisArgument(argumentRegister, valueType);
        argumentRegister += valueType.requiredRegisters();
      }
    }
  }

  private boolean isStatic() {
    return method.accessFlags.isStatic();
  }

  @Override
  public void buildPostlude(IRBuilder builder) {
    // TODO(b/109789541): Generate method synchronization for DEX backend.
  }

  @Override
  public void buildInstruction(
      IRBuilder builder, int instructionIndex, boolean firstBlockInstruction) {
    com.debughelper.tools.r8.cf.code.CfInstruction instruction = code.getInstructions().get(instructionIndex);
    currentInstructionIndex = instructionIndex;
    if (firstBlockInstruction) {
      currentBlockInfo = builder.getCFG().get(instructionIndex);
      state.reset(incomingState.get(instructionIndex), instructionIndex == 0);
    }
    setLocalVariableLists();
    readEndingLocals(builder);
    if (currentBlockInfo != null && instruction.canThrow()) {
      CfState.Snapshot exceptionTransfer =
          state.getSnapshot().exceptionTransfer(builder.getFactory().throwableType);
      for (int target : currentBlockInfo.exceptionalSuccessors) {
        recordStateForTarget(target, exceptionTransfer);
      }
    }
    if (isControlFlow(instruction)) {
      ensureDebugValueLivenessControl(builder);
      instruction.buildIR(builder, state, this);
      CfState.Snapshot stateSnapshot = state.getSnapshot();
      for (int target : getTargets(instructionIndex)) {
        recordStateForTarget(target, stateSnapshot);
      }
      state.clear();
    } else {
      instruction.buildIR(builder, state, this);
      ensureDebugValueLiveness(builder);
      if (builder.getCFG().containsKey(currentInstructionIndex + 1)) {
        recordStateForTarget(currentInstructionIndex + 1, state.getSnapshot());
      }
    }
  }

  private void recordStateForTarget(int target, CfState.Snapshot snapshot) {
    CfState.Snapshot existing = incomingState.get(target);
    CfState.Snapshot merged = CfState.merge(existing, snapshot, origin);
    if (merged != existing) {
      incomingState.put(target, merged);
    }
  }

  public int getCurrentInstructionIndex() {
    return currentInstructionIndex;
  }

  public int getLabelOffset(com.debughelper.tools.r8.cf.code.CfLabel label) {
    assert labelOffsets.containsKey(label);
    return labelOffsets.getInt(label);
  }

  public void setStateFromFrame(com.debughelper.tools.r8.cf.code.CfFrame frame) {
    Int2ReferenceSortedMap<com.debughelper.tools.r8.cf.code.CfFrame.FrameType> frameLocals = frame.getLocals();
    com.debughelper.tools.r8.graph.DexType[] locals = new com.debughelper.tools.r8.graph.DexType[frameLocals.isEmpty() ? 0 : frameLocals.lastIntKey() + 1];
    com.debughelper.tools.r8.graph.DexType[] stack = new com.debughelper.tools.r8.graph.DexType[frame.getStack().size()];
    for (Int2ReferenceMap.Entry<com.debughelper.tools.r8.cf.code.CfFrame.FrameType> entry : frameLocals.int2ReferenceEntrySet()) {
      locals[entry.getIntKey()] = convertUninitialized(entry.getValue());
    }
    for (int i = 0; i < stack.length; i++) {
      stack[i] = convertUninitialized(frame.getStack().get(i));
    }
    state.setStateFromFrame(locals, stack, getDebugPositionAtOffset(currentInstructionIndex));
  }

  private DexType convertUninitialized(com.debughelper.tools.r8.cf.code.CfFrame.FrameType type) {
    if (type.isInitialized()) {
      return type.getInitializedType();
    }
    if (type.isUninitializedNew()) {
      int labelOffset = getLabelOffset(type.getUninitializedLabel());
      int insnOffset = labelOffset + 1;
      while (insnOffset < code.getInstructions().size()) {
        com.debughelper.tools.r8.cf.code.CfInstruction instruction = code.getInstructions().get(insnOffset);
        if (!(instruction instanceof com.debughelper.tools.r8.cf.code.CfLabel)
            && !(instruction instanceof com.debughelper.tools.r8.cf.code.CfFrame)
            && !(instruction instanceof com.debughelper.tools.r8.cf.code.CfPosition)) {
          assert instruction instanceof com.debughelper.tools.r8.cf.code.CfNew;
          break;
        }
        insnOffset += 1;
      }
      com.debughelper.tools.r8.cf.code.CfInstruction instruction = code.getInstructions().get(insnOffset);
      assert instruction instanceof com.debughelper.tools.r8.cf.code.CfNew;
      return ((CfNew) instruction).getType();
    }
    if (type.isUninitializedThis()) {
      return method.method.holder;
    }
    assert type.isTop();
    return null;
  }

  @Override
  public void resolveAndBuildSwitch(
      int value, int fallthroughOffset, int payloadOffset, IRBuilder builder) {}

  @Override
  public void resolveAndBuildNewArrayFilledData(
      int arrayRef, int payloadOffset, IRBuilder builder) {}

  @Override
  public com.debughelper.tools.r8.graph.DebugLocalInfo getIncomingLocal(int register) {
    return incomingLocals.get(register);
  }

  @Override
  public com.debughelper.tools.r8.graph.DebugLocalInfo getOutgoingLocal(int register) {
    if (inPrelude) {
      return getIncomingLocal(register);
    }
    assert !isControlFlow(code.getInstructions().get(currentInstructionIndex))
        : "Outgoing local is undefined for control-flow instructions";
    return outgoingLocals.get(register);
  }

  private void setLocalVariableLists() {
    incomingLocals = getLocalVariables(currentInstructionIndex).locals;
    com.debughelper.tools.r8.cf.code.CfInstruction currentInstruction = code.getInstructions().get(currentInstructionIndex);
    if (inPrelude) {
      outgoingLocals = incomingLocals;
      return;
    }
    if (currentInstruction.isReturn() || currentInstruction instanceof com.debughelper.tools.r8.cf.code.CfThrow) {
      outgoingLocals = emptyMap();
      return;
    }
    if (isControlFlow(currentInstruction)) {
      // We need to read all locals that are not live on all successors to ensure liveness.
      // Determine outgoingLocals as the intersection of all successors' locals.
      outgoingLocals = null;
      int[] targets = getTargets(currentInstructionIndex);
      for (int target : targets) {
        Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo> locals = getLocalVariables(target).locals;
        outgoingLocals = intersectMaps(outgoingLocals, locals);
      }
      assert outgoingLocals != null;
      // Pass outgoingLocals to all successors.
      for (int target : targets) {
        Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo> existing = definitelyLiveIncomingLocals.get(target);
        definitelyLiveIncomingLocals.put(target, intersectMaps(existing, outgoingLocals));
      }
    } else {
      outgoingLocals = getLocalVariables(currentInstructionIndex + 1).locals;
    }
  }

  private void readEndingLocals(IRBuilder builder) {
    if (!outgoingLocals.equals(incomingLocals)) {
      // Add reads of locals ending after the current instruction.
      for (Entry<com.debughelper.tools.r8.graph.DebugLocalInfo> entry : incomingLocals.int2ObjectEntrySet()) {
        if (!entry.getValue().equals(outgoingLocals.get(entry.getIntKey()))) {
          builder.addDebugLocalRead(entry.getIntKey(), entry.getValue());
        }
      }
    }
  }

  private Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo> intersectMaps(
          Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo> existing, Int2ObjectMap<com.debughelper.tools.r8.graph.DebugLocalInfo> update) {
    assert update != null;
    if (existing == null) {
      return update;
    }
    if (existing.size() > update.size()) {
      return intersectMaps(update, existing);
    }
    if (existing.equals(update)) {
      return existing;
    }
    Int2ObjectOpenHashMap<com.debughelper.tools.r8.graph.DebugLocalInfo> result = new Int2ObjectOpenHashMap<>();
    for (Entry<com.debughelper.tools.r8.graph.DebugLocalInfo> local : existing.int2ObjectEntrySet()) {
      if (local.getValue().equals(update.get(local.getIntKey()))) {
        result.put(local.getIntKey(), local.getValue());
      }
    }
    return result;
  }

  private void ensureDebugValueLiveness(IRBuilder builder) {
    if (incomingLocals.equals(outgoingLocals)) {
      return;
    }
    for (Entry<com.debughelper.tools.r8.graph.DebugLocalInfo> entry : incomingLocals.int2ObjectEntrySet()) {
      if (entry.getValue().equals(outgoingLocals.get(entry.getIntKey()))) {
        continue;
      }
      builder.addDebugLocalEnd(entry.getIntKey(), entry.getValue());
    }
    for (Entry<com.debughelper.tools.r8.graph.DebugLocalInfo> entry : outgoingLocals.int2ObjectEntrySet()) {
      if (entry.getValue().equals(incomingLocals.get(entry.getIntKey()))) {
        continue;
      }
      builder.addDebugLocalStart(entry.getIntKey(), entry.getValue());
    }
  }

  private void ensureDebugValueLivenessControl(IRBuilder builder) {
    if (incomingLocals.equals(outgoingLocals)) {
      return;
    }
    for (Entry<com.debughelper.tools.r8.graph.DebugLocalInfo> entry : incomingLocals.int2ObjectEntrySet()) {
      if (entry.getValue().equals(outgoingLocals.get(entry.getIntKey()))) {
        continue;
      }
      builder.addDebugLocalRead(entry.getIntKey(), entry.getValue());
    }
    assert outgoingLocals
        .int2ObjectEntrySet()
        .stream()
        .allMatch(entry -> entry.getValue().equals(incomingLocals.get(entry.getIntKey())));
  }

  private boolean isControlFlow(com.debughelper.tools.r8.cf.code.CfInstruction currentInstruction) {
    return currentInstruction.isReturn()
        || currentInstruction.getTarget() != null
        || currentInstruction instanceof CfSwitch
        || currentInstruction instanceof CfThrow;
  }

  @Override
  public com.debughelper.tools.r8.ir.code.CatchHandlers<Integer> getCurrentCatchHandlers() {
    TryHandlerList tryHandlers = getTryHandlers(instructionOffset(currentInstructionIndex));
    if (tryHandlers.isEmpty()) {
      return null;
    }
    return new CatchHandlers<>(tryHandlers.guards, tryHandlers.offsets);
  }

  @Override
  public int getMoveExceptionRegister() {
    return CfState.Slot.STACK_OFFSET;
  }

  @Override
  public boolean verifyCurrentInstructionCanThrow() {
    return code.getInstructions().get(currentInstructionIndex).canThrow();
  }

  @Override
  public boolean verifyLocalInScope(DebugLocalInfo local) {
    return false;
  }

  @Override
  public com.debughelper.tools.r8.ir.code.Position getDebugPositionAtOffset(int offset) {
    while (offset + 1 < code.getInstructions().size()) {
      CfInstruction insn = code.getInstructions().get(offset);
      if (!(insn instanceof CfLabel) && !(insn instanceof com.debughelper.tools.r8.cf.code.CfFrame)) {
        break;
      }
      offset += 1;
    }
    while (offset >= 0 && !(code.getInstructions().get(offset) instanceof com.debughelper.tools.r8.cf.code.CfPosition)) {
      offset -= 1;
    }
    if (offset < 0) {
      return com.debughelper.tools.r8.ir.code.Position.noneWithMethod(method.method, callerPosition);
    }
    return ((CfPosition) code.getInstructions().get(offset)).getPosition();
  }

  @Override
  public Position getCurrentPosition() {
    return state.getPosition();
  }
}
