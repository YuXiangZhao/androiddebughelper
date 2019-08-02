// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility;
import com.debughelper.tools.r8.graph.DexValue.DexValueBoolean;
import com.debughelper.tools.r8.graph.DexValue.DexValueByte;
import com.debughelper.tools.r8.graph.DexValue.DexValueChar;
import com.debughelper.tools.r8.graph.DexValue.DexValueDouble;
import com.debughelper.tools.r8.graph.DexValue.DexValueFloat;
import com.debughelper.tools.r8.graph.DexValue.DexValueInt;
import com.debughelper.tools.r8.graph.DexValue.DexValueLong;
import com.debughelper.tools.r8.graph.DexValue.DexValueNull;
import com.debughelper.tools.r8.graph.DexValue.DexValueShort;
import com.debughelper.tools.r8.graph.DexValue.DexValueString;
import com.debughelper.tools.r8.ir.analysis.type.TypeEnvironment;
import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.ir.code.AlwaysMaterializingNop;
import com.debughelper.tools.r8.ir.code.Cmp;
import com.debughelper.tools.r8.ir.code.Cmp.Bias;
import com.debughelper.tools.r8.ir.code.If;
import com.debughelper.tools.r8.ir.code.If.Type;
import com.debughelper.tools.r8.ir.optimize.SwitchUtils;
import com.debughelper.tools.r8.ir.optimize.SwitchUtils.EnumSwitchInfo;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.ir.code.AlwaysMaterializingNop;
import com.debughelper.tools.r8.ir.code.ArrayPut;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.Binop;
import com.debughelper.tools.r8.ir.code.CatchHandlers;
import com.debughelper.tools.r8.ir.code.CheckCast;
import com.debughelper.tools.r8.ir.code.ConstInstruction;
import com.debughelper.tools.r8.ir.code.ConstNumber;
import com.debughelper.tools.r8.ir.code.ConstString;
import com.debughelper.tools.r8.ir.code.DebugLocalWrite;
import com.debughelper.tools.r8.ir.code.DominatorTree;
import com.debughelper.tools.r8.ir.code.Goto;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionIterator;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.code.InvokeDirect;
import com.debughelper.tools.r8.ir.code.InvokeMethod;
import com.debughelper.tools.r8.ir.code.InvokeNewArray;
import com.debughelper.tools.r8.ir.code.InvokeStatic;
import com.debughelper.tools.r8.ir.code.InvokeVirtual;
import com.debughelper.tools.r8.ir.code.MemberType;
import com.debughelper.tools.r8.ir.code.NewArrayEmpty;
import com.debughelper.tools.r8.ir.code.NewArrayFilledData;
import com.debughelper.tools.r8.ir.code.NewInstance;
import com.debughelper.tools.r8.ir.code.NumericType;
import com.debughelper.tools.r8.ir.code.Phi;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.Return;
import com.debughelper.tools.r8.ir.code.StaticGet;
import com.debughelper.tools.r8.ir.code.StaticPut;
import com.debughelper.tools.r8.ir.code.Switch;
import com.debughelper.tools.r8.ir.code.Throw;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.code.Xor;
import com.debughelper.tools.r8.ir.conversion.OptimizationFeedback;
import com.debughelper.tools.r8.shaking.Enqueuer;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.LongInterval;
import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public class CodeRewriter {

  private static final int MAX_FILL_ARRAY_SIZE = 8 * Constants.KILOBYTE;
  // This constant was determined by experimentation.
  private static final int STOP_SHARED_CONSTANT_THRESHOLD = 50;
  private static final int SELF_RECURSION_LIMIT = 4;

  private final com.debughelper.tools.r8.graph.AppInfo appInfo;
  private final com.debughelper.tools.r8.graph.DexItemFactory dexItemFactory;
  private final Set<com.debughelper.tools.r8.graph.DexMethod> libraryMethodsReturningReceiver;
  private final com.debughelper.tools.r8.utils.InternalOptions options;

  // For some optimizations, e.g. optimizing synthetic classes, we may need to resolve
  // the current class being optimized. Since all methods of this class are optimized
  // together and are not concurrent to other optimizations, we just store current
  // synthetic class.
  private com.debughelper.tools.r8.graph.DexProgramClass cachedClass = null;

  public CodeRewriter(
          com.debughelper.tools.r8.graph.AppInfo appInfo, Set<com.debughelper.tools.r8.graph.DexMethod> libraryMethodsReturningReceiver, com.debughelper.tools.r8.utils.InternalOptions options) {
    this.appInfo = appInfo;
    this.options = options;
    this.dexItemFactory = appInfo.dexItemFactory;
    this.libraryMethodsReturningReceiver = libraryMethodsReturningReceiver;
  }

  private static boolean removedTrivialGotos(com.debughelper.tools.r8.ir.code.IRCode code) {
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> iterator = code.listIterator();
    assert iterator.hasNext();
    com.debughelper.tools.r8.ir.code.BasicBlock block = iterator.next();
    com.debughelper.tools.r8.ir.code.BasicBlock nextBlock;
    do {
      nextBlock = iterator.hasNext() ? iterator.next() : null;
      // Trivial goto block are only kept if they are self-targeting or are targeted by
      // fallthroughs.
      com.debughelper.tools.r8.ir.code.BasicBlock blk = block;  // Additional local for lambda below.
      assert !block.isTrivialGoto()
          || block.exit().asGoto().getTarget() == block
          || code.blocks.get(0) == block
          || block.getPredecessors().stream().anyMatch((b) -> b.exit().fallthroughBlock() == blk);
      // Trivial goto blocks never target the next block (in that case there should just be a
      // fallthrough).
      assert !block.isTrivialGoto() || block.exit().asGoto().getTarget() != nextBlock;
      block = nextBlock;
    } while (block != null);
    return true;
  }

  private static boolean isFallthroughBlock(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    for (com.debughelper.tools.r8.ir.code.BasicBlock pred : block.getPredecessors()) {
      if (pred.exit().fallthroughBlock() == block) {
        return true;
      }
    }
    return false;
  }

  private static void collapsTrivialGoto(
          com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.BasicBlock block, com.debughelper.tools.r8.ir.code.BasicBlock nextBlock, List<com.debughelper.tools.r8.ir.code.BasicBlock> blocksToRemove) {

    // This is the base case for GOTO loops.
    if (block.exit().asGoto().getTarget() == block) {
      return;
    }

    com.debughelper.tools.r8.ir.code.BasicBlock target = block.endOfGotoChain();

    boolean needed = false;

    if (target == null) {
      // This implies we are in a loop of GOTOs. In that case, we will iteratively remove each
      // trivial GOTO one-by-one until the above base case (one block targeting itself) is left.
      target = block.exit().asGoto().getTarget();
    }

    if (target != nextBlock) {
      // Not targeting the fallthrough block, determine if we need this goto. We need it if
      // a fallthrough can hit this block. That is the case if the block is the entry block
      // or if one of the predecessors fall through to the block.
      needed = code.blocks.get(0) == block || isFallthroughBlock(block);
    }

    if (!needed) {
      blocksToRemove.add(block);
      for (com.debughelper.tools.r8.ir.code.BasicBlock pred : block.getPredecessors()) {
        pred.replaceSuccessor(block, target);
      }
      for (com.debughelper.tools.r8.ir.code.BasicBlock succ : block.getSuccessors()) {
        succ.getPredecessors().remove(block);
      }
      for (com.debughelper.tools.r8.ir.code.BasicBlock pred : block.getPredecessors()) {
        if (!target.getPredecessors().contains(pred)) {
          target.getPredecessors().add(pred);
        }
      }
    }
  }

  private static void collapsIfTrueTarget(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    com.debughelper.tools.r8.ir.code.If insn = block.exit().asIf();
    com.debughelper.tools.r8.ir.code.BasicBlock target = insn.getTrueTarget();
    com.debughelper.tools.r8.ir.code.BasicBlock newTarget = target.endOfGotoChain();
    com.debughelper.tools.r8.ir.code.BasicBlock fallthrough = insn.fallthroughBlock();
    com.debughelper.tools.r8.ir.code.BasicBlock newFallthrough = fallthrough.endOfGotoChain();
    if (newTarget != null && target != newTarget) {
      insn.getBlock().replaceSuccessor(target, newTarget);
      target.getPredecessors().remove(block);
      if (!newTarget.getPredecessors().contains(block)) {
        newTarget.getPredecessors().add(block);
      }
    }
    if (block.exit().isIf()) {
      insn = block.exit().asIf();
      if (insn.getTrueTarget() == newFallthrough) {
        // Replace if with the same true and fallthrough target with a goto to the fallthrough.
        block.replaceSuccessor(insn.getTrueTarget(), fallthrough);
        assert block.exit().isGoto();
        assert block.exit().asGoto().getTarget() == fallthrough;
      }
    }
  }

  private static void collapsNonFallthroughSwitchTargets(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    com.debughelper.tools.r8.ir.code.Switch insn = block.exit().asSwitch();
    com.debughelper.tools.r8.ir.code.BasicBlock fallthroughBlock = insn.fallthroughBlock();
    Set<com.debughelper.tools.r8.ir.code.BasicBlock> replacedBlocks = new HashSet<>();
    for (int j = 0; j < insn.targetBlockIndices().length; j++) {
      com.debughelper.tools.r8.ir.code.BasicBlock target = insn.targetBlock(j);
      if (target != fallthroughBlock) {
        com.debughelper.tools.r8.ir.code.BasicBlock newTarget = target.endOfGotoChain();
        if (newTarget != null && target != newTarget && !replacedBlocks.contains(target)) {
          insn.getBlock().replaceSuccessor(target, newTarget);
          target.getPredecessors().remove(block);
          if (!newTarget.getPredecessors().contains(block)) {
            newTarget.getPredecessors().add(block);
          }
          replacedBlocks.add(target);
        }
      }
    }
  }

  // For method with many self-recursive calls, insert a try-catch to disable inlining.
  // Marshmallow dex2oat aggressively inlines and eats up all the memory on devices.
  public static void disableDex2OatInliningForSelfRecursiveMethods(
          com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.utils.InternalOptions options) {
    if (!options.canHaveDex2OatInliningIssue() || code.hasCatchHandlers()) {
      // Catch handlers disables inlining, so if the method already has catch handlers
      // there is nothing to do.
      return;
    }
    com.debughelper.tools.r8.ir.code.InstructionIterator it = code.instructionIterator();
    int selfRecursionFanOut = 0;
    com.debughelper.tools.r8.ir.code.Instruction lastSelfRecursiveCall = null;
    while (it.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction i = it.next();
      if (i.isInvokeMethod() && i.asInvokeMethod().getInvokedMethod() == code.method.method) {
        selfRecursionFanOut++;
        lastSelfRecursiveCall = i;
      }
    }
    if (selfRecursionFanOut > SELF_RECURSION_LIMIT) {
      assert lastSelfRecursiveCall != null;
      // Split out the last recursive call in its own block.
      com.debughelper.tools.r8.ir.code.InstructionListIterator splitIterator =
          lastSelfRecursiveCall.getBlock().listIterator(lastSelfRecursiveCall);
      splitIterator.previous();
      com.debughelper.tools.r8.ir.code.BasicBlock newBlock = splitIterator.split(code, 1);
      // Generate rethrow block.
      com.debughelper.tools.r8.ir.code.BasicBlock rethrowBlock =
          com.debughelper.tools.r8.ir.code.BasicBlock.createRethrowBlock(code, lastSelfRecursiveCall.getPosition());
      code.blocks.add(rethrowBlock);
      // Add catch handler to the block containing the last recursive call.
      newBlock.addCatchHandler(rethrowBlock, options.itemFactory.throwableType);
    }
  }

  // TODO(sgjesse); Move this somewhere else, and reuse it for some of the other switch rewritings.
  public abstract static class InstructionBuilder<T> {
    protected int blockNumber;
    protected final com.debughelper.tools.r8.ir.code.Position position;

    protected InstructionBuilder(com.debughelper.tools.r8.ir.code.Position position) {
      this.position = position;
    }

    public abstract T self();

    public T setBlockNumber(int blockNumber) {
      this.blockNumber = blockNumber;
      return self();
    }
  }

  public static class SwitchBuilder extends InstructionBuilder<SwitchBuilder> {
    private com.debughelper.tools.r8.ir.code.Value value;
    private final Int2ObjectSortedMap<com.debughelper.tools.r8.ir.code.BasicBlock> keyToTarget = new Int2ObjectAVLTreeMap<>();
    private com.debughelper.tools.r8.ir.code.BasicBlock fallthrough;

    public SwitchBuilder(com.debughelper.tools.r8.ir.code.Position position) {
      super(position);
    }

    @Override
    public SwitchBuilder self() {
      return this;
    }

    public SwitchBuilder setValue(com.debughelper.tools.r8.ir.code.Value value) {
      this.value = value;
      return  this;
    }

    public SwitchBuilder addKeyAndTarget(int key, com.debughelper.tools.r8.ir.code.BasicBlock target) {
      keyToTarget.put(key, target);
      return this;
    }

    public SwitchBuilder setFallthrough(com.debughelper.tools.r8.ir.code.BasicBlock fallthrough) {
      this.fallthrough = fallthrough;
      return this;
    }

    public com.debughelper.tools.r8.ir.code.BasicBlock build() {
      final int NOT_FOUND = -1;
      Object2IntMap<com.debughelper.tools.r8.ir.code.BasicBlock> targetToSuccessorIndex = new Object2IntLinkedOpenHashMap<>();
      targetToSuccessorIndex.defaultReturnValue(NOT_FOUND);

      int[] keys = new int[keyToTarget.size()];
      int[] targetBlockIndices = new int[keyToTarget.size()];
      // Sort keys descending.
      int count = 0;
      IntIterator iter = keyToTarget.keySet().iterator();
      while (iter.hasNext()) {
        int key = iter.nextInt();
        com.debughelper.tools.r8.ir.code.BasicBlock target = keyToTarget.get(key);
        Integer targetIndex =
            targetToSuccessorIndex.computeIfAbsent(target, b -> targetToSuccessorIndex.size());
        keys[count] = key;
        targetBlockIndices[count] = targetIndex;
        count++;
      }
      Integer fallthroughIndex =
          targetToSuccessorIndex.computeIfAbsent(fallthrough, b -> targetToSuccessorIndex.size());
      com.debughelper.tools.r8.ir.code.Switch newSwitch = new com.debughelper.tools.r8.ir.code.Switch(value, keys, targetBlockIndices, fallthroughIndex);
      newSwitch.setPosition(position);
      com.debughelper.tools.r8.ir.code.BasicBlock newSwitchBlock = com.debughelper.tools.r8.ir.code.BasicBlock.createSwitchBlock(blockNumber, newSwitch);
      for (com.debughelper.tools.r8.ir.code.BasicBlock successor : targetToSuccessorIndex.keySet()) {
        newSwitchBlock.link(successor);
      }
      return newSwitchBlock;
    }
  }

  public static class IfBuilder extends InstructionBuilder<IfBuilder> {
    private final com.debughelper.tools.r8.ir.code.IRCode code;
    private com.debughelper.tools.r8.ir.code.Value left;
    private int right;
    private com.debughelper.tools.r8.ir.code.BasicBlock target;
    private com.debughelper.tools.r8.ir.code.BasicBlock fallthrough;

    public IfBuilder(com.debughelper.tools.r8.ir.code.Position position, com.debughelper.tools.r8.ir.code.IRCode code) {
      super(position);
      this.code = code;
    }

    @Override
    public IfBuilder self() {
      return this;
    }

    public IfBuilder setLeft(com.debughelper.tools.r8.ir.code.Value left) {
      this.left = left;
      return  this;
    }

    public IfBuilder setRight(int right) {
      this.right = right;
      return  this;
    }

    public IfBuilder setTarget(com.debughelper.tools.r8.ir.code.BasicBlock target) {
      this.target = target;
      return this;
    }

    public IfBuilder setFallthrough(com.debughelper.tools.r8.ir.code.BasicBlock fallthrough) {
      this.fallthrough = fallthrough;
      return this;
    }

    public com.debughelper.tools.r8.ir.code.BasicBlock build() {
      assert target != null;
      assert fallthrough != null;
      com.debughelper.tools.r8.ir.code.If newIf;
      com.debughelper.tools.r8.ir.code.BasicBlock ifBlock;
      if (right != 0) {
        com.debughelper.tools.r8.ir.code.ConstNumber rightConst = code.createIntConstant(right);
        rightConst.setPosition(position);
        newIf = new com.debughelper.tools.r8.ir.code.If(com.debughelper.tools.r8.ir.code.If.Type.EQ, ImmutableList.of(left, rightConst.dest()));
        ifBlock = com.debughelper.tools.r8.ir.code.BasicBlock.createIfBlock(blockNumber, newIf, rightConst);
      } else {
        newIf = new com.debughelper.tools.r8.ir.code.If(com.debughelper.tools.r8.ir.code.If.Type.EQ, left);
        ifBlock = com.debughelper.tools.r8.ir.code.BasicBlock.createIfBlock(blockNumber, newIf);
      }
      newIf.setPosition(position);
      ifBlock.link(target);
      ifBlock.link(fallthrough);
      return ifBlock;
    }
  }

  /**
   * Covert the switch instruction to a sequence of if instructions checking for a specified
   * set of keys, followed by a new switch with the remaining keys.
   */
  private void convertSwitchToSwitchAndIfs(
          com.debughelper.tools.r8.ir.code.IRCode code, ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocksIterator, com.debughelper.tools.r8.ir.code.BasicBlock originalBlock,
          com.debughelper.tools.r8.ir.code.InstructionListIterator iterator, com.debughelper.tools.r8.ir.code.Switch theSwitch,
          List<IntList> switches, IntList keysToRemove) {

    com.debughelper.tools.r8.ir.code.Position position = theSwitch.getPosition();

    // Extract the information from the switch before removing it.
    Int2ReferenceSortedMap<com.debughelper.tools.r8.ir.code.BasicBlock> keyToTarget = theSwitch.getKeyToTargetMap();

    // Keep track of the current fallthrough, starting with the original.
    com.debughelper.tools.r8.ir.code.BasicBlock fallthroughBlock = theSwitch.fallthroughBlock();

    // Split the switch instruction into its own block and remove it.
    iterator.previous();
    com.debughelper.tools.r8.ir.code.BasicBlock originalSwitchBlock = iterator.split(code, blocksIterator);
    assert !originalSwitchBlock.hasCatchHandlers();
    assert originalSwitchBlock.getInstructions().size() == 1;
    assert originalBlock.exit().isGoto();
    theSwitch.moveDebugValues(originalBlock.exit());
    blocksIterator.remove();
    theSwitch.getBlock().detachAllSuccessors();
    com.debughelper.tools.r8.ir.code.BasicBlock block = theSwitch.getBlock().unlinkSinglePredecessor();
    assert theSwitch.getBlock().getPredecessors().size() == 0;
    assert theSwitch.getBlock().getSuccessors().size() == 0;
    assert block == originalBlock;

    // Collect the new blocks for adding to the block list.
    int nextBlockNumber = code.getHighestBlockNumber() + 1;
    LinkedList<com.debughelper.tools.r8.ir.code.BasicBlock> newBlocks = new LinkedList<>();

    // Build the switch-blocks backwards, to always have the fallthrough block in hand.
    for (int i = switches.size() - 1; i >= 0; i--) {
      SwitchBuilder switchBuilder = new SwitchBuilder(position);
      switchBuilder.setValue(theSwitch.value());
      IntList keys = switches.get(i);
      for (int j = 0; j < keys.size(); j++) {
        int key = keys.getInt(j);
        switchBuilder.addKeyAndTarget(key, keyToTarget.get(key));
      }
      switchBuilder
          .setFallthrough(fallthroughBlock)
          .setBlockNumber(nextBlockNumber++);
      com.debughelper.tools.r8.ir.code.BasicBlock newSwitchBlock = switchBuilder.build();
      newBlocks.addFirst(newSwitchBlock);
      fallthroughBlock = newSwitchBlock;
    }

    // Build the if-blocks backwards, to always have the fallthrough block in hand.
    for (int i = keysToRemove.size() - 1; i >= 0; i--) {
      int key = keysToRemove.getInt(i);
      com.debughelper.tools.r8.ir.code.BasicBlock peeledOffTarget = keyToTarget.get(key);
      IfBuilder ifBuilder = new IfBuilder(position, code);
      ifBuilder
          .setLeft(theSwitch.value())
          .setRight(key)
          .setTarget(peeledOffTarget)
          .setFallthrough(fallthroughBlock)
          .setBlockNumber(nextBlockNumber++);
      com.debughelper.tools.r8.ir.code.BasicBlock ifBlock = ifBuilder.build();
      newBlocks.addFirst(ifBlock);
      fallthroughBlock = ifBlock;
    }

    // Finally link the block before the original switch to the new block sequence.
    originalBlock.link(fallthroughBlock);

    // Finally add the blocks.
    newBlocks.forEach(blocksIterator::add);
  }

  public void rewriteSwitch(com.debughelper.tools.r8.ir.code.IRCode code) {
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocksIterator = code.listIterator();
    while (blocksIterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = blocksIterator.next();
      com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = iterator.next();
        if (instruction.isSwitch()) {
          com.debughelper.tools.r8.ir.code.Switch theSwitch = instruction.asSwitch();
          if (theSwitch.numberOfKeys() == 1) {
            // Rewrite the switch to an if.
            int fallthroughBlockIndex = theSwitch.getFallthroughBlockIndex();
            int caseBlockIndex = theSwitch.targetBlockIndices()[0];
            if (fallthroughBlockIndex < caseBlockIndex) {
              block.swapSuccessorsByIndex(fallthroughBlockIndex, caseBlockIndex);
            }
            if (theSwitch.getFirstKey() == 0) {
              iterator.replaceCurrentInstruction(new com.debughelper.tools.r8.ir.code.If(com.debughelper.tools.r8.ir.code.If.Type.EQ, theSwitch.value()));
            } else {
              com.debughelper.tools.r8.ir.code.ConstNumber labelConst = code.createIntConstant(theSwitch.getFirstKey());
              labelConst.setPosition(theSwitch.getPosition());
              iterator.previous();
              iterator.add(labelConst);
              com.debughelper.tools.r8.ir.code.Instruction dummy = iterator.next();
              assert dummy == theSwitch;
              com.debughelper.tools.r8.ir.code.If theIf = new com.debughelper.tools.r8.ir.code.If(com.debughelper.tools.r8.ir.code.If.Type.EQ, ImmutableList.of(theSwitch.value(), labelConst.dest()));
              iterator.replaceCurrentInstruction(theIf);
            }
          } else {
            // Split keys into outliers and sequences.
            List<IntList> sequences = new ArrayList<>();
            IntList outliers = new IntArrayList();

            IntList current = new IntArrayList();
            int[] keys = theSwitch.getKeys();
            int previousKey = keys[0];
            current.add(previousKey);
            for (int i = 1; i < keys.length; i++) {
              assert current.size() > 0;
              assert current.getInt(current.size() - 1) == previousKey;
              int key = keys[i];
              if (((long) key - (long) previousKey) > 1) {
                if (current.size() == 1) {
                  outliers.add(previousKey);
                } else {
                  sequences.add(current);
                }
                current = new IntArrayList();
              }
              current.add(key);
              previousKey = key;
            }
            if (current.size() == 1) {
              outliers.add(previousKey);
            } else {
              sequences.add(current);
            }

            // Get the existing dex size for the payload and switch.
            int currentSize = com.debughelper.tools.r8.ir.code.Switch.payloadSize(keys) + com.debughelper.tools.r8.ir.code.Switch.estimatedDexSize();

            // Never replace with more than 10 if/switch instructions.
            if (outliers.size() + sequences.size() <= 10) {
              // Calculate estimated size for splitting into ifs and switches.
              long rewrittenSize = 0;
              for (Integer outlier : outliers) {
                if (outlier != 0) {
                  rewrittenSize += com.debughelper.tools.r8.ir.code.ConstNumber.estimatedDexSize(
                      theSwitch.value().outType(), outlier);
                }
                rewrittenSize += com.debughelper.tools.r8.ir.code.If.estimatedDexSize();
              }
              for (List<Integer> sequence : sequences) {
                rewrittenSize += com.debughelper.tools.r8.ir.code.Switch.payloadSize(sequence);
              }
              rewrittenSize += com.debughelper.tools.r8.ir.code.Switch.estimatedDexSize() * sequences.size();

              if (rewrittenSize < currentSize) {
                convertSwitchToSwitchAndIfs(
                    code, blocksIterator, block, iterator, theSwitch, sequences, outliers);
              }
            } else if (outliers.size() > 1) {
              // Calculate estimated size for splitting into switches (packed for the sequences
              // and sparse for the outliers).
              long rewrittenSize = 0;
              for (List<Integer> sequence : sequences) {
                rewrittenSize += com.debughelper.tools.r8.ir.code.Switch.payloadSize(sequence);
              }
              rewrittenSize += com.debughelper.tools.r8.ir.code.Switch.payloadSize(outliers);
              rewrittenSize += com.debughelper.tools.r8.ir.code.Switch.estimatedDexSize() * (sequences.size() + 1);

              if (rewrittenSize < currentSize) {
                // Create a copy to not modify sequences.
                List<IntList> seqs = new ArrayList<>(sequences);
                seqs.add(outliers);
                convertSwitchToSwitchAndIfs(
                    code, blocksIterator, block, iterator, theSwitch, seqs, IntLists.EMPTY_LIST);
              }
            }
          }
        }
      }
    }
    // Rewriting of switches introduces new branching structure. It relies on critical edges
    // being split on the way in but does not maintain this property. We therefore split
    // critical edges at exit.
    code.splitCriticalEdges();
    assert code.isConsistentSSA();
  }

  /**
   * Inline the indirection of switch maps into the switch statement.
   * <p>
   * To ensure binary compatibility, javac generated code does not use ordinal values of enums
   * directly in switch statements but instead generates a companion class that computes a mapping
   * from switch branches to ordinals at runtime. As we have whole-program knowledge, we can
   * analyze these maps and inline the indirection into the switch map again.
   * <p>
   * In particular, we look for code of the form
   *
   * <blockquote><pre>
   * switch(CompanionClass.$switchmap$field[enumValue.ordinal()]) {
   *   ...
   * }
   * </pre></blockquote>
   */
  public void removeSwitchMaps(com.debughelper.tools.r8.ir.code.IRCode code) {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction insn = it.next();
        // Pattern match a switch on a switch map as input.
        if (insn.isSwitch()) {
          com.debughelper.tools.r8.ir.code.Switch switchInsn = insn.asSwitch();
          EnumSwitchInfo info = SwitchUtils
              .analyzeSwitchOverEnum(switchInsn, appInfo.withLiveness());
          if (info != null) {
            Int2IntMap targetMap = new Int2IntArrayMap();
            IntList keys = new IntArrayList(switchInsn.numberOfKeys());
            for (int i = 0; i < switchInsn.numberOfKeys(); i++) {
              assert switchInsn.targetBlockIndices()[i] != switchInsn.getFallthroughBlockIndex();
              int key = info.ordinalsMap.getInt(info.indexMap.get(switchInsn.getKey(i)));
              keys.add(key);
              targetMap.put(key, switchInsn.targetBlockIndices()[i]);
            }
            keys.sort(Comparator.naturalOrder());
            int[] targets = new int[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
              targets[i] = targetMap.get(keys.getInt(i));
            }

            com.debughelper.tools.r8.ir.code.Switch newSwitch = new Switch(info.ordinalInvoke.outValue(), keys.toIntArray(),
                targets, switchInsn.getFallthroughBlockIndex());
            // Replace the switch itself.
            it.replaceCurrentInstruction(newSwitch);
            // If the original input to the switch is now unused, remove it too. It is not dead
            // as it might have side-effects but we ignore these here.
            com.debughelper.tools.r8.ir.code.Instruction arrayGet = info.arrayGet;
            if (arrayGet.outValue().numberOfUsers() == 0) {
              arrayGet.inValues().forEach(v -> v.removeUser(arrayGet));
              arrayGet.getBlock().removeInstruction(arrayGet);
            }
            com.debughelper.tools.r8.ir.code.Instruction staticGet = info.staticGet;
            if (staticGet.outValue().numberOfUsers() == 0) {
              assert staticGet.inValues().isEmpty();
              staticGet.getBlock().removeInstruction(staticGet);
            }
          }
        }
      }
    }
  }

  /**
   * Rewrite all branch targets to the destination of trivial goto chains when possible.
   * Does not rewrite fallthrough targets as that would require block reordering and the
   * transformation only makes sense after SSA destruction where there are no phis.
   */
  public static void collapsTrivialGotos(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code) {
    assert code.isConsistentGraph();
    List<com.debughelper.tools.r8.ir.code.BasicBlock> blocksToRemove = new ArrayList<>();
    // Rewrite all non-fallthrough targets to the end of trivial goto chains and remove
    // first round of trivial goto blocks.
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> iterator = code.listIterator();
    assert iterator.hasNext();
    com.debughelper.tools.r8.ir.code.BasicBlock block = iterator.next();
    com.debughelper.tools.r8.ir.code.BasicBlock nextBlock;

    // The marks will be used for cycle detection.
    do {
      nextBlock = iterator.hasNext() ? iterator.next() : null;
      if (block.isTrivialGoto()) {
        collapsTrivialGoto(code, block, nextBlock, blocksToRemove);
      }
      if (block.exit().isIf()) {
        collapsIfTrueTarget(block);
      }
      if (block.exit().isSwitch()) {
        collapsNonFallthroughSwitchTargets(block);
      }
      block = nextBlock;
    } while (nextBlock != null);
    code.removeBlocks(blocksToRemove);
    // Get rid of gotos to the next block.
    while (!blocksToRemove.isEmpty()) {
      blocksToRemove = new ArrayList<>();
      iterator = code.listIterator();
      block = iterator.next();
      do {
        nextBlock = iterator.hasNext() ? iterator.next() : null;
        if (block.isTrivialGoto()) {
          collapsTrivialGoto(code, block, nextBlock, blocksToRemove);
        }
        block = nextBlock;
      } while (block != null);
      code.removeBlocks(blocksToRemove);
    }
    assert removedTrivialGotos(code);
    assert code.isConsistentGraph();
  }

  public void identifyReturnsArgument(
          com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.conversion.OptimizationFeedback feedback) {
    List<com.debughelper.tools.r8.ir.code.BasicBlock> normalExits = code.computeNormalExitBlocks();
    if (normalExits.isEmpty()) {
      feedback.methodNeverReturnsNormally(method);
      return;
    }
    com.debughelper.tools.r8.ir.code.Return firstExit = normalExits.get(0).exit().asReturn();
    if (firstExit.isReturnVoid()) {
      return;
    }
    com.debughelper.tools.r8.ir.code.Value returnValue = firstExit.returnValue();
    boolean isNeverNull = returnValue.isNeverNull();
    for (int i = 1; i < normalExits.size(); i++) {
      Return exit = normalExits.get(i).exit().asReturn();
      com.debughelper.tools.r8.ir.code.Value value = exit.returnValue();
      if (value != returnValue) {
        returnValue = null;
      }
      isNeverNull = isNeverNull && value.isNeverNull();
    }
    if (returnValue != null) {
      if (returnValue.isArgument()) {
        // Find the argument number.
        int index = code.collectArguments().indexOf(returnValue);
        assert index != -1;
        feedback.methodReturnsArgument(method, index);
      }
      if (returnValue.isConstant() && returnValue.definition.isConstNumber()) {
        long value = returnValue.definition.asConstNumber().getRawValue();
        feedback.methodReturnsConstant(method, value);
      }
    }
    if (isNeverNull) {
      feedback.methodNeverReturnsNull(method);
    }
  }

  public void identifyInvokeSemanticsForInlining(
          com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.conversion.OptimizationFeedback feedback) {
    if (method.isStaticMethod()) {
      // Identifies if the method preserves class initialization after inlining.
      feedback.markTriggerClassInitBeforeAnySideEffect(method,
          triggersClassInitializationBeforeSideEffect(code, method.method.getHolder()));
    } else {
      // Identifies if the method preserves null check of the receiver after inlining.
      final com.debughelper.tools.r8.ir.code.Value receiver = code.getThis();
      feedback.markCheckNullReceiverBeforeAnySideEffect(method,
          receiver.isUsed() && checksNullReceiverBeforeSideEffect(code, receiver));
    }
  }

  public void identifyClassInlinerEligibility(
          com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.conversion.OptimizationFeedback feedback) {
    // Method eligibility is calculated in similar way for regular method
    // and for the constructor. To be eligible method should only be using its
    // receiver in the following ways:
    //
    //  (1) as a receiver of reads/writes of instance fields of the holder class
    //  (2) as a return value
    //  (3) as a receiver of a call to the superclass initializer
    //
    boolean instanceInitializer = method.isInstanceInitializer();
    if (method.accessFlags.isNative() ||
        (!method.isNonAbstractVirtualMethod() && !instanceInitializer)) {
      return;
    }

    feedback.setClassInlinerEligibility(method, null);  // To allow returns below.

    com.debughelper.tools.r8.ir.code.Value receiver = code.getThis();
    if (receiver.numberOfPhiUsers() > 0) {
      return;
    }

    boolean receiverUsedAsReturnValue = false;
    boolean seenSuperInitCall = false;
    for (com.debughelper.tools.r8.ir.code.Instruction insn : receiver.uniqueUsers()) {
      if (insn.isReturn()) {
        receiverUsedAsReturnValue = true;
        continue;
      }

      if (insn.isInstanceGet() ||
          (insn.isInstancePut() && insn.asInstancePut().object() == receiver)) {
        com.debughelper.tools.r8.graph.DexField field = insn.asFieldInstruction().getField();
        if (field.clazz == method.method.holder) {
          // Since class inliner currently only supports classes directly extending
          // java.lang.Object, we don't need to worry about fields defined in superclasses.
          continue;
        }
        return;
      }

      // If this is an instance initializer allow one call
      // to java.lang.Object.<init>() on 'this'.
      if (instanceInitializer && insn.isInvokeDirect()) {
        com.debughelper.tools.r8.ir.code.InvokeDirect invokedDirect = insn.asInvokeDirect();
        if (invokedDirect.getInvokedMethod() == dexItemFactory.objectMethods.constructor &&
            invokedDirect.getReceiver() == receiver &&
            !seenSuperInitCall) {
          seenSuperInitCall = true;
          continue;
        }
        return;
      }

      // Other receiver usages make the method not eligible.
      return;
    }

    if (instanceInitializer && !seenSuperInitCall) {
      // Call to super constructor not found?
      return;
    }

    feedback.setClassInlinerEligibility(
        method, new com.debughelper.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility(receiverUsedAsReturnValue));
  }

  /**
   * An enum used to classify instructions according to a particular effect that they produce.
   *
   * The "effect" of an instruction can be seen as a program state change (or semantic change) at
   * runtime execution. For example, an instruction could cause the initialization of a class,
   * change the value of a field, ... while other instructions do not.
   *
   * This classification also depends on the type of analysis that is using it. For instance, an
   * analysis can look for instructions that cause class initialization while another look for
   * instructions that check nullness of a particular object.
   *
   * On the other hand, some instructions may provide a non desired effect which is a signal for
   * the analysis to stop.
   */
  private enum InstructionEffect {
    DESIRED_EFFECT,
    OTHER_EFFECT,
    NO_EFFECT
  }

  /**
   * Returns true if the given code unconditionally throws if receiver is null before any other
   * side effect instruction.
   *
   * Note: we do not track phis so we may return false negative. This is a conservative approach.
   */
  private static boolean checksNullReceiverBeforeSideEffect(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.Value receiver) {
    return alwaysTriggerExpectedEffectBeforeAnythingElse(code, instr -> {
      if (instr.throwsNpeIfValueIsNull(receiver)) {
        // In order to preserve NPE semantic, the exception must not be caught by any handler.
        // Therefore, we must ignore this instruction if it is covered by a catch handler.
        // Note: this is a conservative approach where we consider that any catch handler could
        // catch the exception, even if it cannot catch a NullPointerException.
        if (!instr.getBlock().hasCatchHandlers()) {
          // We found a NPE check on receiver.
          return InstructionEffect.DESIRED_EFFECT;
        }
      } else if (instructionHasSideEffects(instr)) {
        // We found a side effect before a NPE check.
        return InstructionEffect.OTHER_EFFECT;
      }
      return InstructionEffect.NO_EFFECT;
    });
  }

  private static boolean instructionHasSideEffects(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    // We consider that an instruction has side effects if it can throw an exception. This is a
    // conservative approach which can be revised in the future.
    return instruction.instructionTypeCanThrow();
  }

  /**
   * Returns true if the given code unconditionally triggers class initialization before any other
   * side effecting instruction.
   *
   * Note: we do not track phis so we may return false negative. This is a conservative approach.
   */
  private static boolean triggersClassInitializationBeforeSideEffect(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.graph.DexType klass) {
    return alwaysTriggerExpectedEffectBeforeAnythingElse(code, instruction -> {
      if (instruction.triggersInitializationOfClass(klass)) {
        // In order to preserve class initialization semantic, the exception must not be caught by
        // any handler. Therefore, we must ignore this instruction if it is covered by a catch
        // handler.
        // Note: this is a conservative approach where we consider that any catch handler could
        // catch the exception, even if it cannot catch an ExceptionInInitializerError.
        if (!instruction.getBlock().hasCatchHandlers()) {
          // We found an instruction that preserves initialization of the class.
          return InstructionEffect.DESIRED_EFFECT;
        }
      } else if (instructionHasSideEffects(instruction)) {
        // We found a side effect before class initialization.
        return InstructionEffect.OTHER_EFFECT;
      }
      return InstructionEffect.NO_EFFECT;
    });
  }

  /**
   * Returns true if the given code unconditionally triggers an expected effect before anything
   * else, false otherwise.
   *
   * Note: we do not track phis so we may return false negative. This is a conservative approach.
   */
  private static boolean alwaysTriggerExpectedEffectBeforeAnythingElse(com.debughelper.tools.r8.ir.code.IRCode code,
                                                                       Function<com.debughelper.tools.r8.ir.code.Instruction, InstructionEffect> function) {
    final int color = code.reserveMarkingColor();
    try {
      ArrayDeque<com.debughelper.tools.r8.ir.code.BasicBlock> worklist = new ArrayDeque<>();
      final com.debughelper.tools.r8.ir.code.BasicBlock entry = code.blocks.getFirst();
      worklist.add(entry);
      entry.mark(color);

      while (!worklist.isEmpty()) {
        com.debughelper.tools.r8.ir.code.BasicBlock currentBlock = worklist.poll();
        assert currentBlock.isMarked(color);

        InstructionEffect result = InstructionEffect.NO_EFFECT;
        Iterator<com.debughelper.tools.r8.ir.code.Instruction> it = currentBlock.listIterator();
        while (result == InstructionEffect.NO_EFFECT && it.hasNext()) {
          result = function.apply(it.next());
        }
        if (result == InstructionEffect.OTHER_EFFECT) {
          // We found an instruction that is causing an unexpected side effect.
          return false;
        } else if (result == InstructionEffect.DESIRED_EFFECT) {
          // The current path is causing the expected effect. No need to go deeper in this path,
          // go to the next block in the work list.
          continue;
        } else {
          assert result == InstructionEffect.NO_EFFECT;
          // The block did not cause any particular effect.
          if (currentBlock.getNormalSuccessors().isEmpty()) {
            // This is the end of the current non-exceptional path and we did not find any expected
            // effect. It means there is at least one path where the expected effect does not
            // happen.
            com.debughelper.tools.r8.ir.code.Instruction lastInstruction = currentBlock.getInstructions().getLast();
            assert lastInstruction.isReturn() || lastInstruction.isThrow();
            return false;
          } else {
            // Look into successors
            for (com.debughelper.tools.r8.ir.code.BasicBlock successor : currentBlock.getSuccessors()) {
              if (!successor.isMarked(color)) {
                worklist.add(successor);
                successor.mark(color);
              }
            }
          }
        }
      }

      // If we reach this point, we checked that the expected effect happens in every possible path.
      return true;
    } finally {
      code.returnMarkingColor(color);
    }
  }

  private boolean checkArgumentType(com.debughelper.tools.r8.ir.code.InvokeMethod invoke, com.debughelper.tools.r8.graph.DexMethod target, int argumentIndex) {
    com.debughelper.tools.r8.graph.DexType returnType = invoke.getInvokedMethod().proto.returnType;
    // TODO(sgjesse): Insert cast if required.
    if (invoke.isInvokeStatic()) {
      return invoke.getInvokedMethod().proto.parameters.values[argumentIndex] == returnType;
    } else {
      if (argumentIndex == 0) {
        return invoke.getInvokedMethod().getHolder() == returnType;
      } else {
        return invoke.getInvokedMethod().proto.parameters.values[argumentIndex - 1] == returnType;
      }
    }
  }

  // Replace result uses for methods where something is known about what is returned.
  public void rewriteMoveResult(com.debughelper.tools.r8.ir.code.IRCode code) {
    if (options.isGeneratingClassFiles()) {
      return;
    }
    Enqueuer.AppInfoWithLiveness appInfoWithLiveness = appInfo.withLiveness();
    com.debughelper.tools.r8.ir.code.InstructionIterator iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction current = iterator.next();
      if (current.isInvokeMethod()) {
        com.debughelper.tools.r8.ir.code.InvokeMethod invoke = current.asInvokeMethod();
        if (invoke.outValue() != null && !invoke.outValue().hasLocalInfo()) {
          boolean isLibraryMethodReturningReceiver =
              libraryMethodsReturningReceiver.contains(invoke.getInvokedMethod());
          if (isLibraryMethodReturningReceiver) {
            if (checkArgumentType(invoke, invoke.getInvokedMethod(), 0)) {
              invoke.outValue().replaceUsers(invoke.arguments().get(0));
              invoke.setOutValue(null);
            }
          } else if (appInfoWithLiveness != null) {
            com.debughelper.tools.r8.graph.DexEncodedMethod target = invoke.computeSingleTarget(appInfoWithLiveness);
            if (target != null) {
              com.debughelper.tools.r8.graph.DexMethod invokedMethod = target.method;
              // Check if the invoked method is known to return one of its arguments.
              com.debughelper.tools.r8.graph.DexEncodedMethod definition = appInfo.definitionFor(invokedMethod);
              if (definition != null && definition.getOptimizationInfo().returnsArgument()) {
                int argumentIndex = definition.getOptimizationInfo().getReturnedArgument();
                // Replace the out value of the invoke with the argument and ignore the out value.
                if (argumentIndex != -1 && checkArgumentType(invoke, target.method,
                    argumentIndex)) {
                  com.debughelper.tools.r8.ir.code.Value argument = invoke.arguments().get(argumentIndex);
                  assert invoke.outType().verifyCompatible(argument.outType());
                  invoke.outValue().replaceUsers(argument);
                  invoke.setOutValue(null);
                }
              }
            }
          }
        }
      }
    }
    assert code.isConsistentGraph();
  }

  /**
   * For supporting assert javac adds the static field $assertionsDisabled to all classes which
   * have methods with assertions. This is used to support the Java VM -ea flag.
   *
   * The class:
   * <pre>
   * class A {
   *   void m() {
   *     assert xxx;
   *   }
   * }
   * </pre>
   * Is compiled into:
   * <pre>
   * class A {
   *   static boolean $assertionsDisabled;
   *   static {
   *     $assertionsDisabled = A.class.desiredAssertionStatus();
   *   }
   *
   *   // method with "assert xxx";
   *   void m() {
   *     if (!$assertionsDisabled) {
   *       if (xxx) {
   *         throw new AssertionError(...);
   *       }
   *     }
   *   }
   * }
   * </pre>
   * With the rewriting below (and other rewritings) the resulting code is:
   * <pre>
   * class A {
   *   void m() {
   *   }
   * }
   * </pre>
   */
  public void disableAssertions(
          AppInfo appInfo, com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code, OptimizationFeedback feedback) {
    if (method.isClassInitializer()) {
      if (!hasJavacClinitAssertionCode(code)) {
        return;
      }
      // Mark the clinit as having code to turn on assertions.
      feedback.setInitializerEnablingJavaAssertions(method);
    } else {
      // If the clinit of this class did not have have code to turn on assertions don't try to
      // remove assertion code from the method.
      com.debughelper.tools.r8.graph.DexClass clazz = appInfo.definitionFor(method.method.holder);
      if (clazz == null) {
        return;
      }
      com.debughelper.tools.r8.graph.DexEncodedMethod clinit = clazz.getClassInitializer();
      if (clinit == null
          || !clinit.isProcessed()
          || !clinit.getOptimizationInfo().isInitializerEnablingJavaAssertions()) {
        return;
      }
    }

    com.debughelper.tools.r8.ir.code.InstructionIterator iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction current = iterator.next();
      if (current.isInvokeMethod()) {
        InvokeMethod invoke = current.asInvokeMethod();
        if (invoke.getInvokedMethod() == dexItemFactory.classMethods.desiredAssertionStatus) {
          iterator.replaceCurrentInstruction(code.createFalse());
        }
      } else if (current.isStaticPut()) {
        com.debughelper.tools.r8.ir.code.StaticPut staticPut = current.asStaticPut();
        if (staticPut.getField().name == dexItemFactory.assertionsDisabled) {
          iterator.remove();
        }
      } else if (current.isStaticGet()) {
        com.debughelper.tools.r8.ir.code.StaticGet staticGet = current.asStaticGet();
        if (staticGet.getField().name == dexItemFactory.assertionsDisabled) {
          iterator.replaceCurrentInstruction(code.createTrue());
        }
      }
    }
  }

  private boolean isClassDesiredAssertionStatusInvoke(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    return instruction.isInvokeMethod()
    && instruction.asInvokeMethod().getInvokedMethod()
        == dexItemFactory.classMethods.desiredAssertionStatus;
  }

  private boolean isAssertionsDisabledFieldPut(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    return instruction.isStaticPut()
        && instruction.asStaticPut().getField().name == dexItemFactory.assertionsDisabled;
  }

  private boolean isNotDebugInstruction(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    return !instruction.isDebugInstruction();
  }

  private com.debughelper.tools.r8.ir.code.Value blockWithSingleConstNumberAndGoto(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    com.debughelper.tools.r8.ir.code.InstructionIterator iterator  = block.iterator();
    com.debughelper.tools.r8.ir.code.Instruction constNumber = iterator.nextUntil(this::isNotDebugInstruction);
    if (constNumber == null || !constNumber.isConstNumber()) {
      return null;
    }
    com.debughelper.tools.r8.ir.code.Instruction exit = iterator.nextUntil(this::isNotDebugInstruction);
    return exit != null && exit.isGoto() ? constNumber.outValue() : null;
  }

  private com.debughelper.tools.r8.ir.code.Value blockWithAssertionsDisabledFieldPut(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    com.debughelper.tools.r8.ir.code.InstructionIterator iterator  = block.iterator();
    com.debughelper.tools.r8.ir.code.Instruction fieldPut = iterator.nextUntil(this::isNotDebugInstruction);
    return fieldPut != null
        && isAssertionsDisabledFieldPut(fieldPut) ? fieldPut.inValues().get(0) : null;
  }

  private boolean hasJavacClinitAssertionCode(com.debughelper.tools.r8.ir.code.IRCode code) {
    com.debughelper.tools.r8.ir.code.InstructionIterator iterator = code.instructionIterator();
    com.debughelper.tools.r8.ir.code.Instruction current = iterator.nextUntil(this::isClassDesiredAssertionStatusInvoke);
    if (current == null) {
      return false;
    }

    com.debughelper.tools.r8.ir.code.Value DesiredAssertionStatus = current.outValue();
    assert iterator.hasNext();
    current = iterator.next();
    if (!current.isIf()
        || !current.asIf().isZeroTest()
        || current.asIf().inValues().get(0) != DesiredAssertionStatus) {
      return false;
    }

    com.debughelper.tools.r8.ir.code.If theIf = current.asIf();
    com.debughelper.tools.r8.ir.code.BasicBlock trueTarget = theIf.getTrueTarget();
    com.debughelper.tools.r8.ir.code.BasicBlock falseTarget = theIf.fallthroughBlock();
    if (trueTarget == falseTarget) {
      return false;
    }

    com.debughelper.tools.r8.ir.code.Value trueValue = blockWithSingleConstNumberAndGoto(trueTarget);
    com.debughelper.tools.r8.ir.code.Value falseValue = blockWithSingleConstNumberAndGoto(falseTarget);
    if (trueValue == null
        || falseValue == null
        || (trueTarget.exit().asGoto().getTarget() != falseTarget.exit().asGoto().getTarget())) {
      return false;
    }

    com.debughelper.tools.r8.ir.code.BasicBlock target = trueTarget.exit().asGoto().getTarget();
    com.debughelper.tools.r8.ir.code.Value storeValue = blockWithAssertionsDisabledFieldPut(target);
    return storeValue != null
        && storeValue.isPhi()
        && storeValue.asPhi().getOperands().size() == 2
        && storeValue.asPhi().getOperands().contains(trueValue)
        && storeValue.asPhi().getOperands().contains(falseValue);
  }

  // Check if the static put is a constant derived from the class holding the method.
  // This checks for java.lang.Class.getName and java.lang.Class.getSimpleName.
  private boolean isClassNameConstantOf(com.debughelper.tools.r8.graph.DexClass clazz, com.debughelper.tools.r8.ir.code.StaticPut put) {
    if (put.getField().type != dexItemFactory.stringType) {
      return false;
    }
    if (put.inValue().definition != null) {
      return isClassNameConstantOf(clazz, put.inValue().definition);
    }
    return false;
  }

  private boolean isClassNameConstantOf(com.debughelper.tools.r8.graph.DexClass clazz, com.debughelper.tools.r8.ir.code.Instruction instruction) {
    if (instruction.isInvokeVirtual()) {
      com.debughelper.tools.r8.ir.code.InvokeVirtual invoke = instruction.asInvokeVirtual();
      if ((invoke.getInvokedMethod() == dexItemFactory.classMethods.getSimpleName
          || invoke.getInvokedMethod() == dexItemFactory.classMethods.getName)
          && !invoke.inValues().get(0).isPhi()
          && invoke.inValues().get(0).definition.isConstClass()
          && invoke.inValues().get(0).definition.asConstClass().getValue() == clazz.type) {
        return true;
      }
    }
    return false;
  }

  public void collectClassInitializerDefaults(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code) {
    if (!method.isClassInitializer()) {
      return;
    }

    com.debughelper.tools.r8.graph.DexClass clazz = definitionFor(method.method.getHolder());
    if (clazz == null) {
      return;
    }

    // Collect straight-line static puts up to the first side-effect that is not
    // a static put on a field on this class with a value that can be hoisted to
    // the field initial value.
    Set<com.debughelper.tools.r8.ir.code.StaticPut> puts = Sets.newIdentityHashSet();
    Map<com.debughelper.tools.r8.graph.DexField, com.debughelper.tools.r8.ir.code.StaticPut> finalFieldPut = Maps.newIdentityHashMap();
    computeUnnecessaryStaticPuts(code, method, clazz, puts, finalFieldPut);

    if (!puts.isEmpty()) {
      // Set initial values for static fields from the definitive static put instructions collected.
      for (com.debughelper.tools.r8.ir.code.StaticPut put : finalFieldPut.values()) {
        com.debughelper.tools.r8.graph.DexField field = put.getField();
        DexEncodedField encodedField = appInfo.definitionFor(field);
        if (field.type == dexItemFactory.stringType) {
          if (put.inValue().isConstant()) {
            if (put.inValue().isConstNumber()) {
              assert put.inValue().isZero();
              encodedField.setStaticValue(DexValue.DexValueNull.NULL);
            } else {
              com.debughelper.tools.r8.ir.code.ConstString cnst = put.inValue().getConstInstruction().asConstString();
              encodedField.setStaticValue(new DexValue.DexValueString(cnst.getValue()));
            }
          } else {
            com.debughelper.tools.r8.ir.code.InvokeVirtual invoke = put.inValue().definition.asInvokeVirtual();
            String name = method.method.getHolder().toSourceString();
            if (invoke.getInvokedMethod() == dexItemFactory.classMethods.getSimpleName) {
              String simpleName = name.substring(name.lastIndexOf('.') + 1);
              encodedField.setStaticValue(
                  new DexValue.DexValueString(dexItemFactory.createString(simpleName)));
            } else {
              assert invoke.getInvokedMethod() == dexItemFactory.classMethods.getName;
              encodedField.setStaticValue(new DexValue.DexValueString(dexItemFactory.createString(name)));
            }
          }
        } else if (field.type.isClassType() || field.type.isArrayType()) {
          if (put.inValue().isZero()) {
            encodedField.setStaticValue(DexValue.DexValueNull.NULL);
          } else {
            throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected default value for field type " + field.type + ".");
          }
        } else {
          com.debughelper.tools.r8.ir.code.ConstNumber cnst = put.inValue().getConstInstruction().asConstNumber();
          if (field.type == dexItemFactory.booleanType) {
            encodedField.setStaticValue(DexValue.DexValueBoolean.create(cnst.getBooleanValue()));
          } else if (field.type == dexItemFactory.byteType) {
            encodedField.setStaticValue(DexValue.DexValueByte.create((byte) cnst.getIntValue()));
          } else if (field.type == dexItemFactory.shortType) {
            encodedField.setStaticValue(DexValue.DexValueShort.create((short) cnst.getIntValue()));
          } else if (field.type == dexItemFactory.intType) {
            encodedField.setStaticValue(DexValue.DexValueInt.create(cnst.getIntValue()));
          } else if (field.type == dexItemFactory.longType) {
            encodedField.setStaticValue(DexValue.DexValueLong.create(cnst.getLongValue()));
          } else if (field.type == dexItemFactory.floatType) {
            encodedField.setStaticValue(DexValue.DexValueFloat.create(cnst.getFloatValue()));
          } else if (field.type == dexItemFactory.doubleType) {
            encodedField.setStaticValue(DexValue.DexValueDouble.create(cnst.getDoubleValue()));
          } else if (field.type == dexItemFactory.charType) {
            encodedField.setStaticValue(DexValue.DexValueChar.create((char) cnst.getIntValue()));
          } else {
            throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected field type " + field.type + ".");
          }
        }
      }

      // Remove the static put instructions now replaced by static field initial values.
      List<com.debughelper.tools.r8.ir.code.Instruction> toRemove = new ArrayList<>();
      com.debughelper.tools.r8.ir.code.InstructionIterator iterator = code.instructionIterator();
      while (iterator.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction current = iterator.next();
        if (current.isStaticPut() && puts.contains(current.asStaticPut())) {
          iterator.remove();
          // Collect, for removal, the instruction that created the value for the static put,
          // if all users are gone. This is done even if these instructions can throw as for
          // the current patterns matched these exceptions are not detectable.
          com.debughelper.tools.r8.ir.code.StaticPut put = current.asStaticPut();
          if (put.inValue().uniqueUsers().size() == 0) {
            if (put.inValue().isConstString()) {
              toRemove.add(put.inValue().definition);
            } else if (put.inValue().definition.isInvokeVirtual()) {
              toRemove.add(put.inValue().definition);
            }
          }
        }
      }

      // Remove the instructions collected for removal.
      if (toRemove.size() > 0) {
        iterator = code.instructionIterator();
        while (iterator.hasNext()) {
          if (toRemove.contains(iterator.next())) {
            iterator.remove();
          }
        }
      }
    }
  }

  private void computeUnnecessaryStaticPuts(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.graph.DexEncodedMethod clinit, com.debughelper.tools.r8.graph.DexClass clazz,
                                            Set<com.debughelper.tools.r8.ir.code.StaticPut> puts, Map<com.debughelper.tools.r8.graph.DexField, com.debughelper.tools.r8.ir.code.StaticPut> finalFieldPut) {
    final int color = code.reserveMarkingColor();
    try {
      com.debughelper.tools.r8.ir.code.BasicBlock block = code.blocks.getFirst();
      while (!block.isMarked(color) && block.getPredecessors().size() <= 1) {
        block.mark(color);
        com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
        while (it.hasNext()) {
          com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
          if (instructionHasSideEffects(instruction)) {
            if (isClassNameConstantOf(clazz, instruction)) {
              continue;
            } else if (instruction.isStaticPut()) {
              StaticPut put = instruction.asStaticPut();
              if (put.getField().clazz != clazz.type) {
                // Can cause clinit on another class which can read uninitialized static fields
                // of this class.
                return;
              }
              DexField field = put.getField();
              if (clazz.definesStaticField(field)) {
                if (put.inValue().isConstant()) {
                  if ((field.type.isClassType() || field.type.isArrayType())
                      && put.inValue().isZero()) {
                    finalFieldPut.put(put.getField(), put);
                    puts.add(put);
                  } else if (field.type.isPrimitiveType()
                      || field.type == dexItemFactory.stringType) {
                    finalFieldPut.put(put.getField(), put);
                    puts.add(put);
                  }
                } else if (isClassNameConstantOf(clazz, put)) {
                  // Collect put of class name constant as a potential default value.
                  finalFieldPut.put(put.getField(), put);
                  puts.add(put);
                }
              }
            } else if (!(instruction.isConstString() || instruction.isConstClass())) {
              // Allow const string and const class which can only throw exceptions as their
              // side-effect. Bail out for anything else.
              return;
            }
          }
        }
        if (block.exit().isGoto()) {
          block = block.exit().asGoto().getTarget();
        }
      }
    } finally {
      code.returnMarkingColor(color);
    }
  }

  private com.debughelper.tools.r8.graph.DexClass definitionFor(com.debughelper.tools.r8.graph.DexType type) {
    if (cachedClass != null && cachedClass.type == type) {
      return cachedClass;
    }
    return appInfo.definitionFor(type);
  }

  public void enterCachedClass(com.debughelper.tools.r8.graph.DexProgramClass clazz) {
    assert cachedClass == null;
    cachedClass = clazz;
  }

  public void leaveCachedClass(DexProgramClass clazz) {
    assert cachedClass == clazz;
    cachedClass = null;
  }

  public void removeCasts(com.debughelper.tools.r8.ir.code.IRCode code, TypeEnvironment typeEnvironment) {
    com.debughelper.tools.r8.ir.code.InstructionIterator it = code.instructionIterator();
    boolean needToRemoveTrivialPhis = false;
    while (it.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction current = it.next();
      if (!current.isCheckCast()) {
        continue;
      }
      CheckCast checkCast = current.asCheckCast();
      com.debughelper.tools.r8.ir.code.Value inValue = checkCast.object();
      com.debughelper.tools.r8.ir.code.Value outValue = checkCast.outValue();
      com.debughelper.tools.r8.graph.DexType castType = checkCast.getType();

      // We might see chains of casts on subtypes. It suffices to cast to the lowest subtype,
      // as that will fail if a cast on a supertype would have failed.
      Predicate<com.debughelper.tools.r8.ir.code.Instruction> isCheckcastToSubtype =
          user -> user.isCheckCast() && user.asCheckCast().getType().isSubtypeOf(castType, appInfo);
      if (!checkCast.getBlock().hasCatchHandlers()
          && outValue.isUsed()
          && outValue.numberOfPhiUsers() == 0
          && outValue.uniqueUsers().stream().allMatch(isCheckcastToSubtype)) {
        removeOrReplaceByDebugLocalWrite(it, inValue, outValue);
        continue;
      }

      TypeLatticeElement inTypeLattice = typeEnvironment.getLatticeElement(inValue);
      if (!inTypeLattice.isTop()) {
        TypeLatticeElement outTypeLattice = typeEnvironment.getLatticeElement(outValue);
        TypeLatticeElement castTypeLattice =
            TypeLatticeElement.fromDexType(castType, inTypeLattice.isNullable());
        // Special case: null cast, e.g., getMethod(..., (Class[]) null);
        // This cast should be kept no matter what.
        if (inTypeLattice.mustBeNull()) {
          assert outTypeLattice.equals(castTypeLattice);
          continue;
        }
        // 1) Trivial cast.
        //   A a = ...
        //   A a' = (A) a;
        // 2) Up-cast: we already have finer type info.
        //   A < B
        //   A a = ...
        //   B b = (B) a;
        if (TypeLatticeElement.lessThanOrEqual(appInfo, inTypeLattice, castTypeLattice)) {
          assert outTypeLattice.equals(inTypeLattice);
          needToRemoveTrivialPhis = needToRemoveTrivialPhis || outValue.numberOfPhiUsers() != 0;
          removeOrReplaceByDebugLocalWrite(it, inValue, outValue);
          continue;
        }
        // Otherwise, keep the checkcast to preserve verification errors. E.g., down-cast:
        // A < B < C
        // c = ...        // Even though we know c is of type A,
        // a' = (B) c;    // (this could be removed, since chained below.)
        // a'' = (A) a';  // this should remain for runtime verification.
        assert outTypeLattice.equals(castTypeLattice);
      }
    }
    // ... v1
    // ...
    // v2 <- check-cast v1, T
    // v3 <- phi(v1, v2)
    // Removing check-cast may result in a trivial phi:
    // v3 <- phi(v1, v1)
    if (needToRemoveTrivialPhis) {
      code.removeAllTrivialPhis();
    }
    it = code.instructionIterator();
    assert code.isConsistentSSA();
  }

  private void removeOrReplaceByDebugLocalWrite(
          com.debughelper.tools.r8.ir.code.InstructionIterator it, com.debughelper.tools.r8.ir.code.Value inValue, com.debughelper.tools.r8.ir.code.Value outValue) {
    if (outValue.getLocalInfo() != inValue.getLocalInfo() && outValue.hasLocalInfo()) {
      com.debughelper.tools.r8.ir.code.DebugLocalWrite debugLocalWrite = new com.debughelper.tools.r8.ir.code.DebugLocalWrite(outValue, inValue);
      it.replaceCurrentInstruction(debugLocalWrite);
    } else {
      outValue.replaceUsers(inValue);
      it.removeOrReplaceByDebugLocalRead();
    }
  }

  private boolean canBeFolded(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    return (instruction.isBinop() && instruction.asBinop().canBeFolded()) ||
        (instruction.isUnop() && instruction.asUnop().canBeFolded());
  }

  // Split constants that flow into ranged invokes. This gives the register allocator more
  // freedom in assigning register to ranged invokes which can greatly reduce the number
  // of register needed (and thereby code size as well).
  public void splitRangeInvokeConstants(com.debughelper.tools.r8.ir.code.IRCode code) {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction current = it.next();
        if (current.isInvoke() && current.asInvoke().requiredArgumentRegisters() > 5) {
          Invoke invoke = current.asInvoke();
          it.previous();
          Map<com.debughelper.tools.r8.ir.code.ConstNumber, com.debughelper.tools.r8.ir.code.ConstNumber> oldToNew = new IdentityHashMap<>();
          for (int i = 0; i < invoke.inValues().size(); i++) {
            com.debughelper.tools.r8.ir.code.Value value = invoke.inValues().get(i);
            if (value.isConstNumber() && value.numberOfUsers() > 1) {
              com.debughelper.tools.r8.ir.code.ConstNumber definition = value.getConstInstruction().asConstNumber();
              com.debughelper.tools.r8.ir.code.Value originalValue = definition.outValue();
              com.debughelper.tools.r8.ir.code.ConstNumber newNumber = oldToNew.get(definition);
              if (newNumber == null) {
                newNumber = com.debughelper.tools.r8.ir.code.ConstNumber.copyOf(code, definition);
                it.add(newNumber);
                newNumber.setPosition(current.getPosition());
                oldToNew.put(definition, newNumber);
              }
              invoke.inValues().set(i, newNumber.outValue());
              originalValue.removeUser(invoke);
              newNumber.outValue().addUser(invoke);
            }
          }
          it.next();
        }
      }
    }
  }

  /**
   * If an instruction is known to be a /lit8 or /lit16 instruction, update the instruction to use
   * its own constant that will be defined just before the instruction. This transformation allows
   * to decrease pressure on register allocation by defining the shortest range of constant used
   * by this kind of instruction. D8Adapter knowns at build time that constant will be encoded
   * directly into the final Dex instruction.
   */
  public void useDedicatedConstantForLitInstruction(com.debughelper.tools.r8.ir.code.IRCode code) {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator instructionIterator = block.listIterator();
      while (instructionIterator.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction currentInstruction = instructionIterator.next();
        if (shouldBeLitInstruction(currentInstruction)) {
          assert currentInstruction.isBinop();
          com.debughelper.tools.r8.ir.code.Binop binop = currentInstruction.asBinop();
          com.debughelper.tools.r8.ir.code.Value constValue;
          if (binop.leftValue().isConstNumber()) {
            constValue = binop.leftValue();
          } else if (binop.rightValue().isConstNumber()) {
            constValue = binop.rightValue();
          } else {
            throw new com.debughelper.tools.r8.errors.Unreachable();
          }
          if (constValue.numberOfAllUsers() > 1) {
            // No need to do the transformation if the const value is already used only one time.
            com.debughelper.tools.r8.ir.code.ConstNumber newConstant = com.debughelper.tools.r8.ir.code.ConstNumber
                .copyOf(code, constValue.definition.asConstNumber());
            newConstant.setPosition(currentInstruction.getPosition());
            newConstant.setBlock(currentInstruction.getBlock());
            currentInstruction.replaceValue(constValue, newConstant.outValue());
            constValue.removeUser(currentInstruction);
            instructionIterator.previous();
            instructionIterator.add(newConstant);
            instructionIterator.next();
          }
        }
      }
    }

    assert code.isConsistentSSA();
  }

  /**
   * A /lit8 or /lit16 instruction only concerns arithmetic or logical instruction. /lit8 or /lit16
   * instructions generate bigger code than 2addr instructions, thus we favor 2addr instructions
   * rather than /lit8 or /lit16 instructions.
   */
  private static boolean shouldBeLitInstruction(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    if (instruction.isArithmeticBinop() || instruction.isLogicalBinop()) {
      com.debughelper.tools.r8.ir.code.Binop binop = instruction.asBinop();
      if (!binop.needsValueInRegister(binop.leftValue()) ||
          !binop.needsValueInRegister(binop.rightValue())) {
        return !canBe2AddrInstruction(binop);
      }
    }

    return false;
  }

  /**
   * Estimate if a binary operation can be a 2addr form or not. It can be a 2addr form when an
   * argument is no longer needed after the binary operation and can be overwritten. That is
   * definitely the case if there is no path between the binary operation and all other usages.
   */
  private static boolean canBe2AddrInstruction(com.debughelper.tools.r8.ir.code.Binop binop) {
    com.debughelper.tools.r8.ir.code.Value value = null;
    if (binop.needsValueInRegister(binop.leftValue())) {
      value = binop.leftValue();
    } else if (binop.isCommutative() && binop.needsValueInRegister(binop.rightValue())) {
      value = binop.rightValue();
    }

    if (value != null) {
      Iterable<com.debughelper.tools.r8.ir.code.Instruction> users = value.debugUsers() != null ?
          Iterables.concat(value.uniqueUsers(), value.debugUsers()) : value.uniqueUsers();

      for (com.debughelper.tools.r8.ir.code.Instruction user : users) {
        if (hasPath(binop, user)) {
          return false;
        }
      }

      Iterable<com.debughelper.tools.r8.ir.code.Phi> phiUsers = value.debugPhiUsers() != null
          ? Iterables.concat(value.uniquePhiUsers(), value.debugPhiUsers())
          : value.uniquePhiUsers();

      for (com.debughelper.tools.r8.ir.code.Phi user : phiUsers) {
        if (binop.getBlock().hasPathTo(user.getBlock())) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Return true if there is a path between {@code source} instruction and {@code target}
   * instruction.
   */
  private static boolean hasPath(com.debughelper.tools.r8.ir.code.Instruction source, com.debughelper.tools.r8.ir.code.Instruction target) {
    com.debughelper.tools.r8.ir.code.BasicBlock sourceBlock = source.getBlock();
    com.debughelper.tools.r8.ir.code.BasicBlock targetBlock = target.getBlock();
    if (sourceBlock == targetBlock) {
      return sourceBlock.getInstructions().indexOf(source) <
          targetBlock.getInstructions().indexOf(target);
    }

    return source.getBlock().hasPathTo(targetBlock);
  }

  public void shortenLiveRanges(com.debughelper.tools.r8.ir.code.IRCode code) {
    // Currently, we are only shortening the live range of ConstNumbers in the entry block
    // and ConstStrings with one user.
    // TODO(ager): Generalize this to shorten live ranges for more instructions? Currently
    // doing so seems to make things worse.
    Supplier<com.debughelper.tools.r8.ir.code.DominatorTree> dominatorTreeMemoization =
        Suppliers.memoize(() -> new com.debughelper.tools.r8.ir.code.DominatorTree(code));
    Map<com.debughelper.tools.r8.ir.code.BasicBlock, List<com.debughelper.tools.r8.ir.code.Instruction>> addConstantInBlock = new HashMap<>();
    LinkedList<com.debughelper.tools.r8.ir.code.BasicBlock> blocks = code.blocks;
    for (int i = 0; i < blocks.size(); i++) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = blocks.get(i);
      if (i == 0) {
        // For the first block process all ConstNumber instructions
        // as well as ConstString instructions having just one use.
        shortenLiveRangesInsideBlock(block, dominatorTreeMemoization, addConstantInBlock,
            insn -> (insn.isConstNumber() && insn.outValue().numberOfAllUsers() != 0)
                || (insn.isConstString() && insn.outValue().numberOfAllUsers() == 1));
      } else {
        // For all following blocks only process ConstString with just one use.
        shortenLiveRangesInsideBlock(block, dominatorTreeMemoization, addConstantInBlock,
            insn -> insn.isConstString() && insn.outValue().numberOfAllUsers() == 1);
      }
    }

    // Heuristic to decide if constant instructions are shared in dominator block
    // of usages or move to the usages.

    // Process all blocks in stable order to avoid non-determinism of hash map iterator.
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
      List<com.debughelper.tools.r8.ir.code.Instruction> instructions = addConstantInBlock.get(block);
      if (instructions == null) {
        continue;
      }

      if (block != blocks.get(0) && instructions.size() > STOP_SHARED_CONSTANT_THRESHOLD) {
        // Too much constants in the same block, do not longer share them except if they are used
        // by phi instructions or they are a sting constants.
        for (com.debughelper.tools.r8.ir.code.Instruction instruction : instructions) {
          if (instruction.outValue().numberOfPhiUsers() != 0 || instruction.isConstString()) {
            // Add constant into the dominator block of usages.
            insertConstantInBlock(instruction, block);
          } else {
            assert instruction.isConstNumber();
            com.debughelper.tools.r8.ir.code.ConstNumber constNumber = instruction.asConstNumber();
            com.debughelper.tools.r8.ir.code.Value constantValue = instruction.outValue();
            assert constantValue.numberOfUsers() != 0;
            assert constantValue.numberOfUsers() == constantValue.numberOfAllUsers();
            for (com.debughelper.tools.r8.ir.code.Instruction user : constantValue.uniqueUsers()) {
              com.debughelper.tools.r8.ir.code.ConstNumber newCstNum = com.debughelper.tools.r8.ir.code.ConstNumber.copyOf(code, constNumber);
              newCstNum.setPosition(user.getPosition());
              com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = user.getBlock().listIterator(user);
              iterator.previous();
              iterator.add(newCstNum);
              user.replaceValue(constantValue, newCstNum.outValue());
            }
            constantValue.clearUsers();
          }
        }
      } else {
        // Add constant into the dominator block of usages.
        for (com.debughelper.tools.r8.ir.code.Instruction instruction : instructions) {
          insertConstantInBlock(instruction, block);
        }
      }
    }

    assert code.isConsistentSSA();
  }

  private void shortenLiveRangesInsideBlock(com.debughelper.tools.r8.ir.code.BasicBlock block,
                                            Supplier<com.debughelper.tools.r8.ir.code.DominatorTree> dominatorTreeMemoization,
                                            Map<com.debughelper.tools.r8.ir.code.BasicBlock, List<com.debughelper.tools.r8.ir.code.Instruction>> addConstantInBlock,
                                            Predicate<com.debughelper.tools.r8.ir.code.Instruction> selector) {

    com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
    while (it.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
      if (!selector.test(instruction) || instruction.outValue().hasLocalInfo()) {
        continue;
      }
      // Collect the blocks for all users of the constant.
      List<com.debughelper.tools.r8.ir.code.BasicBlock> userBlocks = new LinkedList<>();
      for (com.debughelper.tools.r8.ir.code.Instruction user : instruction.outValue().uniqueUsers()) {
        userBlocks.add(user.getBlock());
      }
      for (com.debughelper.tools.r8.ir.code.Phi phi : instruction.outValue().uniquePhiUsers()) {
        userBlocks.add(phi.getBlock());
      }
      // Locate the closest dominator block for all user blocks.
      com.debughelper.tools.r8.ir.code.DominatorTree dominatorTree = dominatorTreeMemoization.get();
      com.debughelper.tools.r8.ir.code.BasicBlock dominator = dominatorTree.closestDominator(userBlocks);
      // If the closest dominator block is a block that uses the constant for a phi the constant
      // needs to go in the immediate dominator block so that it is available for phi moves.
      for (com.debughelper.tools.r8.ir.code.Phi phi : instruction.outValue().uniquePhiUsers()) {
        if (phi.getBlock() == dominator) {
          if (instruction.outValue().numberOfAllUsers() == 1 &&
              phi.usesValueOneTime(instruction.outValue())) {
            // Out value is used only one time, move the constant directly to the corresponding
            // branch rather than into the dominator to avoid to generate a const on paths which
            // does not required it.
            int predIndex = phi.getOperands().indexOf(instruction.outValue());
            dominator = dominator.getPredecessors().get(predIndex);
          } else {
            dominator = dominatorTree.immediateDominator(dominator);
          }
          break;
        }
      }

      if (instruction.instructionTypeCanThrow()) {
        if (block.hasCatchHandlers() || dominator.hasCatchHandlers()) {
          // Do not move the constant if the constant instruction can throw
          // and the dominator or the original block has catch handlers.
          continue;
        }
        if (!dominator.isSimpleAlwaysThrowingPath()) {
          // Only move string constants into blocks being part of simple
          // always throwing path.
          continue;
        }
      }

      // Move the const instruction as close to its uses as possible.
      it.detach();

      List<com.debughelper.tools.r8.ir.code.Instruction> csts =
          addConstantInBlock.computeIfAbsent(dominator, k -> new ArrayList<>());
      csts.add(instruction);
    }
  }

  private void insertConstantInBlock(com.debughelper.tools.r8.ir.code.Instruction instruction, com.debughelper.tools.r8.ir.code.BasicBlock block) {
    boolean hasCatchHandlers = block.hasCatchHandlers();
    com.debughelper.tools.r8.ir.code.InstructionListIterator insertAt = block.listIterator();
    // Place the instruction as late in the block as we can. It needs to go before users
    // and if we have catch handlers it needs to be placed before the throwing instruction.
    insertAt.nextUntil(i ->
        i.inValues().contains(instruction.outValue())
            || i.isJumpInstruction()
            || (hasCatchHandlers && i.instructionTypeCanThrow())
            || (options.canHaveCmpIfFloatBug() && i.isCmp()));
    com.debughelper.tools.r8.ir.code.Instruction next = insertAt.previous();
    instruction.forceSetPosition(
        next.isGoto() ? next.asGoto().getTarget().getPosition() : next.getPosition());
    insertAt.add(instruction);
  }

  private short[] computeArrayFilledData(com.debughelper.tools.r8.ir.code.ConstInstruction[] values, int size, int elementSize) {
    if (values == null) {
      return null;
    }
    if (elementSize == 1) {
      short[] result = new short[(size + 1) / 2];
      for (int i = 0; i < size; i += 2) {
        short value = (short) (values[i].asConstNumber().getIntValue() & 0xFF);
        if (i + 1 < size) {
          value |= (short) ((values[i + 1].asConstNumber().getIntValue() & 0xFF) << 8);
        }
        result[i / 2] = value;
      }
      return result;
    }
    assert elementSize == 2 || elementSize == 4 || elementSize == 8;
    int shortsPerConstant = elementSize / 2;
    short[] result = new short[size * shortsPerConstant];
    for (int i = 0; i < size; i++) {
      long value = values[i].asConstNumber().getRawValue();
      for (int part = 0; part < shortsPerConstant; part++) {
        result[i * shortsPerConstant + part] = (short) ((value >> (16 * part)) & 0xFFFFL);
      }
    }
    return result;
  }

  private com.debughelper.tools.r8.ir.code.ConstInstruction[] computeConstantArrayValues(
          com.debughelper.tools.r8.ir.code.NewArrayEmpty newArray, com.debughelper.tools.r8.ir.code.BasicBlock block, int size) {
    if (size > MAX_FILL_ARRAY_SIZE) {
      return null;
    }
    com.debughelper.tools.r8.ir.code.ConstInstruction[] values = new com.debughelper.tools.r8.ir.code.ConstInstruction[size];
    int remaining = size;
    Set<com.debughelper.tools.r8.ir.code.Instruction> users = newArray.outValue().uniqueUsers();
    Set<com.debughelper.tools.r8.ir.code.BasicBlock> visitedBlocks = Sets.newIdentityHashSet();
    // We allow the array instantiations to cross block boundaries as long as it hasn't encountered
    // an instruction instance that can throw an exception.
    com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
    it.nextUntil(i -> i == newArray);
    do {
      visitedBlocks.add(block);
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
        // If we encounter an instruction that can throw an exception we need to bail out of the
        // optimization so that we do not transform half-initialized arrays into fully initialized
        // arrays on exceptional edges. If the block has no handlers it is not observable so
        // we perform the rewriting.
        if (block.hasCatchHandlers() && instruction.instructionInstanceCanThrow()) {
          return null;
        }
        if (!users.contains(instruction)) {
          continue;
        }
        // If the initialization sequence is broken by another use we cannot use a
        // fill-array-data instruction.
        if (!instruction.isArrayPut()) {
          return null;
        }
        ArrayPut arrayPut = instruction.asArrayPut();
        if (!(arrayPut.value().isConstant() && arrayPut.index().isConstNumber())) {
          return null;
        }
        int index = arrayPut.index().getConstInstruction().asConstNumber().getIntValue();
        if (index < 0 || index >= values.length) {
          return null;
        }
        if (values[index] != null) {
          return null;
        }
        com.debughelper.tools.r8.ir.code.ConstInstruction value = arrayPut.value().getConstInstruction();
        values[index] = value;
        --remaining;
        if (remaining == 0) {
          return values;
        }
      }
      com.debughelper.tools.r8.ir.code.BasicBlock nextBlock = block.exit().isGoto() ? block.exit().asGoto().getTarget() : null;
      block = nextBlock != null && !visitedBlocks.contains(nextBlock) ? nextBlock : null;
      it = block != null ? block.listIterator() : null;
    } while (it != null);
    return null;
  }

  private boolean allowNewFilledArrayConstruction(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    if (!(instruction instanceof com.debughelper.tools.r8.ir.code.NewArrayEmpty)) {
      return false;
    }
    com.debughelper.tools.r8.ir.code.NewArrayEmpty newArray = instruction.asNewArrayEmpty();
    if (!newArray.size().isConstant()) {
      return false;
    }
    assert newArray.size().isConstNumber();
    int size = newArray.size().getConstInstruction().asConstNumber().getIntValue();
    if (size < 1) {
      return false;
    }
    if (newArray.type.isPrimitiveArrayType()) {
      return true;
    }
    return newArray.type == dexItemFactory.stringArrayType
        && options.canUseFilledNewArrayOfObjects();
  }

  /**
   * Replace new-array followed by stores of constants to all entries with new-array
   * and fill-array-data / filled-new-array.
   */
  public void simplifyArrayConstruction(com.debughelper.tools.r8.ir.code.IRCode code) {
    if (options.isGeneratingClassFiles()) {
      return;
    }
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      // Map from the array value to the number of array put instruction to remove for that value.
      Map<com.debughelper.tools.r8.ir.code.Value, com.debughelper.tools.r8.ir.code.Instruction> instructionToInsertForArray = new HashMap<>();
      Map<com.debughelper.tools.r8.ir.code.Value, Integer> storesToRemoveForArray = new HashMap<>();
      // First pass: identify candidates and insert fill array data instruction.
      com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
        if (instruction.getLocalInfo() != null
            || !allowNewFilledArrayConstruction(instruction)) {
          continue;
        }
        com.debughelper.tools.r8.ir.code.NewArrayEmpty newArray = instruction.asNewArrayEmpty();
        int size = newArray.size().getConstInstruction().asConstNumber().getIntValue();
        com.debughelper.tools.r8.ir.code.ConstInstruction[] values = computeConstantArrayValues(newArray, block, size);
        if (values == null) {
          continue;
        }
        if (newArray.type == dexItemFactory.stringArrayType) {
          // Don't replace with filled-new-array if it requires more than 200 consecutive registers.
          if (size > 200) {
            continue;
          }
          List<com.debughelper.tools.r8.ir.code.Value> stringValues = new ArrayList<>(size);
          for (ConstInstruction value : values) {
            stringValues.add(value.outValue());
          }
          com.debughelper.tools.r8.ir.code.InvokeNewArray invoke = new InvokeNewArray(
              dexItemFactory.stringArrayType, newArray.outValue(), stringValues);
          invoke.setPosition(newArray.getPosition());
          it.detach();
          for (com.debughelper.tools.r8.ir.code.Value value : newArray.inValues()) {
            value.removeUser(newArray);
          }
          instructionToInsertForArray.put(newArray.outValue(), invoke);
        } else {
          // If there is only one element it is typically smaller to generate the array put
          // instruction instead of fill array data.
          if (size == 1) {
            continue;
          }
          int elementSize = newArray.type.elementSizeForPrimitiveArrayType();
          short[] contents = computeArrayFilledData(values, size, elementSize);
          if (contents == null) {
            continue;
          }
          int arraySize = newArray.size().getConstInstruction().asConstNumber().getIntValue();
          com.debughelper.tools.r8.ir.code.NewArrayFilledData fillArray = new NewArrayFilledData(
              newArray.outValue(), elementSize, arraySize, contents);
          fillArray.setPosition(newArray.getPosition());
          it.add(fillArray);
        }
        storesToRemoveForArray.put(newArray.outValue(), size);
      }
      // Second pass: remove all the array put instructions for the array for which we have
      // inserted a fill array data instruction instead.
      if (!storesToRemoveForArray.isEmpty()) {
        Set<com.debughelper.tools.r8.ir.code.BasicBlock> visitedBlocks = Sets.newIdentityHashSet();
        do {
          visitedBlocks.add(block);
          it = block.listIterator();
          while (it.hasNext()) {
            com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
            if (instruction.isArrayPut()) {
              com.debughelper.tools.r8.ir.code.Value array = instruction.asArrayPut().array();
              Integer toRemoveCount = storesToRemoveForArray.get(array);
              if (toRemoveCount != null) {
                if (toRemoveCount > 0) {
                  storesToRemoveForArray.put(array, --toRemoveCount);
                  it.remove();
                }
                if (toRemoveCount == 0) {
                  storesToRemoveForArray.put(array, --toRemoveCount);
                  com.debughelper.tools.r8.ir.code.Instruction construction = instructionToInsertForArray.get(array);
                  if (construction != null) {
                    it.add(construction);
                  }
                }
              }
            }
          }
          com.debughelper.tools.r8.ir.code.BasicBlock nextBlock = block.exit().isGoto() ? block.exit().asGoto().getTarget() : null;
          block = nextBlock != null && !visitedBlocks.contains(nextBlock) ? nextBlock : null;
        } while (block != null);
      }
    }
  }

  // TODO(mikaelpeltier) Manage that from and to instruction do not belong to the same block.
  private static boolean hasLocalOrLineChangeBetween(
          com.debughelper.tools.r8.ir.code.Instruction from, com.debughelper.tools.r8.ir.code.Instruction to, com.debughelper.tools.r8.graph.DexString localVar) {
    if (from.getBlock() != to.getBlock()) {
      return true;
    }
    if (from.getPosition().isSome()
        && to.getPosition().isSome()
        && !from.getPosition().equals(to.getPosition())) {
      return true;
    }
    com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = from.getBlock().listIterator(from);
    com.debughelper.tools.r8.ir.code.Position position = null;
    while (iterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction instruction = iterator.next();
      if (position == null) {
        if (instruction.getPosition().isSome()) {
          position = instruction.getPosition();
        }
      } else if (instruction.getPosition().isSome()
          && !position.equals(instruction.getPosition())) {
        return true;
      }
      if (instruction == to) {
        return false;
      }
      if (instruction.outValue() != null && instruction.outValue().hasLocalInfo()) {
        if (instruction.outValue().getLocalInfo().name == localVar) {
          return true;
        }
      }
    }
    throw new Unreachable();
  }

  public void simplifyDebugLocals(com.debughelper.tools.r8.ir.code.IRCode code) {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      for (com.debughelper.tools.r8.ir.code.Phi phi : block.getPhis()) {
        if (!phi.hasLocalInfo() && phi.numberOfUsers() == 1 && phi.numberOfAllUsers() == 1) {
          com.debughelper.tools.r8.ir.code.Instruction instruction = phi.uniqueUsers().iterator().next();
          if (instruction.isDebugLocalWrite()) {
            removeDebugWriteOfPhi(phi, instruction.asDebugLocalWrite());
          }
        }
      }

      com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction prevInstruction = iterator.peekPrevious();
        com.debughelper.tools.r8.ir.code.Instruction instruction = iterator.next();
        if (instruction.isDebugLocalWrite()) {
          assert instruction.inValues().size() == 1;
          com.debughelper.tools.r8.ir.code.Value inValue = instruction.inValues().get(0);
          DebugLocalInfo localInfo = instruction.outValue().getLocalInfo();
          com.debughelper.tools.r8.graph.DexString localName = localInfo.name;
          if (!inValue.hasLocalInfo() &&
              inValue.numberOfAllUsers() == 1 &&
              inValue.definition != null &&
              !hasLocalOrLineChangeBetween(inValue.definition, instruction, localName)) {
            inValue.setLocalInfo(localInfo);
            instruction.outValue().replaceUsers(inValue);
            com.debughelper.tools.r8.ir.code.Value overwrittenLocal = instruction.removeDebugValue(localInfo);
            if (overwrittenLocal != null) {
              inValue.definition.addDebugValue(overwrittenLocal);
            }
            if (prevInstruction != null) {
              instruction.moveDebugValues(prevInstruction);
            }
            iterator.removeOrReplaceByDebugLocalRead();
          }
        }
      }
    }
  }

  private void removeDebugWriteOfPhi(com.debughelper.tools.r8.ir.code.Phi phi, DebugLocalWrite write) {
    assert write.src() == phi;
    for (com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = phi.getBlock().listIterator(); iterator.hasNext(); ) {
      com.debughelper.tools.r8.ir.code.Instruction next = iterator.next();
      if (!next.isDebugLocalWrite()) {
        // If the debug write is not in the block header bail out.
        return;
      }
      if (next == write) {
        // Associate the phi with the local.
        phi.setLocalInfo(write.getLocalInfo());
        // Replace uses of the write with the phi.
        write.outValue().replaceUsers(phi);
        // Safely remove the write.
        // TODO(zerny): Once phis become instructions, move debug values there instead of a nop.
        iterator.removeOrReplaceByDebugLocalRead();
        return;
      }
      assert next.getLocalInfo().name != write.getLocalInfo().name;
    }
  }

  private static class CSEExpressionEquivalence extends Equivalence<com.debughelper.tools.r8.ir.code.Instruction> {

    private final com.debughelper.tools.r8.ir.code.IRCode code;

    private CSEExpressionEquivalence(com.debughelper.tools.r8.ir.code.IRCode code) {
      this.code = code;
    }

    @Override
    protected boolean doEquivalent(com.debughelper.tools.r8.ir.code.Instruction a, com.debughelper.tools.r8.ir.code.Instruction b) {
      // Some Dalvik VMs incorrectly handle Cmp instructions which leads to a requirement
      // that we do not perform common subexpression elimination for them. See comment on
      // canHaveCmpLongBug for details.
      if (a.isCmp() && code.options.canHaveCmpLongBug()) {
        return false;
      }
      // Note that we don't consider positions because CSE can at most remove an instruction.
      if (!a.identicalNonValueNonPositionParts(b)) {
        return false;
      }
      // For commutative binary operations any order of in-values are equal.
      if (a.isBinop() && a.asBinop().isCommutative()) {
        com.debughelper.tools.r8.ir.code.Value a0 = a.inValues().get(0);
        com.debughelper.tools.r8.ir.code.Value a1 = a.inValues().get(1);
        com.debughelper.tools.r8.ir.code.Value b0 = b.inValues().get(0);
        com.debughelper.tools.r8.ir.code.Value b1 = b.inValues().get(1);
        return (identicalValue(a0, b0) && identicalValue(a1, b1))
            || (identicalValue(a0, b1) && identicalValue(a1, b0));
      } else {
        // Compare all in-values.
        assert a.inValues().size() == b.inValues().size();
        for (int i = 0; i < a.inValues().size(); i++) {
          if (!identicalValue(a.inValues().get(i), b.inValues().get(i))) {
            return false;
          }
        }
        return true;
      }
    }

    @Override
    protected int doHash(com.debughelper.tools.r8.ir.code.Instruction instruction) {
      final int prime = 29;
      int hash = instruction.getClass().hashCode();
      if (instruction.isBinop()) {
        Binop binop = instruction.asBinop();
        com.debughelper.tools.r8.ir.code.Value in0 = instruction.inValues().get(0);
        com.debughelper.tools.r8.ir.code.Value in1 = instruction.inValues().get(1);
        if (binop.isCommutative()) {
          hash += hash * prime + getHashCode(in0) * getHashCode(in1);
        } else {
          hash += hash * prime + getHashCode(in0);
          hash += hash * prime + getHashCode(in1);
        }
        return hash;
      } else {
        for (com.debughelper.tools.r8.ir.code.Value value : instruction.inValues()) {
          hash += hash * prime + getHashCode(value);
        }
      }
      return hash;
    }

    private static boolean identicalValue(com.debughelper.tools.r8.ir.code.Value a, com.debughelper.tools.r8.ir.code.Value b) {
      if (a.equals(b)) {
        return true;
      }
      if (a.isConstNumber() && b.isConstNumber()) {
        // Do not take assumption that constants are canonicalized.
        return a.definition.identicalNonValueNonPositionParts(b.definition);
      }
      return false;
    }

    private static int getHashCode(com.debughelper.tools.r8.ir.code.Value a) {
      if (a.isConstNumber()) {
        // Do not take assumption that constants are canonicalized.
        return Long.hashCode(a.definition.asConstNumber().getRawValue());
      }
      return a.hashCode();
    }
  }

  private boolean shareCatchHandlers(com.debughelper.tools.r8.ir.code.Instruction i0, com.debughelper.tools.r8.ir.code.Instruction i1) {
    if (!i0.instructionTypeCanThrow()) {
      assert !i1.instructionTypeCanThrow();
      return true;
    }
    assert i1.instructionTypeCanThrow();
    // TODO(sgjesse): This could be even better by checking for the exceptions thrown, e.g. div
    // and rem only ever throw ArithmeticException.
    com.debughelper.tools.r8.ir.code.CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock> ch0 = i0.getBlock().getCatchHandlers();
    CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock> ch1 = i1.getBlock().getCatchHandlers();
    return ch0.equals(ch1);
  }

  private boolean isCSEInstructionCandidate(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    return (instruction.isBinop()
        || instruction.isUnop()
        || instruction.isInstanceOf()
        || instruction.isCheckCast())
        && instruction.getLocalInfo() == null
        && !instruction.hasInValueWithLocalInfo();
  }

  private boolean hasCSECandidate(com.debughelper.tools.r8.ir.code.IRCode code, int noCandidate) {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        if (isCSEInstructionCandidate(iterator.next())) {
          return true;
        }
      }
      block.mark(noCandidate);
    }
    return false;
  }

  public void commonSubexpressionElimination(com.debughelper.tools.r8.ir.code.IRCode code) {
    int noCandidate = code.reserveMarkingColor();
    if (hasCSECandidate(code, noCandidate)) {
      final ListMultimap<Wrapper<com.debughelper.tools.r8.ir.code.Instruction>, com.debughelper.tools.r8.ir.code.Value> instructionToValue =
          ArrayListMultimap.create();
      final CSEExpressionEquivalence equivalence = new CSEExpressionEquivalence(code);
      final com.debughelper.tools.r8.ir.code.DominatorTree dominatorTree = new DominatorTree(code);
      for (int i = 0; i < dominatorTree.getSortedBlocks().length; i++) {
        com.debughelper.tools.r8.ir.code.BasicBlock block = dominatorTree.getSortedBlocks()[i];
        if (block.isMarked(noCandidate)) {
          continue;
        }
        com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = block.listIterator();
        while (iterator.hasNext()) {
          com.debughelper.tools.r8.ir.code.Instruction instruction = iterator.next();
          if (isCSEInstructionCandidate(instruction)) {
            List<com.debughelper.tools.r8.ir.code.Value> candidates = instructionToValue.get(equivalence.wrap(instruction));
            boolean eliminated = false;
            if (candidates.size() > 0) {
              for (com.debughelper.tools.r8.ir.code.Value candidate : candidates) {
                if (dominatorTree.dominatedBy(block, candidate.definition.getBlock())
                    && shareCatchHandlers(instruction, candidate.definition)) {
                  instruction.outValue().replaceUsers(candidate);
                  eliminated = true;
                  iterator.removeOrReplaceByDebugLocalRead();
                  break;  // Don't try any more candidates.
                }
              }
            }
            if (!eliminated) {
              instructionToValue.put(equivalence.wrap(instruction), instruction.outValue());
            }
          }
        }
      }
    }
    code.returnMarkingColor(noCandidate);
    assert code.isConsistentSSA();
  }

  public void simplifyIf(com.debughelper.tools.r8.ir.code.IRCode code, TypeEnvironment typeEnvironment) {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      // Skip removed (= unreachable) blocks.
      if (block.getNumber() != 0 && block.getPredecessors().isEmpty()) {
        continue;
      }
      if (block.exit().isIf()) {
        flipIfBranchesIfNeeded(block);
        rewriteIfWithConstZero(block);

        if (simplifyKnownBooleanCondition(code, block)) {
          continue;
        }

        // Simplify if conditions when possible.
        com.debughelper.tools.r8.ir.code.If theIf = block.exit().asIf();
        List<com.debughelper.tools.r8.ir.code.Value> inValues = theIf.inValues();

        if (inValues.get(0).isConstNumber()
            && (theIf.isZeroTest() || inValues.get(1).isConstNumber())) {
          // Zero test with a constant of comparison between between two constants.
          if (theIf.isZeroTest()) {
            com.debughelper.tools.r8.ir.code.ConstNumber cond = inValues.get(0).getConstInstruction().asConstNumber();
            com.debughelper.tools.r8.ir.code.BasicBlock target = theIf.targetFromCondition(cond);
            simplifyIfWithKnownCondition(code, block, theIf, target);
          } else {
            com.debughelper.tools.r8.ir.code.ConstNumber left = inValues.get(0).getConstInstruction().asConstNumber();
            com.debughelper.tools.r8.ir.code.ConstNumber right = inValues.get(1).getConstInstruction().asConstNumber();
            com.debughelper.tools.r8.ir.code.BasicBlock target = theIf.targetFromCondition(left, right);
            simplifyIfWithKnownCondition(code, block, theIf, target);
          }
        } else if (inValues.get(0).hasValueRange()
            && (theIf.isZeroTest() || inValues.get(1).hasValueRange())) {
          // Zero test with a value range, or comparison between between two values,
          // each with a value ranges.
          if (theIf.isZeroTest()) {
            if (!inValues.get(0).isValueInRange(0)) {
              int cond = Long.signum(inValues.get(0).getValueRange().getMin());
              simplifyIfWithKnownCondition(code, block, theIf, cond);
            }
          } else {
            com.debughelper.tools.r8.utils.LongInterval leftRange = inValues.get(0).getValueRange();
            LongInterval rightRange = inValues.get(1).getValueRange();
            if (!leftRange.overlapsWith(rightRange)) {
              int cond = Long.signum(leftRange.getMin() - rightRange.getMin());
              simplifyIfWithKnownCondition(code, block, theIf, cond);
            }
          }
        } else if (theIf.isZeroTest() && !inValues.get(0).isConstNumber()
            && (theIf.getType() == com.debughelper.tools.r8.ir.code.If.Type.EQ || theIf.getType() == com.debughelper.tools.r8.ir.code.If.Type.NE)) {
          if (inValues.get(0).isNeverNull()) {
            simplifyIfWithKnownCondition(code, block, theIf, 1);
          } else {
            // TODO(b/72693244): annotate type lattice to value
            TypeLatticeElement l = typeEnvironment.getLatticeElement(inValues.get(0));
            if (!l.isPrimitive() && !l.isNullable()) {
              simplifyIfWithKnownCondition(code, block, theIf, 1);
            }
          }
        }
      }
    }
    code.removeUnreachableBlocks();
    assert code.isConsistentSSA();
  }

  private void simplifyIfWithKnownCondition(
          com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.BasicBlock block, com.debughelper.tools.r8.ir.code.If theIf, com.debughelper.tools.r8.ir.code.BasicBlock target) {
    com.debughelper.tools.r8.ir.code.BasicBlock deadTarget =
        target == theIf.getTrueTarget() ? theIf.fallthroughBlock() : theIf.getTrueTarget();
    rewriteIfToGoto(code, block, theIf, target, deadTarget);
  }

  private void simplifyIfWithKnownCondition(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.BasicBlock block, com.debughelper.tools.r8.ir.code.If theIf, int cond) {
    simplifyIfWithKnownCondition(code, block, theIf, theIf.targetFromCondition(cond));
  }

  // Find all method invocations that never returns normally, split the block
  // after each such invoke instruction and follow it with a block throwing a
  // null value (which should result in NPE). Note that this throw is not
  // expected to be ever reached, but is intended to satisfy verifier.
  public void processMethodsNeverReturningNormally(com.debughelper.tools.r8.ir.code.IRCode code) {
    Enqueuer.AppInfoWithLiveness appInfoWithLiveness = appInfo.withLiveness();
    if (appInfoWithLiveness == null) {
      return;
    }

    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blockIterator = code.listIterator();
    while (blockIterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = blockIterator.next();
      if (block.getNumber() != 0 && block.getPredecessors().isEmpty()) {
        continue;
      }
      com.debughelper.tools.r8.ir.code.InstructionListIterator insnIterator = block.listIterator();
      while (insnIterator.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction insn = insnIterator.next();
        if (!insn.isInvokeMethod()) {
          continue;
        }

        com.debughelper.tools.r8.graph.DexEncodedMethod singleTarget =
            insn.asInvokeMethod().computeSingleTarget(appInfoWithLiveness);
        if (singleTarget == null || !singleTarget.getOptimizationInfo().neverReturnsNormally()) {
          continue;
        }

        // Split the block.
        {
          com.debughelper.tools.r8.ir.code.BasicBlock newBlock = insnIterator.split(code, blockIterator);
          assert !insnIterator.hasNext(); // must be pointing *after* inserted GoTo.
          // Move block iterator back so current block is 'newBlock'.
          blockIterator.previous();

          newBlock.unlinkSinglePredecessorSiblingsAllowed();
        }

        // We want to follow the invoke instruction with 'throw null', which should
        // be unreachable but is needed to satisfy the verifier. Note that we have
        // to put 'throw null' into a separate block to make sure we don't get two
        // throwing instructions in the block having catch handler. This new block
        // does not need catch handlers.
        com.debughelper.tools.r8.ir.code.Instruction gotoInsn = insnIterator.previous();
        assert gotoInsn.isGoto();
        assert insnIterator.hasNext();
        com.debughelper.tools.r8.ir.code.BasicBlock throwNullBlock = insnIterator.split(code, blockIterator);
        com.debughelper.tools.r8.ir.code.InstructionListIterator throwNullInsnIterator = throwNullBlock.listIterator();

        // Insert 'null' constant.
        com.debughelper.tools.r8.ir.code.Value nullValue = code.createValue(com.debughelper.tools.r8.ir.code.ValueType.OBJECT, gotoInsn.getLocalInfo());
        com.debughelper.tools.r8.ir.code.ConstNumber nullConstant = new com.debughelper.tools.r8.ir.code.ConstNumber(nullValue, 0);
        nullConstant.setPosition(insn.getPosition());
        throwNullInsnIterator.add(nullConstant);

        // Replace Goto with Throw.
        com.debughelper.tools.r8.ir.code.Throw notReachableThrow = new Throw(nullValue);
        com.debughelper.tools.r8.ir.code.Instruction insnGoto = throwNullInsnIterator.next();
        assert insnGoto.isGoto();
        throwNullInsnIterator.replaceCurrentInstruction(notReachableThrow);
        // Use position from original invoke to guarantee it has a real position.
        notReachableThrow.forceSetPosition(insn.getPosition());
      }
    }
    code.removeUnreachableBlocks();
    assert code.isConsistentSSA();
  }

  /* Identify simple diamond shapes converting boolean true/false to 1/0. We consider the forms:
   *
   * (1)
   *
   *      [dbg pos x]             [dbg pos x]
   *   ifeqz booleanValue       ifnez booleanValue
   *      /        \              /        \
   * [dbg pos x][dbg pos x]  [dbg pos x][dbg pos x]
   *  [const 0]  [const 1]    [const 1]  [const 0]
   *    goto      goto          goto      goto
   *      \        /              \        /
   *      phi(0, 1)                phi(1, 0)
   *
   * which can be replaced by a fallthrough and the phi value can be replaced
   * with the boolean value itself.
   *
   * (2)
   *
   *      [dbg pos x]              [dbg pos x]
   *    ifeqz booleanValue       ifnez booleanValue
   *      /        \              /        \
   * [dbg pos x][dbg pos x]  [dbg pos x][dbg pos x]
   *  [const 1]  [const 0]   [const 0]  [const 1]
   *    goto      goto          goto      goto
   *      \        /              \        /
   *      phi(1, 0)                phi(0, 1)
   *
   * which can be replaced by a fallthrough and the phi value can be replaced
   * by an xor instruction which is smaller.
   */
  private boolean simplifyKnownBooleanCondition(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.BasicBlock block) {
    com.debughelper.tools.r8.ir.code.If theIf = block.exit().asIf();
    com.debughelper.tools.r8.ir.code.Value testValue = theIf.inValues().get(0);
    if (theIf.isZeroTest() && testValue.knownToBeBoolean()) {
      com.debughelper.tools.r8.ir.code.BasicBlock trueBlock = theIf.getTrueTarget();
      com.debughelper.tools.r8.ir.code.BasicBlock falseBlock = theIf.fallthroughBlock();
      if (isBlockSupportedBySimplifyKnownBooleanCondition(trueBlock) &&
          isBlockSupportedBySimplifyKnownBooleanCondition(falseBlock) &&
          trueBlock.getSuccessors().get(0) == falseBlock.getSuccessors().get(0)) {
        com.debughelper.tools.r8.ir.code.BasicBlock targetBlock = trueBlock.getSuccessors().get(0);
        if (targetBlock.getPredecessors().size() == 2) {
          int trueIndex = targetBlock.getPredecessors().indexOf(trueBlock);
          int falseIndex = trueIndex == 0 ? 1 : 0;
          int deadPhis = 0;
          // Locate the phis that have the same value as the boolean and replace them
          // by the boolean in all users.
          for (com.debughelper.tools.r8.ir.code.Phi phi : targetBlock.getPhis()) {
            com.debughelper.tools.r8.ir.code.Value trueValue = phi.getOperand(trueIndex);
            com.debughelper.tools.r8.ir.code.Value falseValue = phi.getOperand(falseIndex);
            if (trueValue.isConstNumber() && falseValue.isConstNumber()) {
              com.debughelper.tools.r8.ir.code.ConstNumber trueNumber = trueValue.getConstInstruction().asConstNumber();
              com.debughelper.tools.r8.ir.code.ConstNumber falseNumber = falseValue.getConstInstruction().asConstNumber();
              if ((theIf.getType() == com.debughelper.tools.r8.ir.code.If.Type.EQ &&
                  trueNumber.isIntegerZero() &&
                  falseNumber.isIntegerOne()) ||
                  (theIf.getType() == com.debughelper.tools.r8.ir.code.If.Type.NE &&
                      trueNumber.isIntegerOne() &&
                      falseNumber.isIntegerZero())) {
                phi.replaceUsers(testValue);
                deadPhis++;
              } else if ((theIf.getType() == com.debughelper.tools.r8.ir.code.If.Type.NE &&
                           trueNumber.isIntegerZero() &&
                           falseNumber.isIntegerOne()) ||
                         (theIf.getType() == com.debughelper.tools.r8.ir.code.If.Type.EQ &&
                           trueNumber.isIntegerOne() &&
                           falseNumber.isIntegerZero())) {
                com.debughelper.tools.r8.ir.code.Value newOutValue = code.createValue(phi.outType(), phi.getLocalInfo());
                com.debughelper.tools.r8.ir.code.ConstNumber cstToUse = trueNumber.isIntegerOne() ? trueNumber : falseNumber;
                com.debughelper.tools.r8.ir.code.BasicBlock phiBlock = phi.getBlock();
                com.debughelper.tools.r8.ir.code.Position phiPosition = phiBlock.getPosition();
                int insertIndex = 0;
                if (cstToUse.getBlock() == trueBlock || cstToUse.getBlock() == falseBlock) {
                  // The constant belongs to the block to remove, create a new one.
                  cstToUse = com.debughelper.tools.r8.ir.code.ConstNumber.copyOf(code, cstToUse);
                  cstToUse.setBlock(phiBlock);
                  cstToUse.setPosition(phiPosition);
                  phiBlock.getInstructions().add(insertIndex++, cstToUse);
                }
                phi.replaceUsers(newOutValue);
                com.debughelper.tools.r8.ir.code.Instruction newInstruction = new Xor(com.debughelper.tools.r8.ir.code.NumericType.INT, newOutValue, testValue,
                    cstToUse.outValue());
                newInstruction.setBlock(phiBlock);
                // The xor is replacing a phi so it does not have an actual position.
                newInstruction.setPosition(phiPosition);
                phiBlock.getInstructions().add(insertIndex, newInstruction);
                deadPhis++;
              }
            }
          }
          // If all phis were removed, there is no need for the diamond shape anymore
          // and it can be rewritten to a goto to one of the branches.
          if (deadPhis == targetBlock.getPhis().size()) {
            rewriteIfToGoto(code, block, theIf, trueBlock, falseBlock);
            return true;
          }
        }
      }
    }
    return false;
  }

  private boolean isBlockSupportedBySimplifyKnownBooleanCondition(com.debughelper.tools.r8.ir.code.BasicBlock b) {
    if (b.isTrivialGoto()) {
      return true;
    }

    int instructionSize = b.getInstructions().size();
    if (b.exit().isGoto() && (instructionSize == 2 || instructionSize == 3)) {
      com.debughelper.tools.r8.ir.code.Instruction constInstruction = b.getInstructions().get(instructionSize - 2);
      if (constInstruction.isConstNumber()) {
        if (!constInstruction.asConstNumber().isIntegerOne() &&
            !constInstruction.asConstNumber().isIntegerZero()) {
          return false;
        }
        if (instructionSize == 2) {
          return true;
        }
        com.debughelper.tools.r8.ir.code.Instruction firstInstruction = b.getInstructions().getFirst();
        if (firstInstruction.isDebugPosition()) {
          assert b.getPredecessors().size() == 1;
          com.debughelper.tools.r8.ir.code.BasicBlock predecessorBlock = b.getPredecessors().get(0);
          com.debughelper.tools.r8.ir.code.InstructionListIterator it = predecessorBlock.listIterator(predecessorBlock.exit());
          com.debughelper.tools.r8.ir.code.Instruction previousPosition = null;
          while (it.hasPrevious() && !(previousPosition = it.previous()).isDebugPosition());
          if (previousPosition != null) {
            return previousPosition.getPosition() == firstInstruction.getPosition();
          }
        }
      }
    }

    return false;
  }

  private void rewriteIfToGoto(
          com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.BasicBlock block, com.debughelper.tools.r8.ir.code.If theIf, com.debughelper.tools.r8.ir.code.BasicBlock target, com.debughelper.tools.r8.ir.code.BasicBlock deadTarget) {
    deadTarget.unlinkSinglePredecessorSiblingsAllowed();
    assert theIf == block.exit();
    block.replaceLastInstruction(new Goto());
    assert block.exit().isGoto();
    assert block.exit().asGoto().getTarget() == target;
  }

  private void rewriteIfWithConstZero(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    com.debughelper.tools.r8.ir.code.If theIf = block.exit().asIf();
    if (theIf.isZeroTest()) {
      return;
    }

    List<com.debughelper.tools.r8.ir.code.Value> inValues = theIf.inValues();
    com.debughelper.tools.r8.ir.code.Value leftValue = inValues.get(0);
    com.debughelper.tools.r8.ir.code.Value rightValue = inValues.get(1);
    if (leftValue.isConstNumber() || rightValue.isConstNumber()) {
      if (leftValue.isConstNumber()) {
        if (leftValue.getConstInstruction().asConstNumber().isZero()) {
          com.debughelper.tools.r8.ir.code.If ifz = new com.debughelper.tools.r8.ir.code.If(theIf.getType().forSwappedOperands(), rightValue);
          block.replaceLastInstruction(ifz);
          assert block.exit() == ifz;
        }
      } else {
        if (rightValue.getConstInstruction().asConstNumber().isZero()) {
          com.debughelper.tools.r8.ir.code.If ifz = new com.debughelper.tools.r8.ir.code.If(theIf.getType(), leftValue);
          block.replaceLastInstruction(ifz);
          assert block.exit() == ifz;
        }
      }
    }
  }

  private boolean flipIfBranchesIfNeeded(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    com.debughelper.tools.r8.ir.code.If theIf = block.exit().asIf();
    com.debughelper.tools.r8.ir.code.BasicBlock trueTarget = theIf.getTrueTarget();
    com.debughelper.tools.r8.ir.code.BasicBlock fallthrough = theIf.fallthroughBlock();
    assert trueTarget != fallthrough;

    if (!fallthrough.isSimpleAlwaysThrowingPath() || trueTarget.isSimpleAlwaysThrowingPath()) {
      return false;
    }

    // In case fall-through block always throws there is a good chance that it
    // is created for error checks and 'trueTarget' represents most more common
    // non-error case. Flipping the if in this case may result in faster code
    // on older debughelper versions.
    List<com.debughelper.tools.r8.ir.code.Value> inValues = theIf.inValues();
    com.debughelper.tools.r8.ir.code.If newIf = new com.debughelper.tools.r8.ir.code.If(theIf.getType().inverted(), inValues);
    block.replaceLastInstruction(newIf);
    block.swapSuccessors(trueTarget, fallthrough);
    return true;
  }

  public void rewriteLongCompareAndRequireNonNull(com.debughelper.tools.r8.ir.code.IRCode code, InternalOptions options) {
    if (options.canUseLongCompareAndObjectsNonNull()) {
      return;
    }

    InstructionIterator iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction current = iterator.next();
      if (current.isInvokeMethod()) {
        com.debughelper.tools.r8.graph.DexMethod invokedMethod = current.asInvokeMethod().getInvokedMethod();
        if (invokedMethod == dexItemFactory.longMethods.compare) {
          // Rewrite calls to Long.compare for sdk versions that do not have that method.
          List<com.debughelper.tools.r8.ir.code.Value> inValues = current.inValues();
          assert inValues.size() == 2;
          iterator.replaceCurrentInstruction(
              new com.debughelper.tools.r8.ir.code.Cmp(com.debughelper.tools.r8.ir.code.NumericType.LONG, com.debughelper.tools.r8.ir.code.Cmp.Bias.NONE, current.outValue(), inValues.get(0),
                  inValues.get(1)));
        } else if (invokedMethod == dexItemFactory.objectsMethods.requireNonNull) {
          // Rewrite calls to Objects.requireNonNull(Object) because Javac 9 start to use it for
          // synthesized null checks.
          com.debughelper.tools.r8.ir.code.InvokeVirtual callToGetClass = new com.debughelper.tools.r8.ir.code.InvokeVirtual(dexItemFactory.objectMethods.getClass,
              null, current.inValues());
          if (current.outValue() != null) {
            current.outValue().replaceUsers(current.inValues().get(0));
            current.setOutValue(null);
          }
          iterator.replaceCurrentInstruction(callToGetClass);
        }
      }
    }
    assert code.isConsistentSSA();
  }

  // Removes calls to Throwable.addSuppressed(Throwable) and rewrites
  // Throwable.getSuppressed() into new Throwable[0].
  //
  // Note that addSuppressed() and getSuppressed() methods are final in
  // Throwable, so these changes don't have to worry about overrides.
  public void rewriteThrowableAddAndGetSuppressed(com.debughelper.tools.r8.ir.code.IRCode code) {
    com.debughelper.tools.r8.graph.DexItemFactory.ThrowableMethods throwableMethods = dexItemFactory.throwableMethods;

    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction current = iterator.next();
        if (current.isInvokeMethod()) {
          com.debughelper.tools.r8.graph.DexMethod invokedMethod = current.asInvokeMethod().getInvokedMethod();
          if (matchesMethodOfThrowable(invokedMethod, throwableMethods.addSuppressed)) {
            // Remove Throwable::addSuppressed(Throwable) call.
            iterator.removeOrReplaceByDebugLocalRead();
          } else if (matchesMethodOfThrowable(invokedMethod, throwableMethods.getSuppressed)) {
            com.debughelper.tools.r8.ir.code.Value destValue = current.outValue();
            if (destValue == null) {
              // If the result of the call was not used we don't create
              // an empty array and just remove the call.
              iterator.removeOrReplaceByDebugLocalRead();
              continue;
            }

            // Replace call to Throwable::getSuppressed() with new Throwable[0].

            // First insert the constant value *before* the current instruction.
            ConstNumber zero = code.createIntConstant(0);
            zero.setPosition(current.getPosition());
            assert iterator.hasPrevious();
            iterator.previous();
            iterator.add(zero);

            // Then replace the invoke instruction with new-array instruction.
            com.debughelper.tools.r8.ir.code.Instruction next = iterator.next();
            assert current == next;
            com.debughelper.tools.r8.ir.code.NewArrayEmpty newArray = new NewArrayEmpty(destValue, zero.outValue(),
                dexItemFactory.createType(dexItemFactory.throwableArrayDescriptor));
            iterator.replaceCurrentInstruction(newArray);
          }
        }
      }
    }
    assert code.isConsistentSSA();
  }

  private boolean matchesMethodOfThrowable(com.debughelper.tools.r8.graph.DexMethod invoked, com.debughelper.tools.r8.graph.DexMethod expected) {
    return invoked.name == expected.name
        && invoked.proto == expected.proto
        && isSubtypeOfThrowable(invoked.holder);
  }

  private boolean isSubtypeOfThrowable(com.debughelper.tools.r8.graph.DexType type) {
    while (type != null && type != dexItemFactory.objectType) {
      if (type == dexItemFactory.throwableType) {
        return true;
      }
      DexClass dexClass = definitionFor(type);
      if (dexClass == null) {
        throw new com.debughelper.tools.r8.errors.CompilationError("Class or interface " + type.toSourceString() +
            " required for desugaring of try-with-resources is not found.");
      }
      type = dexClass.superType;
    }
    return false;
  }

  private com.debughelper.tools.r8.ir.code.Value addConstString(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.InstructionListIterator iterator, String s) {
    com.debughelper.tools.r8.ir.code.Value value = code.createValue(com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    iterator.add(new com.debughelper.tools.r8.ir.code.ConstString(value, dexItemFactory.createString(s)));
    return value;
  }

  /**
   * Insert code into <code>method</code> to log the argument types to System.out.
   *
   * The type is determined by calling getClass() on the argument.
   */
  public void logArgumentTypes(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code) {
    List<com.debughelper.tools.r8.ir.code.Value> arguments = code.collectArguments();
    com.debughelper.tools.r8.ir.code.BasicBlock block = code.blocks.getFirst();
    com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = block.listIterator();

    // Attach some synthetic position to all inserted code.
    com.debughelper.tools.r8.ir.code.Position position = Position.synthetic(1, method.method, null);
    iterator.setInsertionPosition(position);

    // Split arguments into their own block.
    iterator.nextUntil(instruction -> !instruction.isArgument());
    iterator.previous();
    iterator.split(code);
    iterator.previous();

    // Now that the block is split there should not be any catch handlers in the block.
    assert !block.hasCatchHandlers();
    com.debughelper.tools.r8.ir.code.Value out = code.createValue(com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    com.debughelper.tools.r8.graph.DexType javaLangSystemType = dexItemFactory.createType("Ljava/lang/System;");
    DexType javaIoPrintStreamType = dexItemFactory.createType("Ljava/io/PrintStream;");

    DexProto proto = dexItemFactory.createProto(dexItemFactory.voidType, dexItemFactory.objectType);
    com.debughelper.tools.r8.graph.DexMethod print = dexItemFactory.createMethod(javaIoPrintStreamType, proto, "print");
    com.debughelper.tools.r8.graph.DexMethod printLn = dexItemFactory.createMethod(javaIoPrintStreamType, proto, "println");

    iterator.add(
        new StaticGet(MemberType.OBJECT, out,
            dexItemFactory.createField(javaLangSystemType, javaIoPrintStreamType, "out")));

    com.debughelper.tools.r8.ir.code.Value value = code.createValue(com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    iterator.add(new com.debughelper.tools.r8.ir.code.ConstString(value, dexItemFactory.createString("INVOKE ")));
    iterator.add(new com.debughelper.tools.r8.ir.code.InvokeVirtual(print, null, ImmutableList.of(out, value)));

    value = code.createValue(com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    iterator.add(
        new ConstString(value, dexItemFactory.createString(method.method.qualifiedName())));
    iterator.add(new com.debughelper.tools.r8.ir.code.InvokeVirtual(print, null, ImmutableList.of(out, value)));

    com.debughelper.tools.r8.ir.code.Value openParenthesis = addConstString(code, iterator, "(");
    com.debughelper.tools.r8.ir.code.Value comma = addConstString(code, iterator, ",");
    com.debughelper.tools.r8.ir.code.Value closeParenthesis = addConstString(code, iterator, ")");
    com.debughelper.tools.r8.ir.code.Value indent = addConstString(code, iterator, "  ");
    com.debughelper.tools.r8.ir.code.Value nul = addConstString(code, iterator, "(null)");
    com.debughelper.tools.r8.ir.code.Value primitive = addConstString(code, iterator, "(primitive)");
    com.debughelper.tools.r8.ir.code.Value empty = addConstString(code, iterator, "");

    iterator.add(new com.debughelper.tools.r8.ir.code.InvokeVirtual(printLn, null, ImmutableList.of(out, openParenthesis)));
    for (int i = 0; i < arguments.size(); i++) {
      iterator.add(new com.debughelper.tools.r8.ir.code.InvokeVirtual(print, null, ImmutableList.of(out, indent)));

      // Add a block for end-of-line printing.
      com.debughelper.tools.r8.ir.code.BasicBlock eol = com.debughelper.tools.r8.ir.code.BasicBlock.createGotoBlock(code.blocks.size());
      code.blocks.add(eol);

      com.debughelper.tools.r8.ir.code.BasicBlock successor = block.unlinkSingleSuccessor();
      block.link(eol);
      eol.link(successor);

      com.debughelper.tools.r8.ir.code.Value argument = arguments.get(i);
      if (argument.outType() != com.debughelper.tools.r8.ir.code.ValueType.OBJECT) {
        iterator.add(new com.debughelper.tools.r8.ir.code.InvokeVirtual(print, null, ImmutableList.of(out, primitive)));
      } else {
        // Insert "if (argument != null) ...".
        successor = block.unlinkSingleSuccessor();
        com.debughelper.tools.r8.ir.code.If theIf = new com.debughelper.tools.r8.ir.code.If(com.debughelper.tools.r8.ir.code.If.Type.NE, argument);
        theIf.setPosition(position);
        com.debughelper.tools.r8.ir.code.BasicBlock ifBlock = com.debughelper.tools.r8.ir.code.BasicBlock.createIfBlock(code.blocks.size(), theIf);
        code.blocks.add(ifBlock);
        // Fallthrough block must be added right after the if.
        com.debughelper.tools.r8.ir.code.BasicBlock isNullBlock = com.debughelper.tools.r8.ir.code.BasicBlock.createGotoBlock(code.blocks.size());
        code.blocks.add(isNullBlock);
        com.debughelper.tools.r8.ir.code.BasicBlock isNotNullBlock = com.debughelper.tools.r8.ir.code.BasicBlock.createGotoBlock(code.blocks.size());
        code.blocks.add(isNotNullBlock);

        // Link the added blocks together.
        block.link(ifBlock);
        ifBlock.link(isNotNullBlock);
        ifBlock.link(isNullBlock);
        isNotNullBlock.link(successor);
        isNullBlock.link(successor);

        // Fill code into the blocks.
        iterator = isNullBlock.listIterator();
        iterator.setInsertionPosition(position);
        iterator.add(new com.debughelper.tools.r8.ir.code.InvokeVirtual(print, null, ImmutableList.of(out, nul)));
        iterator = isNotNullBlock.listIterator();
        iterator.setInsertionPosition(position);
        value = code.createValue(ValueType.OBJECT);
        iterator.add(new com.debughelper.tools.r8.ir.code.InvokeVirtual(dexItemFactory.objectMethods.getClass, value,
            ImmutableList.of(arguments.get(i))));
        iterator.add(new com.debughelper.tools.r8.ir.code.InvokeVirtual(print, null, ImmutableList.of(out, value)));
      }

      iterator = eol.listIterator();
      iterator.setInsertionPosition(position);
      if (i == arguments.size() - 1) {
        iterator.add(new com.debughelper.tools.r8.ir.code.InvokeVirtual(printLn, null, ImmutableList.of(out, closeParenthesis)));
      } else {
        iterator.add(new com.debughelper.tools.r8.ir.code.InvokeVirtual(printLn, null, ImmutableList.of(out, comma)));
      }
      block = eol;
    }
    // When we fall out of the loop the iterator is in the last eol block.
    iterator.add(new InvokeVirtual(printLn, null, ImmutableList.of(out, empty)));
  }

  public static void ensureDirectStringNewToInit(com.debughelper.tools.r8.ir.code.IRCode code) {
    DexItemFactory factory = code.options.itemFactory;
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      for (com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator(); it.hasNext(); ) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
        if (instruction.isInvokeDirect()) {
          InvokeDirect invoke = instruction.asInvokeDirect();
          com.debughelper.tools.r8.graph.DexMethod method = invoke.getInvokedMethod();
          if (factory.isConstructor(method)
              && method.holder == factory.stringType
              && invoke.getReceiver().isPhi()) {
            com.debughelper.tools.r8.ir.code.NewInstance newInstance = findNewInstance(invoke.getReceiver().asPhi());
            replaceTrivialNewInstancePhis(newInstance.outValue());
            if (invoke.getReceiver().isPhi()) {
              throw new com.debughelper.tools.r8.errors.CompilationError(
                  "Failed to remove trivial phis between new-instance and <init>");
            }
            newInstance.markNoSpilling();
          }
        }
      }
    }
  }

  private static NewInstance findNewInstance(com.debughelper.tools.r8.ir.code.Phi phi) {
    Set<com.debughelper.tools.r8.ir.code.Phi> seen = new HashSet<>();
    Set<com.debughelper.tools.r8.ir.code.Value> values = new HashSet<>();
    recursiveAddOperands(phi, seen, values);
    if (values.size() != 1) {
      throw new com.debughelper.tools.r8.errors.CompilationError("Failed to identify unique new-instance for <init>");
    }
    com.debughelper.tools.r8.ir.code.Value newInstanceValue = values.iterator().next();
    if (newInstanceValue.definition == null || !newInstanceValue.definition.isNewInstance()) {
      throw new CompilationError("Invalid defining value for call to <init>");
    }
    return newInstanceValue.definition.asNewInstance();
  }

  private static void recursiveAddOperands(com.debughelper.tools.r8.ir.code.Phi phi, Set<com.debughelper.tools.r8.ir.code.Phi> seen, Set<com.debughelper.tools.r8.ir.code.Value> values) {
    for (com.debughelper.tools.r8.ir.code.Value operand : phi.getOperands()) {
      if (!operand.isPhi()) {
        values.add(operand);
      } else {
        com.debughelper.tools.r8.ir.code.Phi phiOp = operand.asPhi();
        if (seen.add(phiOp)) {
          recursiveAddOperands(phiOp, seen, values);
        }
      }
    }
  }

  // If an <init> call takes place on a phi the code must contain an irreducible loop between the
  // new-instance and the <init>. Assuming the code is verifiable, new-instance must flow to a
  // unique <init>. Here we compute the set of strongly connected phis making use of the
  // new-instance value and replace all trivial ones by the new-instance value.
  // This is a simplified variant of the removeRedundantPhis algorithm in Section 3.2 of:
  // http://compilers.cs.uni-saarland.de/papers/bbhlmz13cc.pdf
  private static void replaceTrivialNewInstancePhis(com.debughelper.tools.r8.ir.code.Value newInstanceValue) {
    List<Set<com.debughelper.tools.r8.ir.code.Value>> components = new SCC().computeSCC(newInstanceValue);
    for (int i = components.size() - 1; i >= 0; i--) {
      Set<com.debughelper.tools.r8.ir.code.Value> component = components.get(i);
      if (component.size() == 1 && component.iterator().next() == newInstanceValue) {
        continue;
      }
      Set<com.debughelper.tools.r8.ir.code.Phi> trivialPhis = new HashSet<>();
      for (com.debughelper.tools.r8.ir.code.Value value : component) {
        boolean isTrivial = true;
        com.debughelper.tools.r8.ir.code.Phi p = value.asPhi();
        for (com.debughelper.tools.r8.ir.code.Value op : p.getOperands()) {
          if (op != newInstanceValue && !component.contains(op)) {
            isTrivial = false;
            break;
          }
        }
        if (isTrivial) {
          trivialPhis.add(p);
        }
      }
      for (com.debughelper.tools.r8.ir.code.Phi trivialPhi : trivialPhis) {
        for (com.debughelper.tools.r8.ir.code.Value op : trivialPhi.getOperands()) {
          op.removePhiUser(trivialPhi);
        }
        trivialPhi.replaceUsers(newInstanceValue);
        trivialPhi.getBlock().removePhi(trivialPhi);
      }
    }
  }

  // Dijkstra's path-based strongly-connected components algorithm.
  // https://en.wikipedia.org/wiki/Path-based_strong_component_algorithm
  private static class SCC {

    private int currentTime = 0;
    private final Reference2IntMap<com.debughelper.tools.r8.ir.code.Value> discoverTime = new Reference2IntOpenHashMap<>();
    private final Set<com.debughelper.tools.r8.ir.code.Value> unassignedSet = new HashSet<>();
    private final Deque<com.debughelper.tools.r8.ir.code.Value> unassignedStack = new ArrayDeque<>();
    private final Deque<com.debughelper.tools.r8.ir.code.Value> preorderStack = new ArrayDeque<>();
    private final List<Set<com.debughelper.tools.r8.ir.code.Value>> components = new ArrayList<>();

    public List<Set<com.debughelper.tools.r8.ir.code.Value>> computeSCC(com.debughelper.tools.r8.ir.code.Value v) {
      assert currentTime == 0;
      dfs(v);
      return components;
    }

    private void dfs(com.debughelper.tools.r8.ir.code.Value value) {
      discoverTime.put(value, currentTime++);
      unassignedSet.add(value);
      unassignedStack.push(value);
      preorderStack.push(value);
      for (Phi phi : value.uniquePhiUsers()) {
        if (!discoverTime.containsKey(phi)) {
          // If not seen yet, continue the search.
          dfs(phi);
        } else if (unassignedSet.contains(phi)) {
          // If seen already and the element is on the unassigned stack we have found a cycle.
          // Pop off everything discovered later than the target from the preorder stack. This may
          // not coincide with the cycle as an outer cycle may already have popped elements off.
          int discoverTimeOfPhi = discoverTime.getInt(phi);
          while (discoverTimeOfPhi < discoverTime.getInt(preorderStack.peek())) {
            preorderStack.pop();
          }
        }
      }
      if (preorderStack.peek() == value) {
        // If the current element is the top of the preorder stack, then we are at entry to a
        // strongly-connected component consisting of this element and every element above this
        // element on the stack.
        Set<com.debughelper.tools.r8.ir.code.Value> component = new HashSet<>(unassignedStack.size());
        while (true) {
          com.debughelper.tools.r8.ir.code.Value member = unassignedStack.pop();
          unassignedSet.remove(member);
          component.add(member);
          if (member == value) {
            components.add(component);
            break;
          }
        }
        preorderStack.pop();
      }
    }
  }

  // See comment for InternalOptions.canHaveNumberConversionRegisterAllocationBug().
  public void workaroundNumberConversionRegisterAllocationBug(com.debughelper.tools.r8.ir.code.IRCode code) {
    final Supplier<DexMethod> javaLangDoubleisNaN = Suppliers.memoize(() ->
     dexItemFactory.createMethod(
        dexItemFactory.createString("Ljava/lang/Double;"),
        dexItemFactory.createString("isNaN"),
        dexItemFactory.booleanDescriptor,
        new DexString[]{dexItemFactory.doubleDescriptor}));

    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocks = code.listIterator();
    while (blocks.hasNext()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = blocks.next();
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
        if (instruction.isArithmeticBinop() || instruction.isNeg()) {
          for (Value value : instruction.inValues()) {
            // Insert a call to Double.isNaN on each value which come from a number conversion
            // to double and flows into an arithmetic instruction. This seems to break the traces
            // in the Dalvik JIT and avoid the bug where the generated ARM code can clobber float
            // values in a single-precision registers with double values written to
            // double-precision registers. See b/77496850 for examples.
            if (!value.isPhi()
                && value.definition.isNumberConversion()
                && value.definition.asNumberConversion().to == NumericType.DOUBLE) {
              com.debughelper.tools.r8.ir.code.InvokeStatic invokeIsNaN =
                  new InvokeStatic(javaLangDoubleisNaN.get(), null, ImmutableList.of(value));
              invokeIsNaN.setPosition(instruction.getPosition());

              // Insert the invoke before the current instruction.
              it.previous();
              com.debughelper.tools.r8.ir.code.BasicBlock blockWithInvokeNaN =
                  block.hasCatchHandlers() ? it.split(code, blocks) : block;
              if (blockWithInvokeNaN != block) {
                // If we split, add the invoke at the end of the original block.
                it = block.listIterator(block.getInstructions().size());
                it.previous();
                it.add(invokeIsNaN);
                // Continue iteration in the split block.
                block = blockWithInvokeNaN;
                it = block.listIterator();
              } else {
                // Otherwise, add it to the current block.
                it.add(invokeIsNaN);
              }
              // Skip over the instruction causing the invoke to be inserted.
              com.debughelper.tools.r8.ir.code.Instruction temp = it.next();
              assert temp == instruction;
            }
          }
        }
      }
    }
  }

  // If an exceptional edge could target a conditional-loop header ensure that we have a
  // materializing instruction on that path to work around a bug in some L x86_64 non-emulator VMs.
  // See b/111337896.
  public void workaroundExceptionTargetingLoopHeaderBug(IRCode code) {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      if (block.hasCatchHandlers()) {
        for (com.debughelper.tools.r8.ir.code.BasicBlock handler : block.getCatchHandlers().getUniqueTargets()) {
          // We conservatively assume that a block with at least two normal predecessors is a loop
          // header. If we ever end up computing exact loop headers, use that here instead.
          // The loop is conditional if it has at least two normal successors.
          BasicBlock target = handler.endOfGotoChain();
          if (target.getPredecessors().size() > 2
              && target.getNormalPredecessors().size() > 1
              && target.getNormalSuccessors().size() > 1) {
            Instruction fixit = new AlwaysMaterializingNop();
            fixit.setBlock(handler);
            fixit.setPosition(handler.getPosition());
            handler.getInstructions().addFirst(fixit);
          }
        }
      }
    }
  }
}
