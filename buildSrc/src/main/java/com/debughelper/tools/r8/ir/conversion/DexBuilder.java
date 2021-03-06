// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.conversion;

import com.debughelper.tools.r8.graph.DexCode;
import com.debughelper.tools.r8.graph.DexCode.Try;
import com.debughelper.tools.r8.graph.DexCode.TryHandler;
import com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair;
import com.debughelper.tools.r8.code.FillArrayData;
import com.debughelper.tools.r8.code.FillArrayDataPayload;
import com.debughelper.tools.r8.code.Format31t;
import com.debughelper.tools.r8.code.Goto;
import com.debughelper.tools.r8.code.Goto16;
import com.debughelper.tools.r8.code.Goto32;
import com.debughelper.tools.r8.code.IfEq;
import com.debughelper.tools.r8.code.IfEqz;
import com.debughelper.tools.r8.code.IfGe;
import com.debughelper.tools.r8.code.IfGez;
import com.debughelper.tools.r8.code.IfGt;
import com.debughelper.tools.r8.code.IfGtz;
import com.debughelper.tools.r8.code.IfLe;
import com.debughelper.tools.r8.code.IfLez;
import com.debughelper.tools.r8.code.IfLt;
import com.debughelper.tools.r8.code.IfLtz;
import com.debughelper.tools.r8.code.IfNe;
import com.debughelper.tools.r8.code.IfNez;
import com.debughelper.tools.r8.code.Instruction;
import com.debughelper.tools.r8.code.Move16;
import com.debughelper.tools.r8.code.MoveFrom16;
import com.debughelper.tools.r8.code.MoveObject;
import com.debughelper.tools.r8.code.MoveObject16;
import com.debughelper.tools.r8.code.MoveObjectFrom16;
import com.debughelper.tools.r8.code.MoveType;
import com.debughelper.tools.r8.code.MoveWide;
import com.debughelper.tools.r8.code.MoveWide16;
import com.debughelper.tools.r8.code.MoveWideFrom16;
import com.debughelper.tools.r8.code.Nop;
import com.debughelper.tools.r8.code.Throw;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.graph.DexDebugEventBuilder;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.Argument;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.CatchHandlers;
import com.debughelper.tools.r8.ir.code.DebugPosition;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.If;
import com.debughelper.tools.r8.ir.code.InstructionIterator;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Move;
import com.debughelper.tools.r8.ir.code.NewArrayFilledData;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.Return;
import com.debughelper.tools.r8.ir.code.StackValue;
import com.debughelper.tools.r8.ir.code.Switch;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.regalloc.RegisterAllocator;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMaps;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

/**
 * Builder object for constructing dex bytecode from the high-level IR.
 */
public class DexBuilder {

  // The IR representation of the code to build.
  private final com.debughelper.tools.r8.ir.code.IRCode ir;

  // The register allocator providing register assignments for the code to build.
  private final com.debughelper.tools.r8.ir.regalloc.RegisterAllocator registerAllocator;

  private final com.debughelper.tools.r8.utils.InternalOptions options;

  // List of information about switch payloads that have to be created at the end of the
  // dex code.
  private final List<SwitchPayloadInfo> switchPayloadInfos = new ArrayList<>();

  // List of generated FillArrayData dex instructions.
  private final List<FillArrayDataInfo> fillArrayDataInfos = new ArrayList<>();

  // Set of if instructions that have offsets that are so large that they cannot be encoded in
  // the if instruction format.
  private final Set<com.debughelper.tools.r8.ir.code.BasicBlock> ifsNeedingRewrite = Sets.newIdentityHashSet();

  // Running bounds on offsets.
  private int maxOffset = 0;
  private int minOffset = 0;

  // Mapping from IR instructions to info for computing the dex translation. Use the
  // getInfo/setInfo methods to access the mapping.
  private Info[] instructionToInfo;

  // The number of ingoing and outgoing argument registers for the code.
  private int inRegisterCount = 0;
  private int outRegisterCount = 0;

  // The string reference in the code with the highest index.
  private com.debughelper.tools.r8.graph.DexString highestSortingReferencedString = null;

  // Whether or not the generated code has a backwards branch.
  private boolean hasBackwardsBranch = false;

  com.debughelper.tools.r8.ir.code.BasicBlock nextBlock;

  public DexBuilder(
      com.debughelper.tools.r8.ir.code.IRCode ir,
      com.debughelper.tools.r8.ir.regalloc.RegisterAllocator registerAllocator,
      com.debughelper.tools.r8.utils.InternalOptions options) {
    assert ir != null;
    assert registerAllocator != null;
    this.ir = ir;
    this.registerAllocator = registerAllocator;
    this.options = options;
  }

  private void reset() {
    switchPayloadInfos.clear();
    fillArrayDataInfos.clear();
    ifsNeedingRewrite.clear();
    maxOffset = 0;
    minOffset = 0;
    instructionToInfo = new Info[instructionNumberToIndex(ir.numberRemainingInstructions())];
    inRegisterCount = 0;
    outRegisterCount = 0;
    highestSortingReferencedString = null;
    nextBlock = null;
  }

  /**
   * Build the dex instructions added to this builder.
   *
   * This is a two pass construction that will first compute concrete offsets and then construct
   * the concrete instructions.
   */
  public com.debughelper.tools.r8.graph.DexCode build() {
    int numberOfInstructions;
    int offset;

    do {
      // Rewrite ifs that are know from the previous iteration to have offsets that are too
      // large for the if encoding.
      rewriteIfs();

      // Reset the state of the builder to start from scratch.
      reset();

      // Remove redundant debug position instructions. They would otherwise materialize as
      // unnecessary nops.
      removeRedundantDebugPositions(ir, instructionToInfo.length);

      // Populate the builder info objects.
      numberOfInstructions = 0;

      ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> iterator = ir.listIterator();
      assert iterator.hasNext();
      com.debughelper.tools.r8.ir.code.BasicBlock block = iterator.next();
      do {
        nextBlock = iterator.hasNext() ? iterator.next() : null;
        block.buildDex(this);
        block = nextBlock;
      } while (block != null);

      // Compute offsets.
      offset = 0;
      com.debughelper.tools.r8.ir.code.InstructionIterator it = ir.instructionIterator();
      while (it.hasNext()) {
        Info info = getInfo(it.next());
        info.setOffset(offset);
        offset += info.computeSize(this);
        ++numberOfInstructions;
      }
    } while (!ifsNeedingRewrite.isEmpty());

    // Build instructions.
    com.debughelper.tools.r8.graph.DexDebugEventBuilder debugEventBuilder = new DexDebugEventBuilder(ir, options);
    List<com.debughelper.tools.r8.code.Instruction> dexInstructions = new ArrayList<>(numberOfInstructions);
    int instructionOffset = 0;
    com.debughelper.tools.r8.ir.code.InstructionIterator instructionIterator = ir.instructionIterator();
    while (instructionIterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction ir = instructionIterator.next();
      Info info = getInfo(ir);
      int previousInstructionCount = dexInstructions.size();
      info.addInstructions(this, dexInstructions);
      int instructionStartOffset = instructionOffset;
      if (previousInstructionCount < dexInstructions.size()) {
        while (previousInstructionCount < dexInstructions.size()) {
          com.debughelper.tools.r8.code.Instruction instruction = dexInstructions.get(previousInstructionCount++);
          instruction.setOffset(instructionOffset);
          instructionOffset += instruction.getSize();
        }
      }
      debugEventBuilder.add(instructionStartOffset, instructionOffset, ir);
    }

    // Workaround dalvik tracing bug, where the dalvik tracing JIT can end up tracing
    // past the end of the instruction stream if the instruction streams ends with a throw.
    // We could have also changed the block order, however, moving the throwing block higher
    // led to larger code in all experiments (multiple gmscore version and R8 run on itself).
    if (options.canHaveTracingPastInstructionsStreamBug()
        && dexInstructions.get(dexInstructions.size() - 1) instanceof Throw
        && hasBackwardsBranch) {
      com.debughelper.tools.r8.code.Nop nop = new com.debughelper.tools.r8.code.Nop();
      dexInstructions.add(nop);
      nop.setOffset(offset);
      offset += nop.getSize();
      com.debughelper.tools.r8.code.Goto go = new com.debughelper.tools.r8.code.Goto(-nop.getSize());
      dexInstructions.add(go);
      go.setOffset(offset);
      offset += go.getSize();
    }

    // Compute switch payloads.
    for (SwitchPayloadInfo switchPayloadInfo : switchPayloadInfos) {
      // Align payloads at even addresses.
      if (offset % 2 != 0) {
        com.debughelper.tools.r8.code.Nop nop = new com.debughelper.tools.r8.code.Nop();
        nop.setOffset(offset++);
        dexInstructions.add(nop);
      }
      // Create payload and add it to the instruction stream.
      com.debughelper.tools.r8.code.Nop payload = createSwitchPayload(switchPayloadInfo, offset);
      payload.setOffset(offset);
      offset += payload.getSize();
      dexInstructions.add(payload);
    }

    // Compute fill array data payloads.
    for (FillArrayDataInfo info : fillArrayDataInfos) {
      // Align payloads at even addresses.
      if (offset % 2 != 0) {
        com.debughelper.tools.r8.code.Nop nop = new com.debughelper.tools.r8.code.Nop();
        nop.setOffset(offset++);
        dexInstructions.add(nop);
      }
      // Create payload and add it to the instruction stream.
      FillArrayDataPayload payload = info.ir.createPayload();
      payload.setOffset(offset);
      info.dex.setPayloadOffset(offset - info.dex.getOffset());
      offset += payload.getSize();
      dexInstructions.add(payload);
    }

    // Construct try-catch info.
    TryInfo tryInfo = computeTryInfo();

    // Return the dex code.
    com.debughelper.tools.r8.graph.DexCode code = new com.debughelper.tools.r8.graph.DexCode(
        registerAllocator.registersUsed(),
        inRegisterCount,
        outRegisterCount,
        dexInstructions.toArray(new com.debughelper.tools.r8.code.Instruction[dexInstructions.size()]),
        tryInfo.tries,
        tryInfo.handlers,
        debugEventBuilder.build(),
        highestSortingReferencedString);

    return code;
  }

  private static boolean verifyNopHasNoPosition(
          com.debughelper.tools.r8.ir.code.Instruction instruction, ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocks) {
    com.debughelper.tools.r8.ir.code.BasicBlock nextBlock = null;
    if (blocks.hasNext()) {
      nextBlock = blocks.next();
      blocks.previous();
    }
    return verifyNopHasNoPosition(instruction, nextBlock);
  }

  private static boolean verifyNopHasNoPosition(
          com.debughelper.tools.r8.ir.code.Instruction instruction, com.debughelper.tools.r8.ir.code.BasicBlock nextBlock) {
    if (isNopInstruction(instruction, nextBlock)) {
      assert instruction.getPosition().isNone();
    }
    return true;
  }

  // Eliminates unneeded debug positions.
  //
  // After this pass all instructions that don't materialize to an actual DEX/CF instruction will
  // have Position.none(). If any other instruction has a non-none position then all other
  // instructions that do materialize to a DEX/CF instruction (eg, non-fallthrough gotos) will have
  // a non-none position.
  //
  // Remaining debug positions indicate two successive lines without intermediate instructions.
  // For these we must emit a nop instruction to ensure they don't share the same pc.
  public static void removeRedundantDebugPositions(com.debughelper.tools.r8.ir.code.IRCode code, int instructionTableSize) {
    if (!code.hasDebugPositions) {
      return;
    }
    Int2ReferenceMap[] localsMap = new Int2ReferenceMap[instructionTableSize];
    // Scan forwards removing debug positions equal to the previous instruction position.
    {
      Int2ReferenceMap<DebugLocalInfo> locals = Int2ReferenceMaps.emptyMap();
      com.debughelper.tools.r8.ir.code.Position previous = com.debughelper.tools.r8.ir.code.Position.none();
      ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blockIterator = code.listIterator();
      com.debughelper.tools.r8.ir.code.BasicBlock previousBlock = null;
      while (blockIterator.hasNext()) {
        com.debughelper.tools.r8.ir.code.BasicBlock block = blockIterator.next();
        if (previousBlock != null
            && previousBlock.exit().isGoto()
            && !isNopInstruction(previousBlock.exit(), block)) {
          assert previousBlock.exit().getPosition().isNone()
              || previousBlock.exit().getPosition().equals(previous);
          previousBlock.exit().forceSetPosition(previous);
        }
        com.debughelper.tools.r8.ir.code.InstructionListIterator instructionIterator = block.listIterator();
        if (block.getLocalsAtEntry() != null && !locals.equals(block.getLocalsAtEntry())) {
          locals = new Int2ReferenceOpenHashMap<>(block.getLocalsAtEntry());
        }
        while (instructionIterator.hasNext()) {
          com.debughelper.tools.r8.ir.code.Instruction instruction = instructionIterator.next();
          if (instruction.isDebugPosition() && previous.equals(instruction.getPosition())) {
            instructionIterator.remove();
          } else if (isNonMaterializingConstNumber(instruction)) {
            instruction.forceSetPosition(com.debughelper.tools.r8.ir.code.Position.none());
          } else if (instruction.getPosition().isSome()) {
            assert verifyNopHasNoPosition(instruction, blockIterator);
            previous = instruction.getPosition();
          }
          if (instruction.isDebugLocalsChange()) {
            locals = new Int2ReferenceOpenHashMap<>(locals);
            instruction.asDebugLocalsChange().apply(locals);
          }
          localsMap[instructionNumberToIndex(instruction.getNumber())] = locals;
        }
        previousBlock = block;
      }
      if (previousBlock != null && previousBlock.exit().isGoto()) {
        // If the last block ends in a goto it cannot be a fallthrough/nop.
        assert previousBlock.exit().getPosition().isNone();
        previousBlock.exit().forceSetPosition(previous);
      }
    }
    // Scan backwards removing debug positions equal to the following instruction position.
    {
      ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocks = code.blocks.listIterator(code.blocks.size());
      com.debughelper.tools.r8.ir.code.BasicBlock block = null;
      com.debughelper.tools.r8.ir.code.BasicBlock nextBlock;
      com.debughelper.tools.r8.ir.code.Instruction next = null;
      Int2ReferenceMap nextLocals = null;
      while (blocks.hasPrevious()) {
        nextBlock = block;
        block = blocks.previous();
        InstructionListIterator instructions = block.listIterator(block.getInstructions().size());
        while (instructions.hasPrevious()) {
          com.debughelper.tools.r8.ir.code.Instruction instruction = instructions.previous();
          int index = instructionNumberToIndex(instruction.getNumber());
          if (instruction.isDebugPosition() && localsMap[index].equals(nextLocals)) {
            com.debughelper.tools.r8.ir.code.Position nextPosition = next.getPosition();
            com.debughelper.tools.r8.ir.code.Position thisPosition = instruction.getPosition();
            if (nextPosition.isNone()) {
              next.forceSetPosition(thisPosition);
              instructions.remove();
            } else if (nextPosition.equals(thisPosition)) {
              instructions.remove();
            } else {
              next = instruction;
            }
          } else {
            assert verifyNopHasNoPosition(instruction, nextBlock);
            if (!isNopInstruction(instruction, nextBlock)) {
              next = instruction;
              nextLocals = localsMap[index];
              assert nextLocals != null;
            }
          }
        }
      }
    }
  }

  // Rewrite ifs with offsets that are too large for the if encoding. The rewriting transforms:
  //
  //
  // BB0: if condition goto BB_FAR_AWAY
  // BB1: ...
  //
  // to:
  //
  // BB0: if !condition goto BB1
  // BB2: goto BB_FAR_AWAY
  // BB1: ...
  private void rewriteIfs() {
    if (ifsNeedingRewrite.isEmpty()) {
      return;
    }
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> it = ir.blocks.listIterator();
    while (it.hasNext()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = it.next();
      if (ifsNeedingRewrite.contains(block)) {
        com.debughelper.tools.r8.ir.code.If theIf = block.exit().asIf();
        com.debughelper.tools.r8.ir.code.BasicBlock trueTarget = theIf.getTrueTarget();
        com.debughelper.tools.r8.ir.code.BasicBlock newBlock = com.debughelper.tools.r8.ir.code.BasicBlock.createGotoBlock(ir.blocks.size(), trueTarget);
        theIf.setTrueTarget(newBlock);
        theIf.invert();
        it.add(newBlock);
      }
    }
  }

  private void needsIfRewriting(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    ifsNeedingRewrite.add(block);
  }

  public void registerStringReference(DexString string) {
    if (highestSortingReferencedString == null
        || string.slowCompareTo(highestSortingReferencedString) > 0) {
      highestSortingReferencedString = string;
    }
  }

  public void requestOutgoingRegisters(int requiredRegisterCount) {
    if (requiredRegisterCount > outRegisterCount) {
      outRegisterCount = requiredRegisterCount;
    }
  }

  public int allocatedRegister(com.debughelper.tools.r8.ir.code.Value value, int instructionNumber) {
    return registerAllocator.getRegisterForValue(value, instructionNumber);
  }

  // Get the argument register for a value if it is an argument, otherwise returns the
  // allocated register at the instruction number.
  public int argumentOrAllocateRegister(Value value, int instructionNumber) {
    return registerAllocator.getArgumentOrAllocateRegisterForValue(value, instructionNumber);
  }

  public void addGoto(com.debughelper.tools.r8.ir.code.Goto jump) {
    if (jump.getTarget() != nextBlock) {
      add(jump, new GotoInfo(jump));
    } else {
      addNothing(jump);
    }
  }

  public void addIf(com.debughelper.tools.r8.ir.code.If branch) {
    assert nextBlock == branch.fallthroughBlock();
    add(branch, new IfInfo(branch));
  }

  public void addMove(com.debughelper.tools.r8.ir.code.Move move) {
    add(move, new MoveInfo(move));
  }

  public void addNothing(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    assert instruction.getPosition().isNone();
    add(instruction, new FallThroughInfo(instruction));
  }

  private static boolean isNopInstruction(
          com.debughelper.tools.r8.ir.code.Instruction instruction, com.debughelper.tools.r8.ir.code.BasicBlock nextBlock) {
    return instruction.isArgument()
        || instruction.isDebugLocalsChange()
        || isNonMaterializingConstNumber(instruction)
        || (instruction.isGoto() && instruction.asGoto().getTarget() == nextBlock);
  }

  private static boolean isNonMaterializingConstNumber(
      com.debughelper.tools.r8.ir.code.Instruction instruction) {
    return instruction.isConstNumber()
        && !(instruction.outValue() instanceof StackValue)
        && !(instruction.outValue().needsRegister());
  }

  public void addNop(com.debughelper.tools.r8.ir.code.Instruction ir) {
    add(ir, new FixedSizeInfo(ir, new com.debughelper.tools.r8.code.Nop()));
  }

  public void addDebugPosition(DebugPosition position) {
    // Remaining debug positions always require we emit an actual nop instruction.
    // See removeRedundantDebugPositions.
    addNop(position);
  }

  public void add(com.debughelper.tools.r8.ir.code.Instruction ir, com.debughelper.tools.r8.code.Instruction dex) {
    assert !ir.isGoto();
    add(ir, new FixedSizeInfo(ir, dex));
  }

  public void add(com.debughelper.tools.r8.ir.code.Instruction ir, com.debughelper.tools.r8.code.Instruction... dex) {
    assert !ir.isGoto();
    add(ir, new MultiFixedSizeInfo(ir, dex));
  }

  public void addSwitch(com.debughelper.tools.r8.ir.code.Switch s, com.debughelper.tools.r8.code.Format31t dex) {
    assert nextBlock == s.fallthroughBlock();
    switchPayloadInfos.add(new SwitchPayloadInfo(s, dex));
    add(s, dex);
  }

  public void addFillArrayData(com.debughelper.tools.r8.ir.code.NewArrayFilledData nafd, com.debughelper.tools.r8.code.FillArrayData dex) {
    fillArrayDataInfos.add(new FillArrayDataInfo(nafd, dex));
    add(nafd, dex);
  }

  public void addArgument(Argument argument) {
    inRegisterCount += argument.outValue().requiredRegisters();
    add(argument, new FallThroughInfo(argument));
  }

  public void addReturn(com.debughelper.tools.r8.ir.code.Return ret, com.debughelper.tools.r8.code.Instruction dex) {
    if (nextBlock != null
        && ret.identicalAfterRegisterAllocation(nextBlock.entry(), registerAllocator)) {
      ret.forceSetPosition(Position.none());
      addNothing(ret);
    } else {
      add(ret, dex);
    }
  }

  private void add(com.debughelper.tools.r8.ir.code.Instruction ir, Info info) {
    assert ir != null;
    assert info != null;
    assert getInfo(ir) == null;
    info.setMinOffset(minOffset);
    info.setMaxOffset(maxOffset);
    minOffset += info.minSize();
    maxOffset += info.maxSize();
    setInfo(ir, info);
  }

  public static int instructionNumberToIndex(int instructionNumber) {
    return instructionNumber / IRCode.INSTRUCTION_NUMBER_DELTA;
  }

  // Helper used by the info objects.
  private Info getInfo(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    return instructionToInfo[instructionNumberToIndex(instruction.getNumber())];
  }

  private void setInfo(com.debughelper.tools.r8.ir.code.Instruction instruction, Info info) {
    instructionToInfo[instructionNumberToIndex(instruction.getNumber())] = info;
  }

  private Info getTargetInfo(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    InstructionIterator iterator = block.iterator();
    com.debughelper.tools.r8.ir.code.Instruction instruction = null;
    while (iterator.hasNext()) {
      instruction = iterator.next();
      Info info = getInfo(instruction);
      if (!(info instanceof FallThroughInfo)) {
        return info;
      }
    }
    assert instruction != null;
    if (instruction.isReturn()) {
      assert getInfo(instruction) instanceof FallThroughInfo;
      return getTargetInfo(computeNextBlock(block));
    }
    assert instruction.isGoto();
    return getTargetInfo(instruction.asGoto().getTarget());
  }

  private com.debughelper.tools.r8.ir.code.BasicBlock computeNextBlock(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> it = ir.listIterator();
    com.debughelper.tools.r8.ir.code.BasicBlock current = it.next();
    while (current != block) {
      current = it.next();
    }
    return it.next();
  }

  // Helper for computing switch payloads.
  private com.debughelper.tools.r8.code.Nop createSwitchPayload(SwitchPayloadInfo info, int offset) {
    com.debughelper.tools.r8.ir.code.Switch ir = info.ir;
    // Patch the payload offset in the generated switch instruction now
    // that the location is known.
    info.dex.setPayloadOffset(offset - getInfo(ir).getOffset());
    // Compute target offset for each of the keys based on the offset of the
    // first instruction in the block that the switch goes to for that key.
    int[] targetBlockIndices = ir.targetBlockIndices();
    int[] targets = new int[targetBlockIndices.length];
    for (int i = 0; i < targetBlockIndices.length; i++) {
      com.debughelper.tools.r8.ir.code.BasicBlock targetBlock = ir.targetBlock(i);
      com.debughelper.tools.r8.ir.code.Instruction targetInstruction = targetBlock.entry();
      targets[i] = getInfo(targetInstruction).getOffset() - getInfo(ir).getOffset();
    }
    com.debughelper.tools.r8.ir.code.BasicBlock fallthroughBlock = ir.fallthroughBlock();
    com.debughelper.tools.r8.ir.code.Instruction fallthroughTargetInstruction =
        fallthroughBlock.entry();
    int fallthroughTarget =
        getInfo(fallthroughTargetInstruction).getOffset() - getInfo(ir).getOffset();

    return ir.buildPayload(targets, fallthroughTarget);
  }

  // Helpers for computing the try items and handlers.

  private TryInfo computeTryInfo() {
    // Canonical map of handlers.
    BiMap<com.debughelper.tools.r8.ir.code.CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock>, Integer> canonicalHandlers = HashBiMap.create();
    // Compute the list of try items and their handlers.
    List<TryItem> tryItems = computeTryItems(canonicalHandlers);
    // Compute handler sets before dex items which depend on the handler index.
    com.debughelper.tools.r8.graph.DexCode.Try[] tries = getDexTryItems(tryItems, canonicalHandlers);
    com.debughelper.tools.r8.graph.DexCode.TryHandler[] handlers = getDexTryHandlers(canonicalHandlers.inverse());
    return new TryInfo(tries, handlers);
  }

  private List<TryItem> computeTryItems(
      BiMap<com.debughelper.tools.r8.ir.code.CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock>, Integer> handlerToIndex) {
    BiMap<Integer, com.debughelper.tools.r8.ir.code.CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock>> indexToHandler = handlerToIndex.inverse();
    List<TryItem> tryItems = new ArrayList<>();
    List<com.debughelper.tools.r8.ir.code.BasicBlock> blocksWithHandlers = new ArrayList<>();
    TryItem currentTryItem = null;
    // Create try items with maximal ranges to get as much coalescing as possible. After coalescing
    // the try ranges are trimmed.
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : ir.blocks) {
      com.debughelper.tools.r8.ir.code.CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock> handlers = block.getCatchHandlers();
      // If this assert is hit, then the block contains no instruction that can throw. This is most
      // likely due to dead-code elimination or other optimizations that might now work on a refined
      // notion of what can throw. If so, the trivial blocks should either be removed or their catch
      // handlers deleted to reflect the simpler graph prior to building the dex code.
      assert handlers.isEmpty() || block.canThrow();
      if (!handlers.isEmpty()) {
        if (handlerToIndex.containsKey(handlers)) {
          handlers = indexToHandler.get(handlerToIndex.get(handlers));
        } else {
          handlerToIndex.put(handlers, handlerToIndex.size());
        }
        Info startInfo = getInfo(block.entry());
        Info endInfo = getInfo(block.exit());
        int start = startInfo.getOffset();
        int end = endInfo.getOffset() + endInfo.getSize();
        currentTryItem = new TryItem(handlers, start, end);
        tryItems.add(currentTryItem);
        blocksWithHandlers.add(block);
      } else if (currentTryItem != null && !block.canThrow()) {
        Info endInfo = getInfo(block.exit());
        // If the block only contains a goto there might not be an info for the exit instruction.
        if (endInfo != null) {
          currentTryItem.end = endInfo.getOffset() + endInfo.getSize();
        }
      } else {
        currentTryItem = null;
      }
    }

    // If there are no try items it is trivially coalesced.
    if (tryItems.isEmpty()) {
      return tryItems;
    }

    // Coalesce try blocks.
    tryItems.sort(TryItem::compareTo);
    List<TryItem> coalescedTryItems = new ArrayList<>(tryItems.size());
    TryItem item = null;
    for (int i = 0; i < tryItems.size(); ) {
      if (item != null) {
        item.end = trimEnd(blocksWithHandlers.get(i - 1));
      }
      item = tryItems.get(i);
      coalescedTryItems.add(item);
      // Trim the range start for non-throwing instructions when starting a new range.
      List<com.debughelper.tools.r8.ir.code.Instruction> instructions = blocksWithHandlers.get(i)
          .getInstructions();
      for (com.debughelper.tools.r8.ir.code.Instruction insn : instructions) {
        if (insn.instructionTypeCanThrow()) {
          item.start = getInfo(insn).getOffset();
          break;
        }
      }
      // Append all consecutive ranges that define the same handlers.
      ++i;
      while (i < tryItems.size()) {
        TryItem next = tryItems.get(i);
        if (item.end != next.start || !item.handlers.equals(next.handlers)) {
          break;
        }
        item.end = next.end;
        ++i;
      }
    }
    // Trim the last try range.
    int lastIndex = tryItems.size() - 1;
    item.end = trimEnd(blocksWithHandlers.get(lastIndex));
    return coalescedTryItems;
  }

  private int trimEnd(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    // Trim the range end for non-throwing instructions when end has been computed.
    List<com.debughelper.tools.r8.ir.code.Instruction> instructions = block.getInstructions();
    for (com.debughelper.tools.r8.ir.code.Instruction insn : Lists.reverse(instructions)) {
      if (insn.instructionTypeCanThrow()) {
        Info info = getInfo(insn);
        return info.getOffset() + info.getSize();
      }
    }
    throw new com.debughelper.tools.r8.errors.Unreachable("Expected to find a possibly throwing instruction");
  }

  private static com.debughelper.tools.r8.graph.DexCode.Try[] getDexTryItems(List<TryItem> tryItems,
                                                                             Map<com.debughelper.tools.r8.ir.code.CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock>, Integer> catchHandlers) {
    com.debughelper.tools.r8.graph.DexCode.Try[] tries = new com.debughelper.tools.r8.graph.DexCode.Try[tryItems.size()];
    for (int i = 0; i < tries.length; ++i) {
      TryItem item = tryItems.get(i);
      com.debughelper.tools.r8.graph.DexCode.Try dexTry = new com.debughelper.tools.r8.graph.DexCode.Try(item.start, item.end - item.start, -1);
      dexTry.handlerIndex = catchHandlers.get(item.handlers);
      tries[i] = dexTry;
    }
    return tries;
  }

  private com.debughelper.tools.r8.graph.DexCode.TryHandler[] getDexTryHandlers(Map<Integer, com.debughelper.tools.r8.ir.code.CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock>> catchHandlers) {
    com.debughelper.tools.r8.graph.DexCode.TryHandler[] handlers = new com.debughelper.tools.r8.graph.DexCode.TryHandler[catchHandlers.size()];
    for (int j = 0; j < catchHandlers.size(); j++) {
      com.debughelper.tools.r8.ir.code.CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock> handlerGroup = catchHandlers.get(j);
      int catchAllOffset = com.debughelper.tools.r8.graph.DexCode.TryHandler.NO_HANDLER;
      List<com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair> pairs = new ArrayList<>();
      for (int i = 0; i < handlerGroup.getGuards().size(); i++) {
        DexType type = handlerGroup.getGuards().get(i);
        com.debughelper.tools.r8.ir.code.BasicBlock target = handlerGroup.getAllTargets().get(i);
        int targetOffset = getInfo(target.entry()).getOffset();
        if (type == DexItemFactory.catchAllType) {
          assert i == handlerGroup.getGuards().size() - 1;
          catchAllOffset = targetOffset;
        } else {
          pairs.add(new com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair(type, targetOffset));
        }
      }
      com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair[] pairsArray = pairs.toArray(new com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair[pairs.size()]);
      handlers[j] = new com.debughelper.tools.r8.graph.DexCode.TryHandler(pairsArray, catchAllOffset);
    }
    return handlers;
  }

  public InternalOptions getOptions() {
    return options;
  }

  public RegisterAllocator getRegisterAllocator() {
    return registerAllocator;
  }

  // Dex instruction wrapper with information to compute instruction sizes and offsets for jumps.
  private static abstract class Info {

    private final com.debughelper.tools.r8.ir.code.Instruction ir;
    // Concrete final offset of the instruction.
    private int offset = -1;
    // Lower and upper bound of the final offset.
    private int minOffset = -1;
    private int maxOffset = -1;

    public Info(com.debughelper.tools.r8.ir.code.Instruction ir) {
      assert ir != null;
      this.ir = ir;
    }

    // Computes the final size of the instruction.
    // All instruction offsets up-to and including this instruction will be defined at this point.
    public abstract int computeSize(DexBuilder builder);

    // Materialize the actual construction.
    // All instruction offsets are known at this point.
    public abstract void addInstructions(DexBuilder builder, List<com.debughelper.tools.r8.code.Instruction> instructions);

    // Lower bound on the size of the instruction.
    public abstract int minSize();

    // Upper bound on the size of the instruction.
    public abstract int maxSize();

    public abstract int getSize();

    public int getOffset() {
      assert offset >= 0 : this;
      return offset;
    }

    public void setOffset(int offset) {
      assert offset >= 0;
      this.offset = offset;
    }

    public int getMinOffset() {
      assert minOffset >= 0;
      return minOffset;
    }

    public void setMinOffset(int minOffset) {
      assert minOffset >= 0;
      this.minOffset = minOffset;
    }

    public int getMaxOffset() {
      assert maxOffset >= 0;
      return maxOffset;
    }

    public void setMaxOffset(int maxOffset) {
      assert maxOffset >= 0;
      this.maxOffset = maxOffset;
    }

    public com.debughelper.tools.r8.ir.code.Instruction getIR() {
      return ir;
    }
  }

  private static class FixedSizeInfo extends Info {

    private final com.debughelper.tools.r8.code.Instruction instruction;

    public FixedSizeInfo(com.debughelper.tools.r8.ir.code.Instruction ir, com.debughelper.tools.r8.code.Instruction instruction) {
      super(ir);
      this.instruction = instruction;
    }

    @Override
    public int getSize() {
      return instruction.getSize();
    }

    @Override
    public int minSize() {
      return instruction.getSize();
    }

    @Override
    public int maxSize() {
      return instruction.getSize();
    }

    @Override
    public int computeSize(DexBuilder builder) {
      instruction.setOffset(getOffset()); // for better printing of the dex code.
      return instruction.getSize();
    }

    @Override
    public void addInstructions(DexBuilder builder, List<com.debughelper.tools.r8.code.Instruction> instructions) {
      instructions.add(instruction);
    }
  }

  private static class MultiFixedSizeInfo extends Info {

    private final com.debughelper.tools.r8.code.Instruction[] instructions;
    private final int size;

    public MultiFixedSizeInfo(com.debughelper.tools.r8.ir.code.Instruction ir,
                              com.debughelper.tools.r8.code.Instruction[] instructions) {
      super(ir);
      this.instructions = instructions;
      int size = 0;
      for (com.debughelper.tools.r8.code.Instruction instruction : instructions) {
        size += instruction.getSize();
      }
      this.size = size;
    }

    @Override
    public int computeSize(DexBuilder builder) {
      return size;
    }

    @Override
    public void addInstructions(DexBuilder builder, List<com.debughelper.tools.r8.code.Instruction> instructions) {
      int offset = getOffset();
      for (com.debughelper.tools.r8.code.Instruction instruction : this.instructions) {
        instructions.add(instruction);
        instruction.setOffset(offset);
        offset += instruction.getSize();
      }
    }

    @Override
    public int minSize() {
      return size;
    }

    @Override
    public int maxSize() {
      return size;
    }

    @Override
    public int getSize() {
      return size;
    }
  }

  private static class FallThroughInfo extends Info {

    public FallThroughInfo(com.debughelper.tools.r8.ir.code.Instruction ir) {
      super(ir);
    }

    @Override
    public int getSize() {
      return 0;
    }

    @Override
    public int computeSize(DexBuilder builder) {
      return 0;
    }

    @Override
    public void addInstructions(DexBuilder builder, List<com.debughelper.tools.r8.code.Instruction> instructions) {
    }

    @Override
    public int minSize() {
      return 0;
    }

    @Override
    public int maxSize() {
      return 0;
    }
  }

  private static class GotoInfo extends Info {

    private int size = -1;

    public GotoInfo(com.debughelper.tools.r8.ir.code.Goto jump) {
      super(jump);
    }

    private com.debughelper.tools.r8.ir.code.Goto getJump() {
      return (com.debughelper.tools.r8.ir.code.Goto) getIR();
    }

    @Override
    public int getSize() {
      assert size > 0;
      return size;
    }

    @Override
    public int minSize() {
      assert new com.debughelper.tools.r8.code.Goto(42).getSize() == 1;
      return 1;
    }

    @Override
    public int maxSize() {
      assert new com.debughelper.tools.r8.code.Goto32(0).getSize() == 3;
      return 3;
    }

    @Override
    public int computeSize(DexBuilder builder) {
      assert size < 0;
      com.debughelper.tools.r8.ir.code.Goto jump = getJump();
      Info targetInfo = builder.getTargetInfo(jump.getTarget());
      // Trivial loop will be emitted as: nop & goto -1
      if (jump == targetInfo.getIR()) {
        size = 2;
        return size;
      }
      int maxOffset = getMaxOffset();
      int maxTargetOffset = targetInfo.getMaxOffset();
      int delta;
      if (maxTargetOffset < maxOffset) {
        // Backward branch: compute exact size (the target offset is set).
        delta = getOffset() - targetInfo.getOffset();
      } else {
        // Forward branch: over estimate the distance, but take into account the sizes
        // of instructions generated so far. That way the over estimation is only for the
        // instructions between this one and the target.
        int maxOverEstimation = maxOffset - getOffset();
        delta = (maxTargetOffset - maxOverEstimation) - getOffset();
      }
      if (delta <= Byte.MAX_VALUE) {
        size = 1;
      } else if (delta <= Short.MAX_VALUE) {
        size = 2;
      } else {
        size = 3;
      }
      if (targetInfo.getIR().isReturn() && targetInfo.getIR().getPosition().isNone()) {
        // Set the size to the min of the size of the return and the size of the goto. When
        // adding instructions, we use the return if the computed size matches the size of the
        // return.
        assert !(targetInfo instanceof FallThroughInfo);
        size = Math.min(targetInfo.getSize(), size);
      }
      assert size != 0;
      return size;
    }

    @Override
    public void addInstructions(DexBuilder builder, List<com.debughelper.tools.r8.code.Instruction> instructions) {
      com.debughelper.tools.r8.ir.code.Goto jump = getJump();
      int source = builder.getInfo(jump).getOffset();
      Info targetInfo = builder.getTargetInfo(jump.getTarget());
      int relativeOffset = targetInfo.getOffset() - source;
      if (relativeOffset < 0) {
        builder.hasBackwardsBranch = true;
      }
      // Emit a return if the target is a return and the size of the return is the computed
      // size of this instruction.
      Return ret = targetInfo.getIR().asReturn();
      if (ret != null && size == targetInfo.getSize() && ret.getPosition().isNone()) {
        com.debughelper.tools.r8.code.Instruction dex = ret.createDexInstruction(builder);
        dex.setOffset(getOffset()); // for better printing of the dex code.
        instructions.add(dex);
      } else if (size == relativeOffset) {
        // We should never generate a goto targeting the next instruction. However, if we do
        // we replace it with nops. This works around a dalvik bug where the dalvik tracing
        // jit crashes on 'goto next instruction' on debughelper 4.1.1.
        // TODO(b/34726595): We currently do hit this case and we should see if we can avoid that.
        for (int i = 0; i < size; i++) {
          com.debughelper.tools.r8.code.Instruction dex = new com.debughelper.tools.r8.code.Nop();
          assert dex.getSize() == 1;
          dex.setOffset(getOffset() + i); // for better printing of the dex code.
          instructions.add(dex);
        }
      } else {
        com.debughelper.tools.r8.code.Instruction dex;
        switch (size) {
          case 1:
            assert relativeOffset != 0;
            dex = new com.debughelper.tools.r8.code.Goto(relativeOffset);
            break;
          case 2:
            if (relativeOffset == 0) {
              com.debughelper.tools.r8.code.Nop nop = new com.debughelper.tools.r8.code.Nop();
              instructions.add(nop);
              dex = new Goto(-nop.getSize());
            } else {
              dex = new Goto16(relativeOffset);
            }
            break;
          case 3:
            dex = new Goto32(relativeOffset);
            break;
          default:
            throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected size for goto instruction: " + size);
        }
        dex.setOffset(getOffset()); // for better printing of the dex code.
        instructions.add(dex);
      }
    }
  }

  public static class IfInfo extends Info {

    private int size = -1;

    public IfInfo(com.debughelper.tools.r8.ir.code.If branch) {
      super(branch);
    }

    private com.debughelper.tools.r8.ir.code.If getBranch() {
      return (com.debughelper.tools.r8.ir.code.If) getIR();
    }

    private boolean branchesToSelf(DexBuilder builder) {
      com.debughelper.tools.r8.ir.code.If branch = getBranch();
      Info trueTargetInfo = builder.getTargetInfo(branch.getTrueTarget());
      return branch == trueTargetInfo.getIR();
    }

    private boolean offsetOutOfRange(DexBuilder builder) {
      Info targetInfo = builder.getTargetInfo(getBranch().getTrueTarget());
      int maxOffset = getMaxOffset();
      int maxTargetOffset = targetInfo.getMaxOffset();
      if (maxTargetOffset < maxOffset) {
        return getOffset() - targetInfo.getOffset() < Short.MIN_VALUE;
      }
      // Forward branch: over estimate the distance, but take into account the sizes
      // of instructions generated so far. That way the over estimation is only for the
      // instructions between this one and the target.
      int maxOverEstimation = maxOffset - getOffset();
      return (maxTargetOffset - maxOverEstimation) - getOffset() > Short.MAX_VALUE;
    }

    @Override
    public void addInstructions(DexBuilder builder, List<com.debughelper.tools.r8.code.Instruction> instructions) {
      If branch = getBranch();
      int source = builder.getInfo(branch).getOffset();
      int target = builder.getInfo(branch.getTrueTarget().entry()).getOffset();
      int relativeOffset = target - source;
      int register1 = builder.allocatedRegister(branch.inValues().get(0), branch.getNumber());

      if (relativeOffset < 0) {
        builder.hasBackwardsBranch = true;
      }

      if (size == 3) {
        assert branchesToSelf(builder);
        com.debughelper.tools.r8.code.Nop nop = new com.debughelper.tools.r8.code.Nop();
        relativeOffset -= nop.getSize();
        instructions.add(nop);
      }
      assert relativeOffset != 0;
      com.debughelper.tools.r8.code.Instruction instruction = null;
      if (branch.isZeroTest()) {
        switch (getBranch().getType()) {
          case EQ:
            instruction = new IfEqz(register1, relativeOffset);
            break;
          case GE:
            instruction = new IfGez(register1, relativeOffset);
            break;
          case GT:
            instruction = new IfGtz(register1, relativeOffset);
            break;
          case LE:
            instruction = new IfLez(register1, relativeOffset);
            break;
          case LT:
            instruction = new IfLtz(register1, relativeOffset);
            break;
          case NE:
            instruction = new IfNez(register1, relativeOffset);
            break;
        }
      } else {
        int register2 = builder.allocatedRegister(branch.inValues().get(1), branch.getNumber());
        switch (getBranch().getType()) {
          case EQ:
            instruction = new IfEq(register1, register2, relativeOffset);
            break;
          case GE:
            instruction = new IfGe(register1, register2, relativeOffset);
            break;
          case GT:
            instruction = new IfGt(register1, register2, relativeOffset);
            break;
          case LE:
            instruction = new IfLe(register1, register2, relativeOffset);
            break;
          case LT:
            instruction = new IfLt(register1, register2, relativeOffset);
            break;
          case NE:
            instruction = new IfNe(register1, register2, relativeOffset);
            break;
        }
      }
      instruction.setOffset(getOffset());
      instructions.add(instruction);
    }

    @Override
    public int computeSize(DexBuilder builder) {
      if (offsetOutOfRange(builder)) {
        builder.needsIfRewriting(getBranch().getBlock());
      }
      size = branchesToSelf(builder) ? 3 : 2;
      return size;
    }

    @Override
    public int minSize() {
      return 2;
    }

    @Override
    public int maxSize() {
      return 3;
    }

    @Override
    public int getSize() {
      return size;
    }
  }

  public static class MoveInfo extends Info {

    private int size = -1;

    public MoveInfo(com.debughelper.tools.r8.ir.code.Move move) {
      super(move);
    }

    private com.debughelper.tools.r8.ir.code.Move getMove() {
      return (com.debughelper.tools.r8.ir.code.Move) getIR();
    }

    @Override
    public int computeSize(DexBuilder builder) {
      com.debughelper.tools.r8.ir.code.Move move = getMove();
      int srcRegister = builder.argumentOrAllocateRegister(move.src(), move.getNumber());
      int destRegister = builder.allocatedRegister(move.dest(), move.getNumber());
      if (srcRegister == destRegister) {
        size = 1;
      } else if (srcRegister <= com.debughelper.tools.r8.dex.Constants.U4BIT_MAX && destRegister <= com.debughelper.tools.r8.dex.Constants.U4BIT_MAX) {
        size = 1;
      } else if (destRegister <= Constants.U8BIT_MAX) {
        size = 2;
      } else {
        size = 3;
      }
      return size;
    }

    @Override
    public void addInstructions(DexBuilder builder, List<com.debughelper.tools.r8.code.Instruction> instructions) {
      Move move = getMove();
      com.debughelper.tools.r8.code.MoveType moveType = MoveType.fromValueType(move.outType());
      int src = builder.argumentOrAllocateRegister(move.src(), move.getNumber());
      int dest = builder.allocatedRegister(move.dest(), move.getNumber());
      Instruction instruction = null;
      switch (size) {
        case 1:
          if (src == dest) {
            instruction = new com.debughelper.tools.r8.code.Nop();
            break;
          }
          switch (moveType) {
            case SINGLE:
              instruction = new com.debughelper.tools.r8.code.Move(dest, src);
              break;
            case WIDE:
              instruction = new MoveWide(dest, src);
              break;
            case OBJECT:
              instruction = new MoveObject(dest, src);
              break;
            default:
              throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected type: " + move.outType());
          }
          break;
        case 2:
          switch (moveType) {
            case SINGLE:
              instruction = new MoveFrom16(dest, src);
              break;
            case WIDE:
              instruction = new MoveWideFrom16(dest, src);
              break;
            case OBJECT:
              instruction = new MoveObjectFrom16(dest, src);
              break;
            default:
              throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected type: " + move.outType());
          }
          break;
        case 3:
          switch (moveType) {
            case SINGLE:
              instruction = new com.debughelper.tools.r8.code.Move16(dest, src);
              break;
            case WIDE:
              instruction = new MoveWide16(dest, src);
              break;
            case OBJECT:
              instruction = new MoveObject16(dest, src);
              break;
            default:
              throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected type: " + move.outType());
          }
          break;
        default:
          throw new Unreachable("Unexpected size: " + size);
      }
      instruction.setOffset(getOffset());
      instructions.add(instruction);
    }

    @Override
    public int minSize() {
      assert new Nop().getSize() == 1 && new com.debughelper.tools.r8.code.Move(0, 0).getSize() == 1;
      return 1;
    }

    @Override
    public int maxSize() {
      assert new Move16(0, 0).getSize() == 3;
      return 3;
    }

    @Override
    public int getSize() {
      assert size > 0;
      return size;
    }
  }

  // Return-type wrapper for try-related data.
  private static class TryInfo {

    public final com.debughelper.tools.r8.graph.DexCode.Try[] tries;
    public final com.debughelper.tools.r8.graph.DexCode.TryHandler[] handlers;

    public TryInfo(com.debughelper.tools.r8.graph.DexCode.Try[] tries, com.debughelper.tools.r8.graph.DexCode.TryHandler[] handlers) {
      this.tries = tries;
      this.handlers = handlers;
    }
  }

  // Helper class for coalescing ranges for try blocks.
  private static class TryItem implements Comparable<TryItem> {

    public final com.debughelper.tools.r8.ir.code.CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock> handlers;
    public int start;
    public int end;

    public TryItem(CatchHandlers<BasicBlock> handlers, int start, int end) {
      this.handlers = handlers;
      this.start = start;
      this.end = end;
    }

    @Override
    public int compareTo(TryItem other) {
      return Integer.compare(start, other.start);
    }
  }

  private static class SwitchPayloadInfo {

    public final com.debughelper.tools.r8.ir.code.Switch ir;
    public final com.debughelper.tools.r8.code.Format31t dex;

    public SwitchPayloadInfo(Switch ir, Format31t dex) {
      this.ir = ir;
      this.dex = dex;
    }
  }

  private static class FillArrayDataInfo {

    public final com.debughelper.tools.r8.ir.code.NewArrayFilledData ir;
    public final com.debughelper.tools.r8.code.FillArrayData dex;

    public FillArrayDataInfo(NewArrayFilledData ir, FillArrayData dex) {
      this.ir = ir;
      this.dex = dex;
    }
  }
}
