// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.conversion;

import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.BasicBlock.EdgeType;
import com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo;
import com.debughelper.tools.r8.ir.code.Cmp;
import com.debughelper.tools.r8.ir.code.Cmp.Bias;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.code.Invoke.Type;
import com.debughelper.tools.r8.ir.conversion.SourceCode;
import com.debughelper.tools.r8.ir.conversion.TypeConstraintResolver;
import com.debughelper.tools.r8.ApiLevelException;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.errors.InternalCompilerError;
import com.debughelper.tools.r8.errors.InvalidDebugInfoException;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.graph.DexCallSite;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexMethodHandle;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.Add;
import com.debughelper.tools.r8.ir.code.And;
import com.debughelper.tools.r8.ir.code.Argument;
import com.debughelper.tools.r8.ir.code.ArrayGet;
import com.debughelper.tools.r8.ir.code.ArrayLength;
import com.debughelper.tools.r8.ir.code.ArrayPut;
import com.debughelper.tools.r8.ir.code.CatchHandlers;
import com.debughelper.tools.r8.ir.code.CheckCast;
import com.debughelper.tools.r8.ir.code.ConstClass;
import com.debughelper.tools.r8.ir.code.ConstMethodHandle;
import com.debughelper.tools.r8.ir.code.ConstMethodType;
import com.debughelper.tools.r8.ir.code.ConstNumber;
import com.debughelper.tools.r8.ir.code.ConstString;
import com.debughelper.tools.r8.ir.code.DebugLocalRead;
import com.debughelper.tools.r8.ir.code.DebugLocalUninitialized;
import com.debughelper.tools.r8.ir.code.DebugLocalWrite;
import com.debughelper.tools.r8.ir.code.DebugPosition;
import com.debughelper.tools.r8.ir.code.Div;
import com.debughelper.tools.r8.ir.code.Goto;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.If;
import com.debughelper.tools.r8.ir.code.InstanceGet;
import com.debughelper.tools.r8.ir.code.InstanceOf;
import com.debughelper.tools.r8.ir.code.InstancePut;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.InvokeCustom;
import com.debughelper.tools.r8.ir.code.MemberType;
import com.debughelper.tools.r8.ir.code.Monitor;
import com.debughelper.tools.r8.ir.code.MoveException;
import com.debughelper.tools.r8.ir.code.Mul;
import com.debughelper.tools.r8.ir.code.Neg;
import com.debughelper.tools.r8.ir.code.NewArrayEmpty;
import com.debughelper.tools.r8.ir.code.NewArrayFilledData;
import com.debughelper.tools.r8.ir.code.NewInstance;
import com.debughelper.tools.r8.ir.code.Not;
import com.debughelper.tools.r8.ir.code.NumberConversion;
import com.debughelper.tools.r8.ir.code.NumericType;
import com.debughelper.tools.r8.ir.code.Or;
import com.debughelper.tools.r8.ir.code.Phi;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.Rem;
import com.debughelper.tools.r8.ir.code.Return;
import com.debughelper.tools.r8.ir.code.Shl;
import com.debughelper.tools.r8.ir.code.Shr;
import com.debughelper.tools.r8.ir.code.StaticGet;
import com.debughelper.tools.r8.ir.code.StaticPut;
import com.debughelper.tools.r8.ir.code.Sub;
import com.debughelper.tools.r8.ir.code.Switch;
import com.debughelper.tools.r8.ir.code.Throw;
import com.debughelper.tools.r8.ir.code.Ushr;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueNumberGenerator;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.code.Xor;
import com.debughelper.tools.r8.utils.AndroidApiLevel;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.Pair;

import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Builder object for constructing high-level IR from dex bytecode.
 *
 * <p>The generated IR is in SSA form. The SSA construction is based on the paper
 * "Simple and Efficient Construction of Static Single Assignment Form" available at
 * http://compilers.cs.uni-saarland.de/papers/bbhlmz13cc.pdf
 */
public class IRBuilder {

  public static final int INITIAL_BLOCK_OFFSET = -1;

  public DexItemFactory getFactory() {
    return options.itemFactory;
  }

  // SSA construction uses a worklist of basic blocks reachable from the entry and their
  // instruction offsets.
  private static class WorklistItem {

    private final com.debughelper.tools.r8.ir.code.BasicBlock block;
    private final int firstInstructionIndex;

    private WorklistItem(com.debughelper.tools.r8.ir.code.BasicBlock block, int firstInstructionIndex) {
      assert block != null;
      this.block = block;
      this.firstInstructionIndex = firstInstructionIndex;
    }
  }

  private static class MoveExceptionWorklistItem extends WorklistItem {
    private final int targetOffset;

    private MoveExceptionWorklistItem(com.debughelper.tools.r8.ir.code.BasicBlock block, int targetOffset) {
      super(block, -1);
      this.targetOffset = targetOffset;
    }
  }

  /**
   * Representation of lists of values that can be used as keys in maps. A list of
   * values is equal to another list of values if it contains exactly the same values
   * in the same order.
   */
  private static class ValueList {

    private final List<com.debughelper.tools.r8.ir.code.Value> values = new ArrayList<>();

    /**
     * Creates a ValueList of all the operands at the given index in the list of phis.
     */
    public static ValueList fromPhis(List<com.debughelper.tools.r8.ir.code.Phi> phis, int index) {
      ValueList result = new ValueList();
      for (com.debughelper.tools.r8.ir.code.Phi phi : phis) {
        result.values.add(phi.getOperand(index));
      }
      return result;
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof ValueList)) {
        return false;
      }
      ValueList o = (ValueList) other;
      if (o.values.size() != values.size()) {
        return false;
      }
      for (int i = 0; i < values.size(); i++) {
        if (values.get(i) != o.values.get(i)) {
          return false;
        }
      }
      return true;
    }
  }

  public static class BlockInfo {
    com.debughelper.tools.r8.ir.code.BasicBlock block = new com.debughelper.tools.r8.ir.code.BasicBlock();
    IntSet normalPredecessors = new IntArraySet();
    IntSet normalSuccessors = new IntArraySet();
    IntSet exceptionalPredecessors = new IntArraySet();
    IntSet exceptionalSuccessors = new IntArraySet();

    void addNormalPredecessor(int offset) {
      normalPredecessors.add(offset);
    }

    void addNormalSuccessor(int offset) {
      normalSuccessors.add(offset);
    }

    void replaceNormalPredecessor(int existing, int replacement) {
      normalPredecessors.remove(existing);
      normalPredecessors.add(replacement);
    }

    void addExceptionalPredecessor(int offset) {
      exceptionalPredecessors.add(offset);
    }

    void addExceptionalSuccessor(int offset) {
      exceptionalSuccessors.add(offset);
    }

    int predecessorCount() {
      return normalPredecessors.size() + exceptionalPredecessors.size();
    }

    IntSet allSuccessors() {
      IntSet all = new IntArraySet(normalSuccessors.size() + exceptionalSuccessors.size());
      all.addAll(normalSuccessors);
      all.addAll(exceptionalSuccessors);
      return all;
    }

    BlockInfo split(
        int blockStartOffset, int fallthroughOffset, Int2ReferenceMap<BlockInfo> targets) {
      BlockInfo fallthroughInfo = new BlockInfo();
      fallthroughInfo.normalPredecessors = new IntArraySet(Collections.singleton(blockStartOffset));
      fallthroughInfo.block.incrementUnfilledPredecessorCount();
      // Move all normal successors to the fallthrough block.
      IntIterator normalSuccessorIterator = normalSuccessors.iterator();
      while (normalSuccessorIterator.hasNext()) {
        BlockInfo normalSuccessor = targets.get(normalSuccessorIterator.nextInt());
        normalSuccessor.replaceNormalPredecessor(blockStartOffset, fallthroughOffset);
      }
      fallthroughInfo.normalSuccessors = normalSuccessors;
      normalSuccessors = new IntArraySet(Collections.singleton(fallthroughOffset));
      // Copy all exceptional successors to the fallthrough block.
      IntIterator exceptionalSuccessorIterator = fallthroughInfo.exceptionalSuccessors.iterator();
      while (exceptionalSuccessorIterator.hasNext()) {
        BlockInfo exceptionalSuccessor = targets.get(exceptionalSuccessorIterator.nextInt());
        exceptionalSuccessor.addExceptionalPredecessor(fallthroughOffset);
      }
      fallthroughInfo.exceptionalSuccessors = new IntArraySet(this.exceptionalSuccessors);
      return fallthroughInfo;
    }

    @Override
    public String toString() {
      StringBuilder stringBuilder =
          new StringBuilder()
              .append("block ")
              .append(block.getNumberAsString())
              .append(" predecessors: ");
      String sep = "";
      for (int offset : normalPredecessors) {
        stringBuilder.append(sep).append(offset);
        sep = ", ";
      }
      for (int offset : exceptionalPredecessors) {
        stringBuilder.append(sep).append('*').append(offset);
        sep = ", ";
      }
      stringBuilder.append(" successors: ");
      sep = "";
      for (int offset : normalSuccessors) {
        stringBuilder.append(sep).append(offset);
        sep = ", ";
      }
      for (int offset : exceptionalSuccessors) {
        stringBuilder.append(sep).append('*').append(offset);
        sep = ", ";
      }
      return stringBuilder.toString();
    }
  }

  // Mapping from instruction offsets to basic-block targets.
  private final Int2ReferenceSortedMap<BlockInfo> targets = new Int2ReferenceAVLTreeMap<>();

  // Worklist of reachable blocks.
  private final Queue<Integer> traceBlocksWorklist = new LinkedList<>();

  // Bitmap to ensure we don't process an instruction more than once.
  private boolean[] processedInstructions = null;

  // Bitmap of processed subroutine instructions. Lazily allocated off the fast-path.
  private Set<Integer> processedSubroutineInstructions = null;

  // Worklist for SSA construction.
  private final Queue<WorklistItem> ssaWorklist = new LinkedList<>();

  // Basic blocks. Added after processing from the worklist.
  private final LinkedList<com.debughelper.tools.r8.ir.code.BasicBlock> blocks = new LinkedList<>();

  private com.debughelper.tools.r8.ir.code.BasicBlock currentBlock = null;
  private final List<com.debughelper.tools.r8.ir.code.BasicBlock.Pair> needGotoToCatchBlocks = new ArrayList<>();
  final private com.debughelper.tools.r8.ir.code.ValueNumberGenerator valueNumberGenerator;
  private final com.debughelper.tools.r8.graph.DexEncodedMethod method;
  private final com.debughelper.tools.r8.graph.AppInfo appInfo;

  // Source code to build IR from. Null if already built.
  private com.debughelper.tools.r8.ir.conversion.SourceCode source;

  private boolean throwingInstructionInCurrentBlock = false;
  private final com.debughelper.tools.r8.utils.InternalOptions options;

  // Pending local reads.
  private com.debughelper.tools.r8.ir.code.Value previousLocalValue = null;
  private final List<com.debughelper.tools.r8.ir.code.Value> debugLocalReads = new ArrayList<>();

  private int nextBlockNumber = 0;

  // Flag indicating if the instructions define values with imprecise types.
  private boolean hasImpreciseInstructionOutValueTypes = false;

  public IRBuilder(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.graph.AppInfo appInfo,
                   com.debughelper.tools.r8.ir.conversion.SourceCode source, com.debughelper.tools.r8.utils.InternalOptions options) {
    this(method, appInfo, source, options, new com.debughelper.tools.r8.ir.code.ValueNumberGenerator());
  }

  public IRBuilder(
          com.debughelper.tools.r8.graph.DexEncodedMethod method, AppInfo appInfo, SourceCode source,
          InternalOptions options, ValueNumberGenerator valueNumberGenerator) {
    assert source != null;
    this.method = method;
    this.appInfo = appInfo;
    this.source = source;
    this.valueNumberGenerator = valueNumberGenerator;
    this.options = options;
  }

  public boolean isGeneratingClassFiles() {
    return options.isGeneratingClassFiles();
  }

  public Int2ReferenceSortedMap<BlockInfo> getCFG() {
    return targets;
  }

  public com.debughelper.tools.r8.graph.DexMethod getMethod() {
    return method.method;
  }

  private void addToWorklist(com.debughelper.tools.r8.ir.code.BasicBlock block, int firstInstructionIndex) {
    // TODO(ager): Filter out the ones that are already in the worklist, mark bit in block?
    if (!block.isFilled()) {
      ssaWorklist.add(new WorklistItem(block, firstInstructionIndex));
    }
  }

  private void setCurrentBlock(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    currentBlock = block;
  }

  /**
   * Build the high-level IR in SSA form.
   *
   * @return The list of basic blocks. First block is the main entry.
   */
  public com.debughelper.tools.r8.ir.code.IRCode build() {
    assert source != null;
    source.setUp();

    // Create entry block (at a non-targetable address).
    targets.put(INITIAL_BLOCK_OFFSET, new BlockInfo());

    // Process reachable code paths starting from instruction 0.
    int instCount = source.instructionCount();
    processedInstructions = new boolean[instCount];
    traceBlocksWorklist.add(0);
    while (!traceBlocksWorklist.isEmpty()) {
      int startOfBlockOffset = traceBlocksWorklist.remove();
      int startOfBlockIndex = source.instructionIndex(startOfBlockOffset);
      // Check that the block has not been processed after being added.
      if (isIndexProcessed(startOfBlockIndex)) {
        continue;
      }
      // Process each instruction until the block is closed.
      for (int index = startOfBlockIndex; index < instCount; ++index) {
        markIndexProcessed(index);
        int closedAt = source.traceInstruction(index, this);
        if (closedAt != -1) {
          if (closedAt + 1 < instCount) {
            ensureBlockWithoutEnqueuing(source.instructionOffset(closedAt + 1));
          }
          break;
        }
        // If the next instruction starts a block, fall through to it.
        if (index + 1 < instCount) {
          int nextOffset = source.instructionOffset(index + 1);
          if (targets.get(nextOffset) != null) {
            ensureNormalSuccessorBlock(startOfBlockOffset, nextOffset);
            break;
          }
        }
      }
    }
    processedInstructions = null;

    setCurrentBlock(targets.get(INITIAL_BLOCK_OFFSET).block);
    source.buildPrelude(this);

    // Process normal blocks reachable from the entry block using a worklist of reachable
    // blocks.
    addToWorklist(currentBlock, 0);
    processWorklist();

    // Check that the last block is closed and does not fall off the end.
    assert currentBlock == null;

    // Handle where a catch handler hits the same block as the fallthrough.
    handleFallthroughToCatchBlock();

    // Verify that we have properly filled all blocks
    // Must be after handle-catch (which has delayed edges),
    // but before handle-exit (which does not maintain predecessor counts).
    assert verifyFilledPredecessors();

    // Insert debug positions so all position changes are marked by an explicit instruction.
    boolean hasDebugPositions = insertDebugPositions();

    // Clear all reaching definitions to free up memory (and avoid invalid use).
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
      block.clearCurrentDefinitions();
    }

    // Join predecessors for which all phis have the same inputs. This avoids generating the
    // same phi moves in multiple blocks.
    joinPredecessorsWithIdenticalPhis();

    // Package up the IR code.
    com.debughelper.tools.r8.ir.code.IRCode ir = new IRCode(options, method, blocks, valueNumberGenerator, hasDebugPositions);

    // Split critical edges to make sure that we have a place to insert phi moves if
    // necessary.
    ir.splitCriticalEdges();

    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
      block.deduplicatePhis();
    }

    ir.removeAllTrivialPhis();

    if (hasImpreciseTypes()) {
      com.debughelper.tools.r8.ir.conversion.TypeConstraintResolver resolver = new TypeConstraintResolver();
      resolver.resolve(ir);
    }

    // Clear the code so we don't build multiple times.
    source.clear();
    source = null;

    assert ir.isConsistentSSA();
    return ir;
  }

  private boolean hasImpreciseTypes() {
    if (hasImpreciseInstructionOutValueTypes) {
      return true;
    }
    // TODO(zerny): Consider keeping track of the imprecise phi types during phi construction.
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
      for (com.debughelper.tools.r8.ir.code.Phi phi : block.getPhis()) {
        if (!phi.outType().isPreciseType()) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean insertDebugPositions() {
    boolean hasDebugPositions = false;
    if (!options.debug) {
      return hasDebugPositions;
    }
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
      com.debughelper.tools.r8.ir.code.Position current = null;
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
        com.debughelper.tools.r8.ir.code.Position position = instruction.getPosition();
        if (instruction.isMoveException()) {
          assert current == null;
          current = position;
          hasDebugPositions = hasDebugPositions || position.isSome();
        } else if (instruction.isDebugPosition()) {
          hasDebugPositions = true;
          if (position.equals(current)) {
            it.removeOrReplaceByDebugLocalRead();
          } else {
            current = position;
          }
        } else if (position.isSome() && !position.synthetic && !position.equals(current)) {
          com.debughelper.tools.r8.ir.code.DebugPosition positionChange = new com.debughelper.tools.r8.ir.code.DebugPosition();
          positionChange.setPosition(position);
          it.previous();
          it.add(positionChange);
          it.next();
          current = position;
        }
      }
    }
    return hasDebugPositions;
  }

  private boolean verifyFilledPredecessors() {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
      assert verifyFilledPredecessors(block);
    }
    return true;
  }

  private boolean verifyFilledPredecessors(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    assert block.verifyFilledPredecessors();
    // TODO(zerny): Consider moving the validation of the initial control-flow graph to after its
    // construction and prior to building the IR.
    for (BlockInfo info : targets.values()) {
      if (info != null && info.block == block) {
        assert info.predecessorCount() == block.getPredecessors().size();
        assert info.normalSuccessors.size() == block.getNormalSuccessors().size();
        if (block.hasCatchHandlers()) {
          assert info.exceptionalSuccessors.size()
              == block.getCatchHandlers().getUniqueTargets().size();
        } else {
          assert !block.canThrow()
              || info.exceptionalSuccessors.isEmpty()
              || (info.exceptionalSuccessors.size() == 1
                  && info.exceptionalSuccessors.iterator().nextInt() < 0);
        }
        return true;
      }
    }
    // There are places where we add in new blocks that we do not represent in the initial CFG.
    // TODO(zerny): Should we maintain the initial CFG after instruction building?
    return true;
  }

  private void processWorklist() {
    for (WorklistItem item = ssaWorklist.poll(); item != null; item = ssaWorklist.poll()) {
      if (item.block.isFilled()) {
        continue;
      }
      setCurrentBlock(item.block);
      blocks.add(currentBlock);
      currentBlock.setNumber(nextBlockNumber++);
      // Process synthesized move-exception block specially.
      if (item instanceof MoveExceptionWorklistItem) {
        processMoveExceptionItem((MoveExceptionWorklistItem) item);
        continue;
      }
      // Build IR for each dex instruction in the block.
      int instCount = source.instructionCount();
      for (int i = item.firstInstructionIndex; i < instCount; ++i) {
        if (currentBlock == null) {
          break;
        }
        BlockInfo info = targets.get(source.instructionOffset(i));
        if (info != null && info.block != currentBlock) {
          closeCurrentBlockWithFallThrough(info.block);
          addToWorklist(info.block, i);
          break;
        }
        source.buildInstruction(this, i, i == item.firstInstructionIndex);
      }
    }
  }

  private void processMoveExceptionItem(MoveExceptionWorklistItem moveExceptionItem) {
    // TODO(zerny): Link with outer try-block handlers, if any. b/65203529
    int moveExceptionDest = source.getMoveExceptionRegister();
    assert moveExceptionDest >= 0;
    int targetIndex = source.instructionIndex(moveExceptionItem.targetOffset);
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(moveExceptionDest, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW, null);
    com.debughelper.tools.r8.ir.code.Position position = source.getDebugPositionAtOffset(moveExceptionItem.targetOffset);
    com.debughelper.tools.r8.ir.code.MoveException moveException = new com.debughelper.tools.r8.ir.code.MoveException(out);
    moveException.setPosition(position);
    currentBlock.add(moveException);
    com.debughelper.tools.r8.ir.code.Goto exit = new com.debughelper.tools.r8.ir.code.Goto();
    currentBlock.add(exit);
    com.debughelper.tools.r8.ir.code.BasicBlock targetBlock = getTarget(moveExceptionItem.targetOffset);
    currentBlock.link(targetBlock);
    addToWorklist(targetBlock, targetIndex);
    closeCurrentBlock();
  }

  // Helper to resolve switch payloads and build switch instructions (dex code only).
  public void resolveAndBuildSwitch(int value, int fallthroughOffset, int payloadOffset) {
    source.resolveAndBuildSwitch(value, fallthroughOffset, payloadOffset, this);
  }

  // Helper to resolve fill-array data and build new-array instructions (dex code only).
  public void resolveAndBuildNewArrayFilledData(int arrayRef, int payloadOffset) {
    source.resolveAndBuildNewArrayFilledData(arrayRef, payloadOffset, this);
  }

  /**
   * Add an (non-jump) instruction to the builder.
   *
   * @param ir IR instruction to add as the next instruction.
   */
  public void add(com.debughelper.tools.r8.ir.code.Instruction ir) {
    assert !ir.isJumpInstruction();
    addInstruction(ir);
  }

  public void addThisArgument(int register) {
    com.debughelper.tools.r8.graph.DebugLocalInfo local = getOutgoingLocal(register);
    com.debughelper.tools.r8.ir.code.Value value = writeRegister(register, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW, local);
    addInstruction(new com.debughelper.tools.r8.ir.code.Argument(value));
    value.markAsThis();
  }

  public void addNonThisArgument(int register, com.debughelper.tools.r8.ir.code.ValueType valueType) {
    com.debughelper.tools.r8.graph.DebugLocalInfo local = getOutgoingLocal(register);
    com.debughelper.tools.r8.ir.code.Value value = writeRegister(register, valueType, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW, local);
    addInstruction(new com.debughelper.tools.r8.ir.code.Argument(value));
  }

  public void addBooleanNonThisArgument(int register) {
    com.debughelper.tools.r8.graph.DebugLocalInfo local = getOutgoingLocal(register);
    com.debughelper.tools.r8.ir.code.Value value = writeRegister(register, com.debughelper.tools.r8.ir.code.ValueType.INT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW, local);
    value.setKnownToBeBoolean(true);
    addInstruction(new Argument(value));
  }

  public void addDebugUninitialized(int register, com.debughelper.tools.r8.ir.code.ValueType type) {
    if (!options.debug) {
      return;
    }
    com.debughelper.tools.r8.ir.code.Value value = writeRegister(register, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW, null);
    assert !value.hasLocalInfo();
    addInstruction(new DebugLocalUninitialized(value));
  }

  private void addDebugLocalWrite(com.debughelper.tools.r8.ir.code.ValueType type, int dest, com.debughelper.tools.r8.ir.code.Value in) {
    assert options.debug;
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.DebugLocalWrite write = new com.debughelper.tools.r8.ir.code.DebugLocalWrite(out, in);
    assert !write.instructionTypeCanThrow();
    addInstruction(write);
  }

  private com.debughelper.tools.r8.ir.code.Value getIncomingLocalValue(int register, com.debughelper.tools.r8.graph.DebugLocalInfo local) {
    assert options.debug;
    assert local != null;
    assert local == getIncomingLocal(register);
    com.debughelper.tools.r8.ir.code.ValueType valueType = com.debughelper.tools.r8.ir.code.ValueType.fromDexType(local.type);
    return readRegisterIgnoreLocal(register, valueType);
  }

  private static boolean isValidFor(com.debughelper.tools.r8.ir.code.Value value, com.debughelper.tools.r8.graph.DebugLocalInfo local) {
    // Invalid debug-info may cause attempt to read a local that is not actually alive.
    // See b/37722432 and regression test {@code jasmin.InvalidDebugInfoTests::testInvalidInfoThrow}
    return !value.isUninitializedLocal() && value.getLocalInfo() == local;
  }

  public void addDebugLocalRead(int register, com.debughelper.tools.r8.graph.DebugLocalInfo local) {
    if (!options.debug) {
      return;
    }
    com.debughelper.tools.r8.ir.code.Value value = getIncomingLocalValue(register, local);
    if (isValidFor(value, local)) {
      debugLocalReads.add(value);
    }
  }

  public void addDebugLocalStart(int register, com.debughelper.tools.r8.graph.DebugLocalInfo local) {
    if (!options.debug) {
      return;
    }
    assert local != null;
    assert local == getOutgoingLocal(register);
    com.debughelper.tools.r8.ir.code.ValueType valueType = com.debughelper.tools.r8.ir.code.ValueType.fromDexType(local.type);
    // TODO(mathiasr): Here we create a Phi with type based on debug info. That's just wrong!
    com.debughelper.tools.r8.ir.code.Value incomingValue = readRegisterIgnoreLocal(register, valueType);

    // TODO(mathiasr): This can be simplified once trivial phi removal is local-info aware.
    if (incomingValue.isPhi() || incomingValue.getLocalInfo() != local) {
      addDebugLocalWrite(com.debughelper.tools.r8.ir.code.ValueType.fromDexType(local.type), register, incomingValue);
      return;
    }
    assert incomingValue.getLocalInfo() == local;
    assert !incomingValue.isUninitializedLocal();

    // When inserting a start there are three possibilities:
    // 1. The block is empty (eg, instructions from block entry until now materialized to nothing).
    // 2. The block is non-empty and the last instruction defines the local to start.
    // 3. The block is non-empty and the last instruction does not define the local to start.
    if (currentBlock.getInstructions().isEmpty()) {
      addInstruction(new com.debughelper.tools.r8.ir.code.DebugLocalRead());
    }
    com.debughelper.tools.r8.ir.code.Instruction instruction = currentBlock.getInstructions().getLast();
    if (instruction.outValue() == incomingValue) {
      return;
    }
    instruction.addDebugValue(incomingValue);
    incomingValue.addDebugLocalStart(instruction);
  }

  public void addDebugLocalEnd(int register, com.debughelper.tools.r8.graph.DebugLocalInfo local) {
    if (!options.debug) {
      return;
    }
    com.debughelper.tools.r8.ir.code.Value value = getIncomingLocalValue(register, local);
    if (!isValidFor(value, local)) {
      return;
    }
    // When inserting an end there are three possibilities:
    // 1. The block is empty (eg, instructions from block entry until now materialized to nothing).
    // 2. The block has an instruction not defining the local being ended.
    // 3. The block has an instruction defining the local being ended.
    if (currentBlock.getInstructions().isEmpty()) {
      addInstruction(new DebugLocalRead());
    }
    com.debughelper.tools.r8.ir.code.Instruction instruction = currentBlock.getInstructions().getLast();
    if (instruction.outValue() != value) {
      instruction.addDebugValue(value);
      value.addDebugLocalEnd(instruction);
      return;
    }
    // In case 3. there are two cases:
    // a. The defining instruction is a debug-write, in which case it should be removed.
    // b. The defining instruction is overwriting the local value, in which case we de-associate it.
    assert !instruction.outValue().isUsed();
    if (instruction.isDebugLocalWrite()) {
      DebugLocalWrite write = instruction.asDebugLocalWrite();
      currentBlock.replaceCurrentDefinitions(value, write.src());
      currentBlock.listIterator(write).removeOrReplaceByDebugLocalRead();
    } else {
      instruction.outValue().clearLocalInfo();
    }
  }

  public void addDebugPosition(com.debughelper.tools.r8.ir.code.Position position) {
    if (options.debug) {
      assert source.getCurrentPosition().equals(position);
      addInstruction(new DebugPosition());
    }
  }

  public void addAdd(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readNumericRegister(right, type);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Add instruction = new com.debughelper.tools.r8.ir.code.Add(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addAddLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert isNonLongIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Add instruction = new Add(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addAnd(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    assert isIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readNumericRegister(right, type);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.And instruction = new com.debughelper.tools.r8.ir.code.And(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addAndLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert isNonLongIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.And instruction = new And(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addArrayGet(com.debughelper.tools.r8.ir.code.MemberType type, int dest, int array, int index) {
    com.debughelper.tools.r8.ir.code.Value in1 = readRegister(array, com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    com.debughelper.tools.r8.ir.code.Value in2 = readRegister(index, com.debughelper.tools.r8.ir.code.ValueType.INT);
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.fromMemberType(type), com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    out.setKnownToBeBoolean(type == com.debughelper.tools.r8.ir.code.MemberType.BOOLEAN);
    com.debughelper.tools.r8.ir.code.ArrayGet instruction = new ArrayGet(type, out, in1, in2);
    assert instruction.instructionTypeCanThrow();
    add(instruction);
  }

  public void addArrayLength(int dest, int array) {
    com.debughelper.tools.r8.ir.code.Value in = readRegister(array, com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.INT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    com.debughelper.tools.r8.ir.code.ArrayLength instruction = new ArrayLength(out, in);
    assert instruction.instructionTypeCanThrow();
    add(instruction);
  }

  public void addArrayPut(com.debughelper.tools.r8.ir.code.MemberType type, int value, int array, int index) {
    com.debughelper.tools.r8.ir.code.Value inValue = readRegister(value, com.debughelper.tools.r8.ir.code.ValueType.fromMemberType(type));
    com.debughelper.tools.r8.ir.code.Value inArray = readRegister(array, com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    com.debughelper.tools.r8.ir.code.Value inIndex = readRegister(index, com.debughelper.tools.r8.ir.code.ValueType.INT);
    com.debughelper.tools.r8.ir.code.ArrayPut instruction = new ArrayPut(type, inArray, inIndex, inValue);
    add(instruction);
  }

  public void addCheckCast(int value, com.debughelper.tools.r8.graph.DexType type) {
    com.debughelper.tools.r8.ir.code.Value in = readRegister(value, com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(value, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    com.debughelper.tools.r8.ir.code.CheckCast instruction = new CheckCast(out, in, type);
    assert instruction.instructionTypeCanThrow();
    add(instruction);
  }

  public void addCmp(com.debughelper.tools.r8.ir.code.NumericType type, com.debughelper.tools.r8.ir.code.Cmp.Bias bias, int dest, int left, int right) {
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readNumericRegister(right, type);
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.INT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Cmp instruction = new com.debughelper.tools.r8.ir.code.Cmp(type, bias, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    add(instruction);
  }

  public void addConst(com.debughelper.tools.r8.ir.code.ValueType type, int dest, long value) {
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.ConstNumber instruction = new com.debughelper.tools.r8.ir.code.ConstNumber(out, value);
    assert !instruction.instructionTypeCanThrow();
    add(instruction);
  }

  public void addLongConst(int dest, long value) {
    add(new com.debughelper.tools.r8.ir.code.ConstNumber(writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.LONG, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW), value));
  }

  public void addDoubleConst(int dest, long value) {
    add(new com.debughelper.tools.r8.ir.code.ConstNumber(writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.DOUBLE, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW), value));
  }

  public void addIntConst(int dest, long value) {
    add(new com.debughelper.tools.r8.ir.code.ConstNumber(writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.INT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW), value));
  }

  public void addFloatConst(int dest, long value) {
    add(new com.debughelper.tools.r8.ir.code.ConstNumber(writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.FLOAT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW), value));
  }

  public void addNullConst(int dest) {
    add(new com.debughelper.tools.r8.ir.code.ConstNumber(writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW), 0L));
  }

  public void addConstClass(int dest, com.debughelper.tools.r8.graph.DexType type) {
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    com.debughelper.tools.r8.ir.code.ConstClass instruction = new ConstClass(out, type);
    assert instruction.instructionTypeCanThrow();
    add(instruction);
  }

  public void addConstMethodHandle(int dest, com.debughelper.tools.r8.graph.DexMethodHandle methodHandle) {
    if (!options.canUseConstantMethodHandle()) {
      throw new com.debughelper.tools.r8.ApiLevelException(
          com.debughelper.tools.r8.utils.AndroidApiLevel.P,
          "Const-method-handle",
          null /* sourceString */);
    }
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    com.debughelper.tools.r8.ir.code.ConstMethodHandle instruction = new ConstMethodHandle(out, methodHandle);
    add(instruction);
  }

  public void addConstMethodType(int dest, com.debughelper.tools.r8.graph.DexProto methodType) {
    if (!options.canUseConstantMethodType()) {
      throw new com.debughelper.tools.r8.ApiLevelException(
          com.debughelper.tools.r8.utils.AndroidApiLevel.P,
          "Const-method-type",
          null /* sourceString */);
    }
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    com.debughelper.tools.r8.ir.code.ConstMethodType instruction = new ConstMethodType(out, methodType);
    add(instruction);
  }

  public void addConstString(int dest, com.debughelper.tools.r8.graph.DexString string) {
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    com.debughelper.tools.r8.ir.code.ConstString instruction = new ConstString(out, string);
    add(instruction);
  }

  public void addDiv(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    boolean canThrow = type != com.debughelper.tools.r8.ir.code.NumericType.DOUBLE && type != com.debughelper.tools.r8.ir.code.NumericType.FLOAT;
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readNumericRegister(right, type);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type,
        canThrow ? com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW : com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Div instruction = new com.debughelper.tools.r8.ir.code.Div(type, out, in1, in2);
    assert instruction.instructionTypeCanThrow() == canThrow;
    add(instruction);
  }

  public void addDivLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert isNonLongIntegerType(type);
    boolean canThrow = type != com.debughelper.tools.r8.ir.code.NumericType.DOUBLE && type != com.debughelper.tools.r8.ir.code.NumericType.FLOAT;
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type,
        canThrow ? com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW : com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Div instruction = new Div(type, out, in1, in2);
    assert instruction.instructionTypeCanThrow() == canThrow;
    add(instruction);
  }

  public com.debughelper.tools.r8.ir.code.Monitor addMonitor(com.debughelper.tools.r8.ir.code.Monitor.Type type, int monitor) {
    com.debughelper.tools.r8.ir.code.Value in = readRegister(monitor, com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    com.debughelper.tools.r8.ir.code.Monitor monitorEnter = new Monitor(type, in);
    add(monitorEnter);
    return monitorEnter;
  }

  public void addMove(com.debughelper.tools.r8.ir.code.ValueType type, int dest, int src) {
    com.debughelper.tools.r8.ir.code.Value in = readRegister(src, type);
    if (options.debug) {
      // If the move is writing to a different local we must construct a new value.
      com.debughelper.tools.r8.graph.DebugLocalInfo destLocal = getOutgoingLocal(dest);
      if (destLocal != null && destLocal != in.getLocalInfo()) {
        addDebugLocalWrite(type, dest, in);
        return;
      }
    }
    currentBlock.writeCurrentDefinition(dest, in, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
  }

  public void addMul(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readNumericRegister(right, type);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Mul instruction = new com.debughelper.tools.r8.ir.code.Mul(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addMulLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert isNonLongIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Mul instruction = new Mul(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addRem(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    boolean canThrow = type != com.debughelper.tools.r8.ir.code.NumericType.DOUBLE && type != com.debughelper.tools.r8.ir.code.NumericType.FLOAT;
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readNumericRegister(right, type);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type,
        canThrow ? com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW : com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Rem instruction = new com.debughelper.tools.r8.ir.code.Rem(type, out, in1, in2);
    assert instruction.instructionTypeCanThrow() == canThrow;
    addInstruction(instruction);
  }

  public void addRemLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert isNonLongIntegerType(type);
    boolean canThrow = type != com.debughelper.tools.r8.ir.code.NumericType.DOUBLE && type != com.debughelper.tools.r8.ir.code.NumericType.FLOAT;
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type,
        canThrow ? com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW : com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Rem instruction = new Rem(type, out, in1, in2);
    assert instruction.instructionTypeCanThrow() == canThrow;
    addInstruction(instruction);
  }

  public void addGoto(int targetOffset) {
    addInstruction(new com.debughelper.tools.r8.ir.code.Goto());
    com.debughelper.tools.r8.ir.code.BasicBlock targetBlock = getTarget(targetOffset);
    if (currentBlock.hasCatchSuccessor(targetBlock)) {
      needGotoToCatchBlocks.add(new com.debughelper.tools.r8.ir.code.BasicBlock.Pair(currentBlock, targetBlock));
    } else {
      currentBlock.link(targetBlock);
    }
    addToWorklist(targetBlock, source.instructionIndex(targetOffset));
    closeCurrentBlock();
  }

  private void addTrivialIf(int trueTargetOffset, int falseTargetOffset) {
    assert trueTargetOffset == falseTargetOffset;
    // Conditional instructions with the same true and false targets are noops. They will
    // always go to the next instruction. We end this basic block with a goto instead of
    // a conditional.
    com.debughelper.tools.r8.ir.code.BasicBlock target = getTarget(trueTargetOffset);
    // We expected an if here and therefore we incremented the expected predecessor count
    // twice for the following block.
    target.decrementUnfilledPredecessorCount();
    addInstruction(new com.debughelper.tools.r8.ir.code.Goto());
    currentBlock.link(target);
    addToWorklist(target, source.instructionIndex(trueTargetOffset));
    closeCurrentBlock();
  }

  private void addNonTrivialIf(com.debughelper.tools.r8.ir.code.If instruction, int trueTargetOffset, int falseTargetOffset) {
    addInstruction(instruction);
    com.debughelper.tools.r8.ir.code.BasicBlock trueTarget = getTarget(trueTargetOffset);
    com.debughelper.tools.r8.ir.code.BasicBlock falseTarget = getTarget(falseTargetOffset);
    currentBlock.link(trueTarget);
    currentBlock.link(falseTarget);
    // Generate fall-through before the block that is branched to.
    addToWorklist(falseTarget, source.instructionIndex(falseTargetOffset));
    addToWorklist(trueTarget, source.instructionIndex(trueTargetOffset));
    closeCurrentBlock();
  }

  public void addIf(com.debughelper.tools.r8.ir.code.If.Type type, com.debughelper.tools.r8.ir.code.ValueType operandType, int value1, int value2,
                    int trueTargetOffset, int falseTargetOffset) {
    if (trueTargetOffset == falseTargetOffset) {
      addTrivialIf(trueTargetOffset, falseTargetOffset);
    } else {
      List<com.debughelper.tools.r8.ir.code.Value> values = new ArrayList<>(2);
      values.add(readRegister(value1, operandType));
      values.add(readRegister(value2, operandType));
      com.debughelper.tools.r8.ir.code.If instruction = new com.debughelper.tools.r8.ir.code.If(type, values);
      addNonTrivialIf(instruction, trueTargetOffset, falseTargetOffset);
    }
  }

  public void addIfZero(com.debughelper.tools.r8.ir.code.If.Type type, com.debughelper.tools.r8.ir.code.ValueType operandType, int value, int trueTargetOffset, int falseTargetOffset) {
    if (trueTargetOffset == falseTargetOffset) {
      addTrivialIf(trueTargetOffset, falseTargetOffset);
    } else {
      com.debughelper.tools.r8.ir.code.If instruction = new If(type, readRegister(value, operandType));
      addNonTrivialIf(instruction, trueTargetOffset, falseTargetOffset);
    }
  }

  public void addInstanceGet(int dest, int object, com.debughelper.tools.r8.graph.DexField field) {
    com.debughelper.tools.r8.ir.code.MemberType type = com.debughelper.tools.r8.ir.code.MemberType.fromDexType(field.type);
    com.debughelper.tools.r8.ir.code.Value in = readRegister(object, com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.fromMemberType(type), com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    out.setKnownToBeBoolean(type == com.debughelper.tools.r8.ir.code.MemberType.BOOLEAN);
    com.debughelper.tools.r8.ir.code.InstanceGet instruction = new InstanceGet(type, out, in, field);
    assert instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addInstanceOf(int dest, int value, com.debughelper.tools.r8.graph.DexType type) {
    com.debughelper.tools.r8.ir.code.Value in = readRegister(value, com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.INT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    com.debughelper.tools.r8.ir.code.InstanceOf instruction = new InstanceOf(out, in, type);
    assert instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addInstancePut(int value, int object, com.debughelper.tools.r8.graph.DexField field) {
    com.debughelper.tools.r8.ir.code.MemberType type = com.debughelper.tools.r8.ir.code.MemberType.fromDexType(field.type);
    com.debughelper.tools.r8.ir.code.Value objectValue = readRegister(object, com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    com.debughelper.tools.r8.ir.code.Value valueValue = readRegister(value, com.debughelper.tools.r8.ir.code.ValueType.fromMemberType(type));
    com.debughelper.tools.r8.ir.code.InstancePut instruction = new InstancePut(type, field, objectValue, valueValue);
    add(instruction);
  }

  public void addInvoke(
          com.debughelper.tools.r8.ir.code.Invoke.Type type, com.debughelper.tools.r8.graph.DexItem item, com.debughelper.tools.r8.graph.DexProto callSiteProto, List<com.debughelper.tools.r8.ir.code.Value> arguments, boolean itf) {
    if (type == com.debughelper.tools.r8.ir.code.Invoke.Type.POLYMORPHIC) {
      assert item instanceof com.debughelper.tools.r8.graph.DexMethod;
      if (!options.canUseInvokePolymorphic()) {
        throw new com.debughelper.tools.r8.ApiLevelException(
            com.debughelper.tools.r8.utils.AndroidApiLevel.O,
            "MethodHandle.invoke and MethodHandle.invokeExact",
            null /* sourceString */);
      } else if (!options.canUseInvokePolymorphicOnVarHandle()
          && ((com.debughelper.tools.r8.graph.DexMethod) item).getHolder() == options.itemFactory.varHandleType) {
        throw new ApiLevelException(
            AndroidApiLevel.P,
            "Call to polymorphic signature of VarHandle",
            null /* sourceString */);
      }
    }
    if (appInfo != null && type == com.debughelper.tools.r8.ir.code.Invoke.Type.VIRTUAL) {
      // If an invoke-virtual targets a private method in the current class overriding will
      // not apply (see jvm spec on method resolution 5.4.3.3 and overriding 5.4.5) and
      // therefore we use an invoke-direct instead. We need to do this as the debughelper Runtime
      // will not allow invoke-virtual of a private method.
      com.debughelper.tools.r8.graph.DexMethod invocationMethod = (com.debughelper.tools.r8.graph.DexMethod) item;
      if (invocationMethod.holder == method.method.holder) {
        DexEncodedMethod directTarget = appInfo.lookupDirectTarget(invocationMethod);
        if (directTarget != null && invocationMethod.holder == directTarget.method.holder) {
          type = com.debughelper.tools.r8.ir.code.Invoke.Type.DIRECT;
        }
      }
    }
    add(com.debughelper.tools.r8.ir.code.Invoke.create(type, item, callSiteProto, null, arguments, itf));
  }

  public void addInvoke(com.debughelper.tools.r8.ir.code.Invoke.Type type, com.debughelper.tools.r8.graph.DexItem item, com.debughelper.tools.r8.graph.DexProto callSiteProto, List<com.debughelper.tools.r8.ir.code.Value> arguments) {
    addInvoke(type, item, callSiteProto, arguments, false);
  }

  public void addInvoke(
      com.debughelper.tools.r8.ir.code.Invoke.Type type,
      com.debughelper.tools.r8.graph.DexItem item,
      com.debughelper.tools.r8.graph.DexProto callSiteProto,
      List<com.debughelper.tools.r8.ir.code.ValueType> types,
      List<Integer> registers) {
    addInvoke(type, item, callSiteProto, types, registers, false);
  }

  public void addInvoke(
      com.debughelper.tools.r8.ir.code.Invoke.Type type,
      DexItem item,
      com.debughelper.tools.r8.graph.DexProto callSiteProto,
      List<com.debughelper.tools.r8.ir.code.ValueType> types,
      List<Integer> registers,
      boolean itf) {
    assert types.size() == registers.size();
    List<com.debughelper.tools.r8.ir.code.Value> arguments = new ArrayList<>(types.size());
    for (int i = 0; i < types.size(); i++) {
      arguments.add(readRegister(registers.get(i), types.get(i)));
    }
    addInvoke(type, item, callSiteProto, arguments, itf);
  }

  public void addInvokeCustomRegisters(
          com.debughelper.tools.r8.graph.DexCallSite callSite, int argumentRegisterCount, int[] argumentRegisters) {
    int registerIndex = 0;
    com.debughelper.tools.r8.graph.DexMethodHandle bootstrapMethod = callSite.bootstrapMethod;
    List<com.debughelper.tools.r8.ir.code.Value> arguments = new ArrayList<>(argumentRegisterCount);

    if (!bootstrapMethod.isStaticHandle()) {
      arguments.add(readRegister(argumentRegisters[registerIndex], com.debughelper.tools.r8.ir.code.ValueType.OBJECT));
      registerIndex += com.debughelper.tools.r8.ir.code.ValueType.OBJECT.requiredRegisters();
    }

    String shorty = callSite.methodProto.shorty.toString();

    for (int i = 1; i < shorty.length(); i++) {
      com.debughelper.tools.r8.ir.code.ValueType valueType = com.debughelper.tools.r8.ir.code.ValueType.fromTypeDescriptorChar(shorty.charAt(i));
      arguments.add(readRegister(argumentRegisters[registerIndex], valueType));
      registerIndex += valueType.requiredRegisters();
    }

    add(new com.debughelper.tools.r8.ir.code.InvokeCustom(callSite, null, arguments));
  }

  public void addInvokeCustomRange(
          com.debughelper.tools.r8.graph.DexCallSite callSite, int argumentCount, int firstArgumentRegister) {
    DexMethodHandle bootstrapMethod = callSite.bootstrapMethod;
    List<com.debughelper.tools.r8.ir.code.Value> arguments = new ArrayList<>(argumentCount);

    int register = firstArgumentRegister;
    if (!bootstrapMethod.isStaticHandle()) {
      arguments.add(readRegister(register, com.debughelper.tools.r8.ir.code.ValueType.OBJECT));
      register += com.debughelper.tools.r8.ir.code.ValueType.OBJECT.requiredRegisters();
    }

    String shorty = callSite.methodProto.shorty.toString();

    for (int i = 1; i < shorty.length(); i++) {
      com.debughelper.tools.r8.ir.code.ValueType valueType = com.debughelper.tools.r8.ir.code.ValueType.fromTypeDescriptorChar(shorty.charAt(i));
      arguments.add(readRegister(register, valueType));
      register += valueType.requiredRegisters();
    }
    checkInvokeArgumentRegisters(register, firstArgumentRegister + argumentCount);
    add(new com.debughelper.tools.r8.ir.code.InvokeCustom(callSite, null, arguments));
  }

  public void addInvokeCustom(
          DexCallSite callSite, List<com.debughelper.tools.r8.ir.code.ValueType> types, List<Integer> registers) {
    assert types.size() == registers.size();
    List<com.debughelper.tools.r8.ir.code.Value> arguments = new ArrayList<>(types.size());
    for (int i = 0; i < types.size(); i++) {
      arguments.add(readRegister(registers.get(i), types.get(i)));
    }
    add(new InvokeCustom(callSite, null, arguments));
  }

  public void addInvokeRegisters(
      com.debughelper.tools.r8.ir.code.Invoke.Type type,
      com.debughelper.tools.r8.graph.DexMethod method,
      com.debughelper.tools.r8.graph.DexProto callSiteProto,
      int argumentRegisterCount,
      int[] argumentRegisters) {
    // The value of argumentRegisterCount is the number of registers - not the number of values,
    // but it is an upper bound on the number of arguments.
    List<com.debughelper.tools.r8.ir.code.Value> arguments = new ArrayList<>(argumentRegisterCount);
    int registerIndex = 0;
    if (type != com.debughelper.tools.r8.ir.code.Invoke.Type.STATIC) {
      arguments.add(readRegister(argumentRegisters[registerIndex], com.debughelper.tools.r8.ir.code.ValueType.OBJECT));
      registerIndex += com.debughelper.tools.r8.ir.code.ValueType.OBJECT.requiredRegisters();
    }
    com.debughelper.tools.r8.graph.DexString methodShorty;
    if (type == com.debughelper.tools.r8.ir.code.Invoke.Type.POLYMORPHIC) {
      // The call site signature for invoke polymorphic must be take from call site and not from
      // the called method.
      methodShorty = callSiteProto.shorty;
    } else {
      methodShorty = method.proto.shorty;
    }
    String shorty = methodShorty.toString();
    for (int i = 1; i < methodShorty.size; i++) {
      com.debughelper.tools.r8.ir.code.ValueType valueType = com.debughelper.tools.r8.ir.code.ValueType.fromTypeDescriptorChar(shorty.charAt(i));
      arguments.add(readRegister(argumentRegisters[registerIndex], valueType));
      registerIndex += valueType.requiredRegisters();
    }
    checkInvokeArgumentRegisters(registerIndex, argumentRegisterCount);
    addInvoke(type, method, callSiteProto, arguments);
  }

  public void addInvokeNewArray(com.debughelper.tools.r8.graph.DexType type, int argumentCount, int[] argumentRegisters) {
    String descriptor = type.descriptor.toString();
    assert descriptor.charAt(0) == '[';
    assert descriptor.length() >= 2;
    com.debughelper.tools.r8.ir.code.ValueType valueType = com.debughelper.tools.r8.ir.code.ValueType.fromTypeDescriptorChar(descriptor.charAt(1));
    List<com.debughelper.tools.r8.ir.code.Value> arguments = new ArrayList<>(argumentCount / valueType.requiredRegisters());
    int registerIndex = 0;
    while (registerIndex < argumentCount) {
      arguments.add(readRegister(argumentRegisters[registerIndex], valueType));
      if (valueType.isWide()) {
        assert registerIndex < argumentCount - 1;
        assert argumentRegisters[registerIndex] == argumentRegisters[registerIndex + 1] + 1;
      }
      registerIndex += valueType.requiredRegisters();
    }
    checkInvokeArgumentRegisters(registerIndex, argumentCount);
    addInvoke(com.debughelper.tools.r8.ir.code.Invoke.Type.NEW_ARRAY, type, null, arguments);
  }

  public void addMultiNewArray(com.debughelper.tools.r8.graph.DexType type, int dest, int[] dimensions) {
    assert isGeneratingClassFiles();
    List<com.debughelper.tools.r8.ir.code.Value> arguments = new ArrayList<>(dimensions.length);
    for (int dimension : dimensions) {
      arguments.add(readRegister(dimension, com.debughelper.tools.r8.ir.code.ValueType.INT));
    }
    addInvoke(com.debughelper.tools.r8.ir.code.Invoke.Type.MULTI_NEW_ARRAY, type, null, arguments);
    addMoveResult(dest);
  }

  public void addInvokeRange(
      com.debughelper.tools.r8.ir.code.Invoke.Type type,
      DexMethod method,
      DexProto callSiteProto,
      int argumentCount,
      int firstArgumentRegister) {
    // The value of argumentCount is the number of registers - not the number of values, but it
    // is an upper bound on the number of arguments.
    List<com.debughelper.tools.r8.ir.code.Value> arguments = new ArrayList<>(argumentCount);
    int register = firstArgumentRegister;
    if (type != com.debughelper.tools.r8.ir.code.Invoke.Type.STATIC) {
      arguments.add(readRegister(register, com.debughelper.tools.r8.ir.code.ValueType.OBJECT));
      register += com.debughelper.tools.r8.ir.code.ValueType.OBJECT.requiredRegisters();
    }
    DexString methodShorty;
    if (type == com.debughelper.tools.r8.ir.code.Invoke.Type.POLYMORPHIC) {
      // The call site signature for invoke polymorphic must be take from call site and not from
      // the called method.
      methodShorty = callSiteProto.shorty;
    } else {
      methodShorty = method.proto.shorty;
    }
    String shorty = methodShorty.toString();
    for (int i = 1; i < methodShorty.size; i++) {
      com.debughelper.tools.r8.ir.code.ValueType valueType = com.debughelper.tools.r8.ir.code.ValueType.fromTypeDescriptorChar(shorty.charAt(i));
      arguments.add(readRegister(register, valueType));
      register += valueType.requiredRegisters();
    }
    checkInvokeArgumentRegisters(register, firstArgumentRegister + argumentCount);
    addInvoke(type, method, callSiteProto, arguments);
  }

  public void addInvokeRangeNewArray(com.debughelper.tools.r8.graph.DexType type, int argumentCount, int firstArgumentRegister) {
    String descriptor = type.descriptor.toString();
    assert descriptor.charAt(0) == '[';
    assert descriptor.length() >= 2;
    com.debughelper.tools.r8.ir.code.ValueType valueType = com.debughelper.tools.r8.ir.code.ValueType.fromTypeDescriptorChar(descriptor.charAt(1));
    List<com.debughelper.tools.r8.ir.code.Value> arguments = new ArrayList<>(argumentCount / valueType.requiredRegisters());
    int register = firstArgumentRegister;
    while (register < firstArgumentRegister + argumentCount) {
      arguments.add(readRegister(register, valueType));
      register += valueType.requiredRegisters();
    }
    checkInvokeArgumentRegisters(register, firstArgumentRegister + argumentCount);
    addInvoke(com.debughelper.tools.r8.ir.code.Invoke.Type.NEW_ARRAY, type, null, arguments);
  }

  private void checkInvokeArgumentRegisters(int expected, int actual) {
    if (expected != actual) {
      throw new com.debughelper.tools.r8.errors.CompilationError("Invalid invoke instruction. "
          + "Expected use of " + expected + " argument registers, "
          + "found actual use of " + actual);
    }
  }

  public void addMoveException(int dest) {
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    assert !out.hasLocalInfo();
    com.debughelper.tools.r8.ir.code.MoveException instruction = new com.debughelper.tools.r8.ir.code.MoveException(out);
    assert !instruction.instructionTypeCanThrow();
    if (currentBlock.getInstructions().size() == 1 && currentBlock.entry().isDebugPosition()) {
      InstructionListIterator it = currentBlock.listIterator();
      com.debughelper.tools.r8.ir.code.Instruction entry = it.next();
      assert entry.getPosition().equals(source.getCurrentPosition());
      attachLocalValues(instruction);
      it.replaceCurrentInstruction(instruction);
      return;
    }
    if (!currentBlock.getInstructions().isEmpty()) {
      throw new com.debughelper.tools.r8.errors.CompilationError("Invalid MoveException instruction encountered. "
          + "The MoveException instruction is not the first instruction in the block in "
          + method.qualifiedName()
          + ".");
    }
    addInstruction(instruction);
  }

  public void addMoveResult(int dest) {
    List<com.debughelper.tools.r8.ir.code.Instruction> instructions = currentBlock.getInstructions();
    com.debughelper.tools.r8.ir.code.Invoke invoke = instructions.get(instructions.size() - 1).asInvoke();
    assert invoke.outValue() == null;
    assert invoke.instructionTypeCanThrow();
    com.debughelper.tools.r8.graph.DexType outType = invoke.getReturnType();
    com.debughelper.tools.r8.ir.code.Value outValue = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.fromDexType(outType), com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    outValue.setKnownToBeBoolean(outType.isBooleanType());
    invoke.setOutValue(outValue);
  }

  public void addNeg(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value) {
    com.debughelper.tools.r8.ir.code.Value in = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Neg instruction = new Neg(type, out, in);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addNot(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value) {
    com.debughelper.tools.r8.ir.code.Value in = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Instruction instruction;
    if (options.canUseNotInstruction()) {
      instruction = new com.debughelper.tools.r8.ir.code.Not(type, out, in);
    } else {
      com.debughelper.tools.r8.ir.code.Value minusOne = readLiteral(com.debughelper.tools.r8.ir.code.ValueType.fromNumericType(type), -1);
      instruction = new com.debughelper.tools.r8.ir.code.Xor(type, out, in, minusOne);
    }
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addNewArrayEmpty(int dest, int size, com.debughelper.tools.r8.graph.DexType type) {
    assert type.isArrayType();
    com.debughelper.tools.r8.ir.code.Value in = readRegister(size, com.debughelper.tools.r8.ir.code.ValueType.INT);
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    com.debughelper.tools.r8.ir.code.NewArrayEmpty instruction = new NewArrayEmpty(out, in, type);
    assert instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addNewArrayFilledData(int arrayRef, int elementWidth, long size, short[] data) {
    add(new NewArrayFilledData(readRegister(arrayRef, com.debughelper.tools.r8.ir.code.ValueType.OBJECT), elementWidth, size, data));
  }

  public void addNewInstance(int dest, DexType type) {
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    com.debughelper.tools.r8.ir.code.NewInstance instruction = new NewInstance(type, out);
    assert instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addReturn(com.debughelper.tools.r8.ir.code.ValueType type, int value) {
    com.debughelper.tools.r8.ir.code.ValueType returnType = com.debughelper.tools.r8.ir.code.ValueType.fromDexType(method.method.proto.returnType);
    assert returnType.verifyCompatible(type);
    com.debughelper.tools.r8.ir.code.Value in = readRegister(value, returnType);
    addReturn(new com.debughelper.tools.r8.ir.code.Return(in, returnType));
  }

  public void addReturn() {
    addReturn(new com.debughelper.tools.r8.ir.code.Return());
  }

  private void addReturn(Return ret) {
    // Attach the live locals to the return instruction to avoid a local change on monitor exit.
    attachLocalValues(ret);
    source.buildPostlude(this);
    addInstruction(ret);
    closeCurrentBlock();
  }

  public void addStaticGet(int dest, com.debughelper.tools.r8.graph.DexField field) {
    com.debughelper.tools.r8.ir.code.MemberType type = com.debughelper.tools.r8.ir.code.MemberType.fromDexType(field.type);
    com.debughelper.tools.r8.ir.code.Value out = writeRegister(dest, com.debughelper.tools.r8.ir.code.ValueType.fromMemberType(type), com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
    out.setKnownToBeBoolean(type == com.debughelper.tools.r8.ir.code.MemberType.BOOLEAN);
    com.debughelper.tools.r8.ir.code.StaticGet instruction = new StaticGet(type, out, field);
    assert instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addStaticPut(int value, DexField field) {
    com.debughelper.tools.r8.ir.code.MemberType type = MemberType.fromDexType(field.type);
    com.debughelper.tools.r8.ir.code.Value in = readRegister(value, com.debughelper.tools.r8.ir.code.ValueType.fromMemberType(type));
    add(new StaticPut(type, in, field));
  }

  public void addSub(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readNumericRegister(right, type);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Sub instruction = new com.debughelper.tools.r8.ir.code.Sub(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addRsubLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert type != com.debughelper.tools.r8.ir.code.NumericType.DOUBLE;
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    // Add this as a sub instruction - sub instructions with literals need to have the constant
    // on the left side (rsub).
    com.debughelper.tools.r8.ir.code.Sub instruction = new Sub(type, out, in2, in1);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addSwitch(int value, int[] keys, int fallthroughOffset, int[] labelOffsets) {
    int numberOfTargets = labelOffsets.length;
    assert (keys.length == 1) || (keys.length == numberOfTargets);

    // If the switch has no targets simply add a goto to the fallthrough.
    if (numberOfTargets == 0) {
      addGoto(fallthroughOffset);
      return;
    }

    com.debughelper.tools.r8.ir.code.Value switchValue = readRegister(value, com.debughelper.tools.r8.ir.code.ValueType.INT);

    // Find the keys not targeting the fallthrough.
    IntList nonFallthroughKeys = new IntArrayList(numberOfTargets);
    IntList nonFallthroughOffsets = new IntArrayList(numberOfTargets);
    int numberOfFallthroughs = 0;
    if (keys.length == 1) {
      int key = keys[0];
      for (int i = 0; i < numberOfTargets; i++) {
        if (labelOffsets[i] != fallthroughOffset) {
          nonFallthroughKeys.add(key);
          nonFallthroughOffsets.add(labelOffsets[i]);
        } else {
          numberOfFallthroughs++;
        }
        key++;
      }
    } else {
      assert keys.length == numberOfTargets;
      for (int i = 0; i < numberOfTargets; i++) {
        if (labelOffsets[i] != fallthroughOffset) {
          nonFallthroughKeys.add(keys[i]);
          nonFallthroughOffsets.add(labelOffsets[i]);
        } else {
          numberOfFallthroughs++;
        }
      }
    }
    targets.get(fallthroughOffset).block.decrementUnfilledPredecessorCount(numberOfFallthroughs);

    // If this was switch with only fallthrough cases we can make it a goto.
    // Oddly, this does happen.
    if (numberOfFallthroughs == numberOfTargets) {
      assert nonFallthroughKeys.size() == 0;
      addGoto(fallthroughOffset);
      return;
    }

    // Create a switch with only the non-fallthrough targets.
    keys = nonFallthroughKeys.toIntArray();
    labelOffsets = nonFallthroughOffsets.toIntArray();
    addInstruction(createSwitch(switchValue, keys, fallthroughOffset, labelOffsets));
    closeCurrentBlock();
  }

  private com.debughelper.tools.r8.ir.code.Switch createSwitch(com.debughelper.tools.r8.ir.code.Value value, int[] keys, int fallthroughOffset, int[] targetOffsets) {
    assert keys.length == targetOffsets.length;
    // Compute target blocks for all keys. Only add a successor block once even
    // if it is hit by more of the keys.
    int[] targetBlockIndices = new int[targetOffsets.length];
    Map<Integer, Integer> offsetToBlockIndex = new HashMap<>();
    // Start with fall-through block.
    com.debughelper.tools.r8.ir.code.BasicBlock fallthroughBlock = getTarget(fallthroughOffset);
    currentBlock.link(fallthroughBlock);
    addToWorklist(fallthroughBlock, source.instructionIndex(fallthroughOffset));
    int fallthroughBlockIndex = currentBlock.getSuccessors().size() - 1;
    offsetToBlockIndex.put(fallthroughOffset, fallthroughBlockIndex);
    // Then all the switch target blocks.
    for (int i = 0; i < targetOffsets.length; i++) {
      int targetOffset = targetOffsets[i];
      com.debughelper.tools.r8.ir.code.BasicBlock targetBlock = getTarget(targetOffset);
      Integer targetBlockIndex = offsetToBlockIndex.get(targetOffset);
      if (targetBlockIndex == null) {
        // Target block not added as successor. Add it now.
        currentBlock.link(targetBlock);
        addToWorklist(targetBlock, source.instructionIndex(targetOffset));
        int successorIndex = currentBlock.getSuccessors().size() - 1;
        offsetToBlockIndex.put(targetOffset, successorIndex);
        targetBlockIndices[i] = successorIndex;
      } else {
        // Target block already added as successor. The target block therefore
        // has one less predecessor than precomputed.
        targetBlock.decrementUnfilledPredecessorCount();
        targetBlockIndices[i] = targetBlockIndex;
      }
    }
    return new Switch(value, keys, targetBlockIndices, fallthroughBlockIndex);
  }

  public void addThrow(int value) {
    com.debughelper.tools.r8.ir.code.Value in = readRegister(value, com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    addInstruction(new Throw(in));
    closeCurrentBlock();
  }

  public void addOr(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    assert isIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readNumericRegister(right, type);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Or instruction = new com.debughelper.tools.r8.ir.code.Or(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addOrLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert isNonLongIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Or instruction = new Or(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addShl(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    assert isIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readRegister(right, com.debughelper.tools.r8.ir.code.ValueType.INT);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Shl instruction = new com.debughelper.tools.r8.ir.code.Shl(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addShlLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert isNonLongIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Shl instruction = new Shl(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addShr(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    assert isIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readRegister(right, com.debughelper.tools.r8.ir.code.ValueType.INT);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Shr instruction = new com.debughelper.tools.r8.ir.code.Shr(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addShrLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert isNonLongIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Shr instruction = new Shr(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addUshr(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    assert isIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readRegister(right, com.debughelper.tools.r8.ir.code.ValueType.INT);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Ushr instruction = new com.debughelper.tools.r8.ir.code.Ushr(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addUshrLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert isNonLongIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Ushr instruction = new Ushr(type, out, in1, in2);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addXor(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int left, int right) {
    assert isIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(left, type);
    com.debughelper.tools.r8.ir.code.Value in2 = readNumericRegister(right, type);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.Instruction instruction;
    if (options.canUseNotInstruction() &&
        in2.isConstNumber() &&
        in2.getConstInstruction().asConstNumber().isIntegerNegativeOne(type)) {
      instruction = new com.debughelper.tools.r8.ir.code.Not(type, out, in1);
    } else {
      instruction = new com.debughelper.tools.r8.ir.code.Xor(type, out, in1, in2);
    }
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addXorLiteral(com.debughelper.tools.r8.ir.code.NumericType type, int dest, int value, int constant) {
    assert isNonLongIntegerType(type);
    com.debughelper.tools.r8.ir.code.Value in1 = readNumericRegister(value, type);
    com.debughelper.tools.r8.ir.code.Instruction instruction;
    if (options.canUseNotInstruction() && constant == -1) {
      com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
      instruction = new Not(type, out, in1);
    } else {
      com.debughelper.tools.r8.ir.code.Value in2 = readIntLiteral(constant);
      com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
      instruction = new Xor(type, out, in1, in2);
    }
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  public void addConversion(com.debughelper.tools.r8.ir.code.NumericType to, com.debughelper.tools.r8.ir.code.NumericType from, int dest, int source) {
    com.debughelper.tools.r8.ir.code.Value in = readNumericRegister(source, from);
    com.debughelper.tools.r8.ir.code.Value out = writeNumericRegister(dest, to, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW);
    com.debughelper.tools.r8.ir.code.NumberConversion instruction = new NumberConversion(from, to, out, in);
    assert !instruction.instructionTypeCanThrow();
    addInstruction(instruction);
  }

  // Value abstraction methods.

  public com.debughelper.tools.r8.ir.code.Value readRegister(int register, com.debughelper.tools.r8.ir.code.ValueType type) {
    com.debughelper.tools.r8.graph.DebugLocalInfo local = getIncomingLocal(register);
    com.debughelper.tools.r8.ir.code.Value value = readRegister(register, type, currentBlock, com.debughelper.tools.r8.ir.code.BasicBlock.EdgeType.NON_EDGE, local);
    // Check that any information about a current-local is consistent with the read.
    if (local != null && value.getLocalInfo() != local && !value.isUninitializedLocal()) {
      throw new InvalidDebugInfoException(
          "Attempt to read local " + local
              + " but no local information was associated with the value being read.");
    }
    // Check that any local information on the value is actually visible.
    // If this assert triggers, the probable cause is that we end up reading an SSA value
    // after it should have been ended on a fallthrough from a conditional jump or a trivial-phi
    // removal resurrected the local.
    assert !value.hasLocalInfo()
        || value.getDebugLocalEnds() != null
        || source.verifyLocalInScope(value.getLocalInfo());
    value.constrainType(type);
    return value;
  }

  private com.debughelper.tools.r8.ir.code.Value readRegisterIgnoreLocal(int register, com.debughelper.tools.r8.ir.code.ValueType type) {
    com.debughelper.tools.r8.graph.DebugLocalInfo local = getIncomingLocal(register);
    return readRegister(register, type, currentBlock, com.debughelper.tools.r8.ir.code.BasicBlock.EdgeType.NON_EDGE, local);
  }

  public com.debughelper.tools.r8.ir.code.Value readRegister(int register, com.debughelper.tools.r8.ir.code.ValueType type, com.debughelper.tools.r8.ir.code.BasicBlock block, com.debughelper.tools.r8.ir.code.BasicBlock.EdgeType readingEdge,
                                                             com.debughelper.tools.r8.graph.DebugLocalInfo local) {
    checkRegister(register);
    com.debughelper.tools.r8.ir.code.Value value = block.readCurrentDefinition(register, readingEdge);
    return value != null ? value : readRegisterRecursive(register, block, readingEdge, type, local);
  }

  private com.debughelper.tools.r8.ir.code.Value readRegisterRecursive(
          int register, com.debughelper.tools.r8.ir.code.BasicBlock block, com.debughelper.tools.r8.ir.code.BasicBlock.EdgeType readingEdge, com.debughelper.tools.r8.ir.code.ValueType type, com.debughelper.tools.r8.graph.DebugLocalInfo local) {
    com.debughelper.tools.r8.ir.code.Value value = null;
    // Iterate back along the predecessor chain as long as there is a single sealed predecessor.
    List<com.debughelper.tools.r8.utils.Pair<com.debughelper.tools.r8.ir.code.BasicBlock, com.debughelper.tools.r8.ir.code.BasicBlock.EdgeType>> stack = null;
    if (block.isSealed() && block.getPredecessors().size() == 1) {
      stack = new ArrayList<>(blocks.size());
      do {
        assert block.verifyFilledPredecessors();
        com.debughelper.tools.r8.ir.code.BasicBlock pred = block.getPredecessors().get(0);
        com.debughelper.tools.r8.ir.code.BasicBlock.EdgeType edgeType = pred.getEdgeType(block);
        checkRegister(register);
        value = pred.readCurrentDefinition(register, edgeType);
        if (value != null) {
          break;
        }
        stack.add(new com.debughelper.tools.r8.utils.Pair<>(block, readingEdge));
        block = pred;
        readingEdge = edgeType;
      } while (block.isSealed() && block.getPredecessors().size() == 1);
    }
    // If the register still has unknown value create a phi value for it.
    if (value == null) {
      if (!block.isSealed()) {
        assert !blocks.isEmpty() : "No write to " + register;
        com.debughelper.tools.r8.ir.code.Phi phi = new com.debughelper.tools.r8.ir.code.Phi(valueNumberGenerator.next(), block, type, local);
        block.addIncompletePhi(register, phi, readingEdge);
        value = phi;
      } else {
        com.debughelper.tools.r8.ir.code.Phi phi = new com.debughelper.tools.r8.ir.code.Phi(valueNumberGenerator.next(), block, type, local);
        // We need to write the phi before adding operands to break cycles. If the phi is trivial
        // and is removed by addOperands, the definition is overwritten and looked up again below.
        block.updateCurrentDefinition(register, phi, readingEdge);
        phi.addOperands(this, register);
        // Lookup the value for the register again at this point. Recursive trivial
        // phi removal could have simplified what we wanted to return here.
        value = block.readCurrentDefinition(register, readingEdge);
      }
    }
    // If the stack of successors is non-empty then update their definitions with the value.
    if (stack != null) {
      for (Pair<com.debughelper.tools.r8.ir.code.BasicBlock, com.debughelper.tools.r8.ir.code.BasicBlock.EdgeType> item : stack) {
        item.getFirst().updateCurrentDefinition(register, value, item.getSecond());
      }
    }
    // Update the last block at which the definition was found/created.
    block.updateCurrentDefinition(register, value, readingEdge);
    return value;
  }

  public com.debughelper.tools.r8.ir.code.Value readNumericRegister(int register, com.debughelper.tools.r8.ir.code.NumericType type) {
    return readRegister(register, com.debughelper.tools.r8.ir.code.ValueType.fromNumericType(type));
  }

  public com.debughelper.tools.r8.ir.code.Value readLiteral(com.debughelper.tools.r8.ir.code.ValueType type, long constant) {
    if (type == com.debughelper.tools.r8.ir.code.ValueType.INT) {
      return readIntLiteral(constant);
    } else {
      assert type == com.debughelper.tools.r8.ir.code.ValueType.LONG;
      return readLongLiteral(constant);
    }
  }

  public com.debughelper.tools.r8.ir.code.Value readLongLiteral(long constant) {
    com.debughelper.tools.r8.ir.code.Value value = new com.debughelper.tools.r8.ir.code.Value(valueNumberGenerator.next(), com.debughelper.tools.r8.ir.code.ValueType.LONG, null);
    com.debughelper.tools.r8.ir.code.ConstNumber number = new com.debughelper.tools.r8.ir.code.ConstNumber(value, constant);
    add(number);
    return number.outValue();
  }

  public com.debughelper.tools.r8.ir.code.Value readIntLiteral(long constant) {
    com.debughelper.tools.r8.ir.code.Value value = new com.debughelper.tools.r8.ir.code.Value(valueNumberGenerator.next(), com.debughelper.tools.r8.ir.code.ValueType.INT, null);
    com.debughelper.tools.r8.ir.code.ConstNumber number = new ConstNumber(value, constant);
    add(number);
    return number.outValue();
  }

  // This special write register is needed when changing the scoping of a local variable.
  // See addDebugLocalStart and addDebugLocalEnd.
  private com.debughelper.tools.r8.ir.code.Value writeRegister(
          int register, com.debughelper.tools.r8.ir.code.ValueType type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo throwing, com.debughelper.tools.r8.graph.DebugLocalInfo local) {
    checkRegister(register);
    com.debughelper.tools.r8.ir.code.Value value = new com.debughelper.tools.r8.ir.code.Value(valueNumberGenerator.next(), type, local);
    currentBlock.writeCurrentDefinition(register, value, throwing);
    return value;
  }

  public com.debughelper.tools.r8.ir.code.Value writeRegister(int register, com.debughelper.tools.r8.ir.code.ValueType type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo throwing) {
    com.debughelper.tools.r8.graph.DebugLocalInfo incomingLocal = getIncomingLocal(register);
    com.debughelper.tools.r8.graph.DebugLocalInfo outgoingLocal = getOutgoingLocal(register);
    // If the local info does not change at the current instruction, we need to ensure
    // that the old value is read at the instruction by setting 'previousLocalValue'.
    // If the local info changes, then there must be both an old local ending
    // and a new local starting at the current instruction, and it is up to the SourceCode
    // to ensure that the old local is read when it ends.
    // Furthermore, if incomingLocal != outgoingLocal, then we cannot be sure that
    // the type of the incomingLocal is the same as the type of the outgoingLocal,
    // and we must not call readRegisterIgnoreLocal() with the wrong type.
    previousLocalValue =
        (incomingLocal == null || incomingLocal != outgoingLocal)
            ? null
            : readRegisterIgnoreLocal(register, type);
    return writeRegister(register, type, throwing, outgoingLocal);
  }

  public com.debughelper.tools.r8.ir.code.Value writeNumericRegister(int register, com.debughelper.tools.r8.ir.code.NumericType type, com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo throwing) {
    return writeRegister(register, ValueType.fromNumericType(type), throwing);
  }

  private com.debughelper.tools.r8.graph.DebugLocalInfo getIncomingLocal(int register) {
    return options.debug ? source.getIncomingLocal(register) : null;
  }

  private DebugLocalInfo getOutgoingLocal(int register) {
    return options.debug ? source.getOutgoingLocal(register) : null;
  }

  private void checkRegister(int register) {
    if (register < 0) {
      throw new InternalCompilerError("Invalid register");
    }
    if (!source.verifyRegister(register)) {
      throw new com.debughelper.tools.r8.errors.CompilationError("Invalid use of register " + register);
    }
  }

  /**
   * Ensure that the current block can hold a throwing instruction. This will create a new current
   * block if the current block has handlers and already has one throwing instruction.
   */
  void ensureBlockForThrowingInstruction() {
    if (!throwingInstructionInCurrentBlock) {
      return;
    }
    com.debughelper.tools.r8.ir.code.BasicBlock block = new com.debughelper.tools.r8.ir.code.BasicBlock();
    block.setNumber(nextBlockNumber++);
    blocks.add(block);
    block.incrementUnfilledPredecessorCount();
    int freshOffset = INITIAL_BLOCK_OFFSET - 1;
    while (targets.containsKey(freshOffset)) {
      freshOffset--;
    }
    targets.put(freshOffset, null);
    for (int offset : source.getCurrentCatchHandlers().getUniqueTargets()) {
      BlockInfo target = targets.get(offset);
      assert !target.block.isSealed();
      target.block.incrementUnfilledPredecessorCount();
      target.addExceptionalPredecessor(freshOffset);
    }
    addInstruction(new com.debughelper.tools.r8.ir.code.Goto());
    currentBlock.link(block);
    closeCurrentBlock();
    setCurrentBlock(block);
  }

  // Private instruction helpers.
  private void addInstruction(com.debughelper.tools.r8.ir.code.Instruction ir) {
    addInstruction(ir, source.getCurrentPosition());
  }

  private void addInstruction(com.debughelper.tools.r8.ir.code.Instruction ir, Position position) {
    hasImpreciseInstructionOutValueTypes |= ir.outValue() != null && !ir.outType().isPreciseType();
    ir.setPosition(position);
    attachLocalValues(ir);
    currentBlock.add(ir);
    if (ir.instructionTypeCanThrow()) {
      assert source.verifyCurrentInstructionCanThrow();
      CatchHandlers<Integer> catchHandlers = source.getCurrentCatchHandlers();
      if (catchHandlers != null) {
        assert !throwingInstructionInCurrentBlock;
        throwingInstructionInCurrentBlock = true;
        List<com.debughelper.tools.r8.ir.code.BasicBlock> targets = new ArrayList<>(catchHandlers.getAllTargets().size());
        int moveExceptionDest = source.getMoveExceptionRegister();
        if (moveExceptionDest < 0) {
          for (int targetOffset : catchHandlers.getAllTargets()) {
            com.debughelper.tools.r8.ir.code.BasicBlock target = getTarget(targetOffset);
            addToWorklist(target, source.instructionIndex(targetOffset));
            targets.add(target);
          }
        } else {
          // If there is a well-defined move-exception destination register (eg, compiling from
          // Java-bytecode) then we construct move-exception header blocks for each unique target.
          Map<com.debughelper.tools.r8.ir.code.BasicBlock, com.debughelper.tools.r8.ir.code.BasicBlock> moveExceptionHeaders =
              new IdentityHashMap<>(catchHandlers.getUniqueTargets().size());
          for (int targetOffset : catchHandlers.getAllTargets()) {
            com.debughelper.tools.r8.ir.code.BasicBlock target = getTarget(targetOffset);
            com.debughelper.tools.r8.ir.code.BasicBlock header = moveExceptionHeaders.get(target);
            if (header == null) {
              header = new com.debughelper.tools.r8.ir.code.BasicBlock();
              header.incrementUnfilledPredecessorCount();
              moveExceptionHeaders.put(target, header);
              ssaWorklist.add(new MoveExceptionWorklistItem(header, targetOffset));
            }
            targets.add(header);
          }
        }
        currentBlock.linkCatchSuccessors(catchHandlers.getGuards(), targets);
      }
    }
  }

  private void attachLocalValues(Instruction ir) {
    if (!options.debug) {
      assert previousLocalValue == null;
      assert debugLocalReads.isEmpty();
      return;
    }
    // Add a use if this instruction is overwriting a previous value of the same local.
    if (previousLocalValue != null && previousLocalValue.getLocalInfo() == ir.getLocalInfo()) {
      assert ir.outValue() != null;
      ir.addDebugValue(previousLocalValue);
    }
    // Add reads of locals if any are pending.
    for (Value value : debugLocalReads) {
      ir.addDebugValue(value);
    }
    previousLocalValue = null;
    debugLocalReads.clear();
  }

  // Package (ie, SourceCode accessed) helpers.

  // Ensure there is a block starting at offset.
  BlockInfo ensureBlockWithoutEnqueuing(int offset) {
    assert offset != INITIAL_BLOCK_OFFSET;
    BlockInfo info = targets.get(offset);
    if (info == null) {
      // If this is a processed instruction, the block split and it has a fall-through predecessor.
      if (offset >= 0 && isOffsetProcessed(offset)) {
        int blockStartOffset = getBlockStartOffset(offset);
        BlockInfo existing = targets.get(blockStartOffset);
        info = existing.split(blockStartOffset, offset, targets);
      } else {
        info = new BlockInfo();
      }
      targets.put(offset, info);
    }
    return info;
  }

  private int getBlockStartOffset(int offset) {
    if (targets.containsKey(offset)) {
      return offset;
    }
    return targets.headMap(offset).lastIntKey();
  }

  // Ensure there is a block starting at offset and add it to the work-list if it needs processing.
  private BlockInfo ensureBlock(int offset) {
    // We don't enqueue negative targets (these are special blocks, eg, an argument prelude).
    if (offset >= 0 && !isOffsetProcessed(offset)) {
      traceBlocksWorklist.add(offset);
    }
    return ensureBlockWithoutEnqueuing(offset);
  }

  private boolean isOffsetProcessed(int offset) {
    return isIndexProcessed(source.instructionIndex(offset));
  }

  private boolean isIndexProcessed(int index) {
    if (index < processedInstructions.length) {
      return processedInstructions[index];
    }
    ensureSubroutineProcessedInstructions();
    return processedSubroutineInstructions.contains(index);
  }

  private void markIndexProcessed(int index) {
    assert !isIndexProcessed(index);
    if (index < processedInstructions.length) {
      processedInstructions[index] = true;
      return;
    }
    ensureSubroutineProcessedInstructions();
    processedSubroutineInstructions.add(index);
  }

  private void ensureSubroutineProcessedInstructions() {
    if (processedSubroutineInstructions == null) {
      processedSubroutineInstructions = new HashSet<>();
    }
  }

  // Ensure there is a block at offset and add a predecessor to it.
  private void ensureSuccessorBlock(int sourceOffset, int targetOffset, boolean normal) {
    BlockInfo targetInfo = ensureBlock(targetOffset);
    int sourceStartOffset = getBlockStartOffset(sourceOffset);
    BlockInfo sourceInfo = targets.get(sourceStartOffset);
    if (normal) {
      sourceInfo.addNormalSuccessor(targetOffset);
      targetInfo.addNormalPredecessor(sourceStartOffset);
    } else {
      sourceInfo.addExceptionalSuccessor(targetOffset);
      targetInfo.addExceptionalPredecessor(sourceStartOffset);
    }
    targetInfo.block.incrementUnfilledPredecessorCount();
  }

  public void ensureNormalSuccessorBlock(int sourceOffset, int targetOffset) {
    ensureSuccessorBlock(sourceOffset, targetOffset, true);
  }

  void ensureExceptionalSuccessorBlock(int sourceOffset, int targetOffset) {
    ensureSuccessorBlock(sourceOffset, targetOffset, false);
  }

  // Private block helpers.

  private com.debughelper.tools.r8.ir.code.BasicBlock getTarget(int offset) {
    return targets.get(offset).block;
  }

  private void closeCurrentBlock() {
    // TODO(zerny): To ensure liveness of locals throughout the entire block, we might want to
    // insert reads before closing the block. It is unclear if we can rely on a local-end to ensure
    // liveness in all blocks where the local should be live.
    assert currentBlock != null;
    currentBlock.close(this);
    setCurrentBlock(null);
    throwingInstructionInCurrentBlock = false;
  }

  private void closeCurrentBlockWithFallThrough(com.debughelper.tools.r8.ir.code.BasicBlock nextBlock) {
    assert currentBlock != null;
    addInstruction(new Goto());
    if (currentBlock.hasCatchSuccessor(nextBlock)) {
      needGotoToCatchBlocks.add(new com.debughelper.tools.r8.ir.code.BasicBlock.Pair(currentBlock, nextBlock));
    } else {
      currentBlock.link(nextBlock);
    }
    closeCurrentBlock();
  }

  private void handleFallthroughToCatchBlock() {
    // When a catch handler for a block goes to the same block as the fallthrough for that
    // block the graph only has one edge there. In these cases we add an additional block so the
    // catch edge goes through that and then make the fallthrough go through a new direct edge.
    for (com.debughelper.tools.r8.ir.code.BasicBlock.Pair pair : needGotoToCatchBlocks) {
      com.debughelper.tools.r8.ir.code.BasicBlock source = pair.first;
      com.debughelper.tools.r8.ir.code.BasicBlock target = pair.second;

      // New block with one unfilled predecessor.
      com.debughelper.tools.r8.ir.code.BasicBlock newBlock = com.debughelper.tools.r8.ir.code.BasicBlock.createGotoBlock(nextBlockNumber++, target);
      blocks.add(newBlock);
      newBlock.incrementUnfilledPredecessorCount();

      // Link blocks.
      source.replaceSuccessor(target, newBlock);
      newBlock.getPredecessors().add(source);
      source.getSuccessors().add(target);
      target.getPredecessors().add(newBlock);

      // Check that the successor indexes are correct.
      assert source.hasCatchSuccessor(newBlock);
      assert !source.hasCatchSuccessor(target);

      // Mark the filled predecessors to the blocks.
      if (source.isFilled()) {
        newBlock.filledPredecessor(this);
      }
      target.filledPredecessor(this);
    }
  }

  /**
   * Change to control-flow graph to avoid repeated phi operands when all the same values
   * flow in from multiple predecessors.
   *
   * <p> As an example:
   *
   * <pre>
   *
   *              b1          b2         b3
   *              |                       |
   *              ----------\ | /----------
   *
   *                         b4
   *                  v3 = phi(v1, v1, v2)
   * </pre>
   *
   * <p> Is rewritten to:
   *
   * <pre>
   *              b1          b2         b3
   *                  \    /             /
   *                    b5        -------
   *                        \    /
   *                          b4
   *                  v3 = phi(v1, v2)
   *
   * </pre>
   */
  public void joinPredecessorsWithIdenticalPhis() {
    List<com.debughelper.tools.r8.ir.code.BasicBlock> blocksToAdd = new ArrayList<>();
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
      // Consistency check. At this point there should be no incomplete phis.
      // If there are, the input is typically dex code that uses a register
      // that is not defined on all control-flow paths.
      if (block.hasIncompletePhis()) {
        throw new CompilationError(
            "Undefined value encountered during compilation. "
                + "This is typically caused by invalid dex input that uses a register "
                + "that is not define on all control-flow paths leading to the use.");
      }
      if (block.entry() instanceof MoveException) {
        // TODO: Should we support joining in the presence of move-exception instructions?
        continue;
      }
      List<Integer> operandsToRemove = new ArrayList<>();
      Map<ValueList, Integer> values = new HashMap<>();
      Map<Integer, com.debughelper.tools.r8.ir.code.BasicBlock> joinBlocks = new HashMap<>();
      if (block.getPhis().size() > 0) {
        Phi phi = block.getPhis().get(0);
        for (int operandIndex = 0; operandIndex < phi.getOperands().size(); operandIndex++) {
          ValueList v = ValueList.fromPhis(block.getPhis(), operandIndex);
          com.debughelper.tools.r8.ir.code.BasicBlock predecessor = block.getPredecessors().get(operandIndex);
          if (values.containsKey(v)) {
            // Seen before, create a join block (or reuse an existing join block) to join through.
            int otherPredecessorIndex = values.get(v);
            com.debughelper.tools.r8.ir.code.BasicBlock joinBlock = joinBlocks.get(otherPredecessorIndex);
            if (joinBlock == null) {
              joinBlock = com.debughelper.tools.r8.ir.code.BasicBlock.createGotoBlock(blocks.size() + blocksToAdd.size(), block);
              joinBlocks.put(otherPredecessorIndex, joinBlock);
              blocksToAdd.add(joinBlock);
              com.debughelper.tools.r8.ir.code.BasicBlock otherPredecessor = block.getPredecessors().get(otherPredecessorIndex);
              joinBlock.getPredecessors().add(otherPredecessor);
              otherPredecessor.replaceSuccessor(block, joinBlock);
              block.getPredecessors().set(otherPredecessorIndex, joinBlock);
            }
            joinBlock.getPredecessors().add(predecessor);
            predecessor.replaceSuccessor(block, joinBlock);
            operandsToRemove.add(operandIndex);
          } else {
            // Record the value and its predecessor index.
            values.put(v, operandIndex);
          }
        }
      }
      block.removePredecessorsByIndex(operandsToRemove);
      block.removePhisByIndex(operandsToRemove);
    }
    blocks.addAll(blocksToAdd);
  }

  // Other stuff.

  boolean isIntegerType(com.debughelper.tools.r8.ir.code.NumericType type) {
    return type != com.debughelper.tools.r8.ir.code.NumericType.FLOAT && type != com.debughelper.tools.r8.ir.code.NumericType.DOUBLE;
  }

  boolean isNonLongIntegerType(com.debughelper.tools.r8.ir.code.NumericType type) {
    return type != com.debughelper.tools.r8.ir.code.NumericType.FLOAT && type != com.debughelper.tools.r8.ir.code.NumericType.DOUBLE && type != NumericType.LONG;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append(("blocks:\n"));
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
      builder.append(block.toDetailedString());
      builder.append("\n");
    }
    return builder.toString();
  }
}

