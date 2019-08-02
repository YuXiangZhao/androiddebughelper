// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.regalloc;

import static com.debughelper.tools.r8.ir.code.IRCode.INSTRUCTION_NUMBER_DELTA;
import static com.debughelper.tools.r8.ir.regalloc.LiveIntervals.NO_REGISTER;

import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.regalloc.LiveIntervals;
import com.debughelper.tools.r8.ir.regalloc.LiveIntervalsUse;
import com.debughelper.tools.r8.ir.regalloc.LiveRange;
import com.debughelper.tools.r8.ir.regalloc.RegisterPositions;
import com.debughelper.tools.r8.ir.regalloc.RegisterPositions.Type;
import com.debughelper.tools.r8.ir.regalloc.SpillMoveSet;
import com.debughelper.tools.r8.cf.FixedLocalValue;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.ir.code.Add;
import com.debughelper.tools.r8.ir.code.And;
import com.debughelper.tools.r8.ir.code.ArithmeticBinop;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.CheckCast;
import com.debughelper.tools.r8.ir.code.DebugLocalsChange;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.code.Move;
import com.debughelper.tools.r8.ir.code.NumericType;
import com.debughelper.tools.r8.ir.code.Or;
import com.debughelper.tools.r8.ir.code.Phi;
import com.debughelper.tools.r8.ir.code.Sub;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.code.Xor;
import com.debughelper.tools.r8.logging.Log;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.ListUtils;
import com.debughelper.tools.r8.utils.StringUtils;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap.Entry;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Reference2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Linear scan register allocator.
 *
 * <p>The implementation is inspired by:
 *
 * <ul>
 *   <li>"Linear Scan Register Allocation in the Context of SSA Form and Register Constraints"
 *   (ftp://ftp.ssw.uni-linz.ac.at/pub/Papers/Moe02.PDF)</li>
 *   <li>"Linear Scan Register Allocation on SSA Form"
 *   (http://www.christianwimmer.at/Publications/Wimmer10a/Wimmer10a.pdf)</li>
 *   <li>"Linear Scan Register Allocation for the Java HotSpot Client Compiler"
 *   (http://www.christianwimmer.at/Publications/Wimmer04a/Wimmer04a.pdf)</li>
 * </ul>
 */
public class LinearScanRegisterAllocator implements RegisterAllocator {

  public static final int REGISTER_CANDIDATE_NOT_FOUND = -1;
  public static final int MIN_CONSTANT_FREE_FOR_POSITIONS = 5;
  public static final int EXCEPTION_INTERVALS_OVERLAP_CUTOFF = 500;

  private enum ArgumentReuseMode {
    ALLOW_ARGUMENT_REUSE,
    DISALLOW_ARGUMENT_REUSE
  }

  private static class LocalRange implements Comparable<LocalRange> {
    final com.debughelper.tools.r8.ir.code.Value value;
    final com.debughelper.tools.r8.graph.DebugLocalInfo local;
    final int register;
    final int start;
    final int end;

    LocalRange(com.debughelper.tools.r8.ir.code.Value value, int register, int start, int end) {
      assert value.hasLocalInfo();
      this.value = value;
      this.local = value.getLocalInfo();
      this.register = register;
      this.start = start;
      this.end = end;
    }

    @Override
    public int compareTo(LocalRange o) {
      return (start != o.start)
          ? Integer.compare(start, o.start)
          : Integer.compare(end, o.end);
    }

    @Override
    public String toString() {
      return local + " @ r" + register + ": " + new com.debughelper.tools.r8.ir.regalloc.LiveRange(start, end);
    }
  }

  // The max register number that will fit in any dex instruction encoding.
  private static final int MAX_SMALL_REGISTER = com.debughelper.tools.r8.dex.Constants.U4BIT_MAX;

  // The code for which to allocate registers.
  private final com.debughelper.tools.r8.ir.code.IRCode code;
  // Number of registers used for arguments.
  protected final int numberOfArgumentRegisters;
  // Compiler options.
  private final com.debughelper.tools.r8.utils.InternalOptions options;

  // Mapping from basic blocks to the set of values live at entry to that basic block.
  private Map<com.debughelper.tools.r8.ir.code.BasicBlock, Set<com.debughelper.tools.r8.ir.code.Value>> liveAtEntrySets;
  // The value of the first argument, or null if the method has no arguments.
  protected com.debughelper.tools.r8.ir.code.Value firstArgumentValue;
  // The value of the last argument, or null if the method has no arguments.
  private com.debughelper.tools.r8.ir.code.Value lastArgumentValue;

  // The set of registers that are free for allocation.
  private TreeSet<Integer> freeRegisters = new TreeSet<>();
  // The max register number used.
  private int maxRegisterNumber = -1;

  // List of all top-level live intervals for all SSA values.
  private List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> liveIntervals = new ArrayList<>();
  // List of active intervals.
  private List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> active = new LinkedList<>();
  // List of intervals where the current instruction falls into one of their live range holes.
  protected List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> inactive = new LinkedList<>();
  // List of intervals that no register has been allocated to sorted by first live range.
  protected PriorityQueue<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> unhandled = new PriorityQueue<>();

  // List of intervals for the result of move-exception instructions.
  // Always empty in mode ALLOW_ARGUMENT_REUSE.
  private List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> moveExceptionIntervals = new ArrayList<>();

  // The first register used for parallel moves. After register allocation the parallel move
  // temporary registers are [firstParallelMoveTemporary, maxRegisterNumber].
  private int firstParallelMoveTemporary = NO_REGISTER;
  // Mapping from register number to the number of unused register numbers below that register
  // number. Used for compacting the register numbers if some spill registers are not used
  // because their values can be rematerialized.
  private int[] unusedRegisters = null;

  // Whether or not the code has a move exception instruction. Used to pin the move exception
  // register.
  private boolean hasDedicatedMoveExceptionRegister() {
    return !moveExceptionIntervals.isEmpty();
  }

  // We allocate a dedicated move exception register right after the arguments.
  // TODO(christofferqa): The move-exception instruction only requires its destination register to
  // fit in 8 bits. In some situations, it might be better to use a register which is >= 16 if we
  // end up using that many registers.
  private int getMoveExceptionRegister() {
    assert hasDedicatedMoveExceptionRegister();
    return numberOfArgumentRegisters;
  }

  public LinearScanRegisterAllocator(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.utils.InternalOptions options) {
    this.code = code;
    this.options = options;
    int argumentRegisters = 0;
    for (com.debughelper.tools.r8.ir.code.Instruction instruction : code.blocks.getFirst().getInstructions()) {
      if (instruction.isArgument()) {
        argumentRegisters += instruction.outValue().requiredRegisters();
      }
    }
    numberOfArgumentRegisters = argumentRegisters;
  }

  /**
   * Perform register allocation for the IRCode.
   */
  @Override
  public void allocateRegisters(boolean debug) {
    // There are no linked values prior to register allocation.
    assert noLinkedValues();
    assert code.isConsistentSSA();
    if (this.code.method.accessFlags.isBridge() && implementationIsBridge(this.code)) {
      transformBridgeMethod();
    }
    computeNeedsRegister();
    insertArgumentMoves();
    ImmutableList<com.debughelper.tools.r8.ir.code.BasicBlock> blocks = computeLivenessInformation();
    // First attempt to allocate register allowing argument reuse. This will fail if spilling
    // is required or if we end up using more than 16 registers.
    boolean noSpilling =
        performAllocationWithoutMoveInsertion(ArgumentReuseMode.ALLOW_ARGUMENT_REUSE);
    if (!noSpilling || (highestUsedRegister() > MAX_SMALL_REGISTER)) {
      // Redo allocation disallowing argument reuse. This always succeeds.
      clearRegisterAssignments();
      performAllocation(ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE);
    } else {
      // Insert spill and phi moves after allocating with argument reuse. If the moves causes
      // the method to use more than 16 registers we redo allocation disallowing argument
      // reuse. This very rarely happens in practice (12 methods on GMSCore v4 hits that case).
      insertMoves();
      if (highestUsedRegister() > MAX_SMALL_REGISTER) {
        // Redo allocation disallowing argument reuse. This always succeeds.
        clearRegisterAssignments();
        removeSpillAndPhiMoves();
        performAllocation(ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE);
      }
    }

    assert code.isConsistentGraph();
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      Log.debug(this.getClass(), toString());
    }
    computeUnusedRegisters();
    if (debug) {
      computeDebugInfo(blocks);
    }
    clearUserInfo();
    clearState();
  }

  private static Integer nextInRange(int start, int end, List<Integer> points) {
    while (!points.isEmpty() && points.get(0) < start) {
      points.remove(0);
    }
    if (points.isEmpty()) {
      return null;
    }
    Integer next = points.get(0);
    assert start <= next;
    if (next < end) {
      points.remove(0);
      return next;
    }
    return null;
  }

  private void computeDebugInfo(ImmutableList<com.debughelper.tools.r8.ir.code.BasicBlock> blocks) {
    computeDebugInfo(blocks, liveIntervals, this);
  }

  public static void computeDebugInfo(
          ImmutableList<com.debughelper.tools.r8.ir.code.BasicBlock> blocks, List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> liveIntervals,
          RegisterAllocator allocator) {
    // Collect live-ranges for all SSA values with local information.
    List<LocalRange> ranges = new ArrayList<>();
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals interval : liveIntervals) {
      com.debughelper.tools.r8.ir.code.Value value = interval.getValue();
      if (!value.hasLocalInfo()) {
        continue;
      }
      List<Integer> starts = com.debughelper.tools.r8.utils.ListUtils.map(value.getDebugLocalStarts(), com.debughelper.tools.r8.ir.code.Instruction::getNumber);
      List<Integer> ends = ListUtils.map(value.getDebugLocalEnds(), com.debughelper.tools.r8.ir.code.Instruction::getNumber);
      List<com.debughelper.tools.r8.ir.regalloc.LiveRange> liveRanges = new ArrayList<>();
      liveRanges.addAll(interval.getRanges());
      for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals child : interval.getSplitChildren()) {
        assert child.getValue() == value;
        assert child.getSplitChildren() == null || child.getSplitChildren().isEmpty();
        liveRanges.addAll(child.getRanges());
      }
      liveRanges.sort((r1, r2) -> Integer.compare(r1.start, r2.start));
      starts.sort(Integer::compare);
      ends.sort(Integer::compare);

      for (com.debughelper.tools.r8.ir.regalloc.LiveRange liveRange : liveRanges) {
        int start = liveRange.start;
        int end = liveRange.end;
        Integer nextEnd;
        while ((nextEnd = nextInRange(start, end, ends)) != null) {
          // If an argument value has been split, we have disallowed argument reuse and therefore,
          // the argument value is also in the argument register throughout the method. For debug
          // information, we always use the argument register whenever a local corresponds to an
          // argument value. That avoids ending and restarting locals whenever we move arguments
          // to lower register.
          int register = allocator.getArgumentOrAllocateRegisterForValue(value, start);
          ranges.add(new LocalRange(value, register, start, nextEnd));
          Integer nextStart = nextInRange(nextEnd, end, starts);
          if (nextStart == null) {
            start = -1;
            break;
          }
          start = nextStart;
        }
        if (start >= 0) {
          ranges.add(
              new LocalRange(
                  value,
                  allocator.getArgumentOrAllocateRegisterForValue(value, start),
                  start,
                  end));
        }
      }
    }
    if (ranges.isEmpty()) {
      return;
    }
    ranges.sort(LocalRange::compareTo);

    // At each instruction compute the changes to live locals.
    LinkedList<LocalRange> openRanges = new LinkedList<>();
    Iterator<LocalRange> rangeIterator = ranges.iterator();
    LocalRange nextStartingRange = rangeIterator.next();
    Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> ending = new Int2ReferenceOpenHashMap<>();
    Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> starting = new Int2ReferenceOpenHashMap<>();

    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
      // Skip past all spill moves to obtain the instruction number of the actual first instruction.
      com.debughelper.tools.r8.ir.code.InstructionListIterator instructionIterator = block.listIterator();
      instructionIterator.nextUntil(
          i -> !i.isArgument() && !i.isMoveException() && !isSpillInstruction(i));
      com.debughelper.tools.r8.ir.code.Instruction firstInstruction = instructionIterator.previous();
      int firstIndex = firstInstruction.getNumber();

      // Close ranges up-to but excluding the first instruction. Ends are exclusive but the values
      // might be live upon entering the first instruction (if they are used by it). Since we
      // skipped move-exception this closes locals at the move exception which should close as part
      // of the exceptional transfer.
      openRanges.removeIf(openRange -> !isLocalLiveAtInstruction(firstInstruction, openRange));

      // Open ranges up-to but excluding the first instruction. Starts are inclusive but entry is
      // prior to the first instruction.
      while (nextStartingRange != null && nextStartingRange.start < firstIndex) {
        // If the range is live at this index open it. Again the end is inclusive here because the
        // instruction is live at block entry if it is live at entry to the first instruction.
        if (isLocalLiveAtInstruction(firstInstruction, nextStartingRange)) {
          openRanges.add(nextStartingRange);
        }
        nextStartingRange = rangeIterator.hasNext() ? rangeIterator.next() : null;
      }

      // Initialize current locals (registers after any spill instructions).
      Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> currentLocals =
          new Int2ReferenceOpenHashMap<>(openRanges.size());
      for (LocalRange openRange : openRanges) {
        currentLocals.put(openRange.register, openRange.local);
      }

      // Set locals at entry. This will adjust initial local registers in case of spilling.
      setLocalsAtEntry(block, instructionIterator, openRanges, currentLocals, allocator);

      // Iterate the block instructions and emit locals changed events.
      while (instructionIterator.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = instructionIterator.next();
        if (instruction.isDebugLocalRead()) {
          // Remove debug local reads now that local liveness is computed.
          assert !instruction.getDebugValues().isEmpty();
          instruction.clearDebugValues();
          instructionIterator.remove();
        }
        if (!instructionIterator.hasNext()) {
          break;
        }
        com.debughelper.tools.r8.ir.code.Instruction nextInstruction = instructionIterator.peekNext();
        if (isSpillInstruction(nextInstruction)) {
          // No need to insert a DebugLocalsChange instruction before a spill instruction.
          continue;
        }
        int index = nextInstruction.getNumber();
        ListIterator<LocalRange> it = openRanges.listIterator(0);
        while (it.hasNext()) {
          LocalRange openRange = it.next();
          // Close ranges up-to but excluding the first instruction.
          if (!isLocalLiveAtInstruction(nextInstruction, openRange)) {
            it.remove();
            assert currentLocals.get(openRange.register) == openRange.local;
            currentLocals.remove(openRange.register);
            ending.put(openRange.register, openRange.local);
          }
        }
        while (nextStartingRange != null && nextStartingRange.start < index) {
          // If the range is live at this index open it.
          if (isLocalLiveAtInstruction(nextInstruction, nextStartingRange)) {
            openRanges.add(nextStartingRange);
            assert !currentLocals.containsKey(nextStartingRange.register);
            currentLocals.put(nextStartingRange.register, nextStartingRange.local);
            starting.put(nextStartingRange.register, nextStartingRange.local);
          }
          nextStartingRange = rangeIterator.hasNext() ? rangeIterator.next() : null;
        }
        // Compute the final change in locals and insert it before nextInstruction.
        boolean localsChanged = !ending.isEmpty() || !starting.isEmpty();
        if (localsChanged) {
          boolean skipChange =
              nextInstruction == nextInstruction.getBlock().exit() && nextInstruction.isGoto();
          if (!skipChange) {
            com.debughelper.tools.r8.ir.code.DebugLocalsChange change = createLocalsChange(ending, starting);
            if (change != null) {
              // Insert the DebugLocalsChange instruction before nextInstruction.
              instructionIterator.add(change);
            }
          }
          // Create new maps for the next DebugLocalsChange instruction.
          ending = new Int2ReferenceOpenHashMap<>();
          starting = new Int2ReferenceOpenHashMap<>();
        }
      }
    }
  }

  private static boolean isLocalLiveAtInstruction(com.debughelper.tools.r8.ir.code.Instruction instruction, LocalRange range) {
    return isLocalLiveAtInstruction(instruction, range.start, range.end, range.value);
  }

  private static boolean isLocalLiveAtInstruction(
          com.debughelper.tools.r8.ir.code.Instruction instruction, int start, int end, com.debughelper.tools.r8.ir.code.Value value) {
    int number = instruction.getNumber();
    assert start < number;
    return number < end || (number == end && usesValue(value, instruction));
  }

  private static boolean usesValue(com.debughelper.tools.r8.ir.code.Value usedValue, com.debughelper.tools.r8.ir.code.Instruction instruction) {
    return valuesContain(usedValue, instruction.inValues())
        || valuesContain(usedValue, instruction.getDebugValues());
  }

  private static boolean valuesContain(com.debughelper.tools.r8.ir.code.Value value, Collection<com.debughelper.tools.r8.ir.code.Value> values) {
    for (com.debughelper.tools.r8.ir.code.Value other : values) {
      if (value == other) {
        return true;
      }
      if (value.isPhi()
          && other instanceof com.debughelper.tools.r8.cf.FixedLocalValue
          && ((FixedLocalValue) other).getPhi() == value) {
        return true;
      }
    }
    return false;
  }

  private static void setLocalsAtEntry(
      com.debughelper.tools.r8.ir.code.BasicBlock block,
      com.debughelper.tools.r8.ir.code.InstructionListIterator instructionIterator,
      List<LocalRange> openRanges,
      Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> finalLocals,
      RegisterAllocator allocator) {
    // If this is the graph-entry or there are no moves entry locals are current locals.
    if (block.getPredecessors().isEmpty() || block.entry() == instructionIterator.peekNext()) {
      assert !block.entry().isMoveException();
      assert !isSpillInstruction(block.entry());
      block.setLocalsAtEntry(new Int2ReferenceOpenHashMap<>(finalLocals));
      return;
    }
    // Otherwise entry locals are the registers of the predecessor, ie, prior to spill instructions.
    Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> initialLocals =
        new Int2ReferenceOpenHashMap<>(openRanges.size());
    int predecessorExitIndex =
        block.entry().isMoveException()
            ? block.getPredecessors().get(0).exceptionalExit().getNumber()
            : block.getPredecessors().get(0).exit().getNumber();
    for (LocalRange open : openRanges) {
      int predecessorRegister =
          allocator.getArgumentOrAllocateRegisterForValue(open.value, predecessorExitIndex);
      initialLocals.put(predecessorRegister, open.local);
    }
    block.setLocalsAtEntry(initialLocals);

    // Compute the final change in locals and insert it after the last spill instruction.
    Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> ending = new Int2ReferenceOpenHashMap<>();
    Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> starting = new Int2ReferenceOpenHashMap<>();
    for (Entry<com.debughelper.tools.r8.graph.DebugLocalInfo> initialLocal : initialLocals.int2ReferenceEntrySet()) {
      if (finalLocals.get(initialLocal.getIntKey()) != initialLocal.getValue()) {
        ending.put(initialLocal.getIntKey(), initialLocal.getValue());
      }
    }
    for (Entry<com.debughelper.tools.r8.graph.DebugLocalInfo> finalLocal : finalLocals.int2ReferenceEntrySet()) {
      if (initialLocals.get(finalLocal.getIntKey()) != finalLocal.getValue()) {
        starting.put(finalLocal.getIntKey(), finalLocal.getValue());
      }
    }
    com.debughelper.tools.r8.ir.code.DebugLocalsChange change = createLocalsChange(ending, starting);
    if (change != null) {
      instructionIterator.add(change);
    }
  }

  private static com.debughelper.tools.r8.ir.code.DebugLocalsChange createLocalsChange(
          Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> ending, Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> starting) {
    if (ending.isEmpty() && starting.isEmpty()) {
      return null;
    }
    if (ending.isEmpty() || starting.isEmpty()) {
      return new com.debughelper.tools.r8.ir.code.DebugLocalsChange(ending, starting);
    }
    IntSet unneeded = new IntArraySet(Math.min(ending.size(), starting.size()));
    for (Entry<DebugLocalInfo> entry : ending.int2ReferenceEntrySet()) {
      if (starting.get(entry.getIntKey()) == entry.getValue()) {
        unneeded.add(entry.getIntKey());
      }
    }
    if (unneeded.size() == ending.size() && unneeded.size() == starting.size()) {
      return null;
    }
    IntIterator iterator = unneeded.iterator();
    while (iterator.hasNext()) {
      int key = iterator.nextInt();
      ending.remove(key);
      starting.remove(key);
    }
    return new DebugLocalsChange(ending, starting);
  }

  private void clearState() {
    liveAtEntrySets = null;
    liveIntervals = null;
    active = null;
    inactive = null;
    unhandled = null;
    freeRegisters = null;
  }

  // Compute a table that for each register numbers contains the number of previous register
  // numbers that were unused. This table is then used to slide down the actual registers
  // used to fill the gaps.
  private void computeUnusedRegisters() {
    if (registersUsed() == 0) {
      return;
    }
    // Compute the set of registers that is used based on all live intervals.
    Set<Integer> usedRegisters = new HashSet<>();
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals : liveIntervals) {
      addRegisterIfUsed(usedRegisters, intervals);
      for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals childIntervals : intervals.getSplitChildren()) {
        addRegisterIfUsed(usedRegisters, childIntervals);
      }
    }
    // Additionally, we have used temporary registers for parallel move scheduling, those
    // are used as well.
    for (int i = firstParallelMoveTemporary; i < maxRegisterNumber + 1; i++) {
      usedRegisters.add(realRegisterNumberFromAllocated(i));
    }
    // Compute the table based on the set of used registers.
    int unused = 0;
    int[] computed = new int[registersUsed()];
    for (int i = 0; i < registersUsed(); i++) {
      if (!usedRegisters.contains(i)) {
        unused++;
      }
      computed[i] = unused;
    }
    unusedRegisters = computed;
  }

  private void addRegisterIfUsed(Set<Integer> used, com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    boolean unused = intervals.isSpilledAndRematerializable();
    if (!unused) {
      used.add(realRegisterNumberFromAllocated(intervals.getRegister()));
      if (intervals.getType().isWide()) {
        used.add(realRegisterNumberFromAllocated(intervals.getRegister() + 1));
      }
    }
  }

  public int highestUsedRegister() {
    return registersUsed() - 1;
  }

  @Override
  public int registersUsed() {
    int numberOfRegister = maxRegisterNumber + 1;
    if (unusedRegisters != null) {
      return numberOfRegister - unusedRegisters[unusedRegisters.length - 1];
    }
    return numberOfRegister;
  }

  @Override
  public int getRegisterForValue(com.debughelper.tools.r8.ir.code.Value value, int instructionNumber) {
    if (value.isFixedRegisterValue()) {
      return realRegisterNumberFromAllocated(value.asFixedRegisterValue().getRegister());
    }
    com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals = value.getLiveIntervals();
    if (intervals.hasSplits()) {
      intervals = intervals.getSplitCovering(instructionNumber);
    }
    return getRegisterForIntervals(intervals);
  }

  @Override
  public int getArgumentOrAllocateRegisterForValue(com.debughelper.tools.r8.ir.code.Value value, int instructionNumber) {
    if (value.isArgument()) {
      return getRegisterForIntervals(value.getLiveIntervals());
    }
    return getRegisterForValue(value, instructionNumber);
  }

  @Override
  public com.debughelper.tools.r8.utils.InternalOptions getOptions() {
    return options;
  }

  private ImmutableList<com.debughelper.tools.r8.ir.code.BasicBlock> computeLivenessInformation() {
    ImmutableList<com.debughelper.tools.r8.ir.code.BasicBlock> blocks = code.numberInstructions();
    liveAtEntrySets = code.computeLiveAtEntrySets();
    computeLiveRanges();
    return blocks;
  }

  private boolean performAllocationWithoutMoveInsertion(ArgumentReuseMode mode) {
    pinArgumentRegisters();
    return performLinearScan(mode);
  }

  private boolean performAllocation(ArgumentReuseMode mode) {
    boolean result = performAllocationWithoutMoveInsertion(mode);
    insertMoves();
    if (mode == ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE) {
      // Now that we know the max register number we can compute whether it is safe to use
      // argument registers in place. If it is, we redo move insertion to get rid of the moves
      // caused by splitting of the argument registers.
      if (unsplitArguments()) {
        removeSpillAndPhiMoves();
        insertMoves();
      }
    }
    return result;
  }

  // When argument register reuse is disallowed, we split argument values to make sure that
  // we can get the argument into low enough registers at uses that require low numbers. After
  // register allocation we can check if it is safe to just use the argument register itself
  // for all uses and thereby avoid moving argument values around.
  private boolean unsplitArguments() {
    boolean argumentRegisterUnsplit = false;
    com.debughelper.tools.r8.ir.code.Value current = firstArgumentValue;
    while (current != null) {
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals = current.getLiveIntervals();
      assert intervals.getRegisterLimit() == com.debughelper.tools.r8.dex.Constants.U16BIT_MAX;
      boolean canUseArgumentRegister = true;
      boolean couldUseArgumentRegister = true;
      for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals child : intervals.getSplitChildren()) {
        int registerConstraint = child.getRegisterLimit();
        if (registerConstraint < com.debughelper.tools.r8.dex.Constants.U16BIT_MAX) {
          couldUseArgumentRegister = false;

          if (registerConstraint < highestUsedRegister()) {
            canUseArgumentRegister = false;
            break;
          }
        }
      }
      if (canUseArgumentRegister && !couldUseArgumentRegister) {
        // Only return true if there is a constrained use where it turns out that we can use the
        // original argument register. This way we will not unnecessarily redo move insertion.
        argumentRegisterUnsplit = true;
        for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals child : intervals.getSplitChildren()) {
          child.clearRegisterAssignment();
          child.setRegister(intervals.getRegister());
          child.setSpilled(false);
        }
      }
      current = current.getNextConsecutive();
    }
    return argumentRegisterUnsplit;
  }

  private void removeSpillAndPhiMoves() {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
        if (isSpillInstruction(instruction)) {
          it.remove();
        }
      }
    }
  }

  private static boolean isSpillInstruction(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    com.debughelper.tools.r8.ir.code.Value outValue = instruction.outValue();
    if (outValue != null && outValue.isFixedRegisterValue()) {
      // Only move and const number instructions are inserted for spill and phi moves. The
      // const number instructions are for values that can be rematerialized instead of
      // spilled.
      assert instruction.getNumber() == -1;
      assert instruction.isMove() || instruction.isConstNumber();
      assert !instruction.isDebugInstruction();
      return true;
    }
    return false;
  }

  private void clearRegisterAssignments() {
    freeRegisters.clear();
    maxRegisterNumber = -1;
    active.clear();
    inactive.clear();
    unhandled.clear();
    moveExceptionIntervals.clear();
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals : liveIntervals) {
      intervals.clearRegisterAssignment();
    }
  }

  /**
   * Get the register allocated to a given set of live intervals.
   */
  private int getRegisterForIntervals(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    int intervalsRegister = intervals.getRegister();
    return realRegisterNumberFromAllocated(intervalsRegister);
  }

  int unadjustedRealRegisterFromAllocated(int allocated) {
    assert allocated != NO_REGISTER;
    assert allocated >= 0;
    int register;
    if (allocated < numberOfArgumentRegisters) {
      // For the |numberOfArguments| first registers map to the correct argument register.
      register = maxRegisterNumber - (numberOfArgumentRegisters - allocated - 1);
    } else {
      // For everything else use the lower numbers.
      register = allocated - numberOfArgumentRegisters;
    }
    return register;
  }

  int realRegisterNumberFromAllocated(int allocated) {
    int register = unadjustedRealRegisterFromAllocated(allocated);
    // Adjust for spill registers that turn out to be unused because the value can be
    // rematerialized instead of spilled.
    if (unusedRegisters != null) {
      return register - unusedRegisters[register];
    }
    return register;
  }

  private boolean isHighRegister(int register) {
    return register > com.debughelper.tools.r8.dex.Constants.U4BIT_MAX;
  }

  private boolean performLinearScan(ArgumentReuseMode mode) {
    unhandled.addAll(liveIntervals);

    com.debughelper.tools.r8.ir.code.Value argumentValue = firstArgumentValue;
    while (argumentValue != null) {
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals argumentInterval = argumentValue.getLiveIntervals();
      assert argumentInterval.getRegister() != NO_REGISTER;
      unhandled.remove(argumentInterval);
      if (mode == ArgumentReuseMode.ALLOW_ARGUMENT_REUSE) {
        // All the argument intervals are active in the beginning and have preallocated registers.
        active.add(argumentInterval);
      } else {
        // Treat the argument interval as spilled which will require a load to a different
        // register for all register-constrained usages.
        inactive.add(argumentInterval);
        // Split argument live interval at its first constrained use.
        if (argumentInterval.getUses().size() > 1) {
          com.debughelper.tools.r8.ir.regalloc.LiveIntervalsUse use = argumentInterval.firstUseWithConstraint();
          if (use != null) {
            com.debughelper.tools.r8.ir.regalloc.LiveIntervals split;
            if (argumentInterval.numberOfUsesWithConstraint() == 1) {
              // If there is only one register-constrained use, split before
              // that one use.
              split = argumentInterval.splitBefore(use.getPosition());
            } else {
              // If there are multiple register-constrained users, split right after the definition
              // to make it more likely that arguments get in usable registers from the start.
              // TODO(christofferqa): This is not great if there are many arguments with multiple
              // constrained uses, since we fill up all the low registers immediately, making it
              // likely that we will have to kick them back out before they are actually used.
              split = argumentInterval
                  .splitBefore(argumentInterval.getValue().definition.getNumber() + 1);
            }
            unhandled.add(split);
          }
        }
        // Since we are not activating the argument live intervals, we need to free their registers.
        freeOccupiedRegistersForIntervals(argumentInterval);
      }
      argumentValue = argumentValue.getNextConsecutive();
    }

    // We have to be careful when it comes to the register allocated for a move exception
    // instruction. For move exception instructions there is no place to put spill or
    // restore moves. The move exception instruction has to be the first instruction in a
    // catch handler.
    //
    // When we allow argument reuse we do not allow any splitting, therefore we cannot get into
    // trouble with move exception registers. When argument reuse is disallowed we block a fixed
    // register to be used only by move exception instructions.
    if (mode == ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE) {
      // Force all move exception ranges to start out with the exception in a fixed register. Split
      // their live ranges which will force another register if used.
      boolean overlappingMoveExceptionIntervals = false;
      for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = block.entry();
        if (instruction.isMoveException()) {
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals = instruction.outValue().getLiveIntervals();
          unhandled.remove(intervals);
          moveExceptionIntervals.add(intervals);
          intervals.setRegister(getMoveExceptionRegister());
          if (!overlappingMoveExceptionIntervals) {
            for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals other : moveExceptionIntervals) {
              overlappingMoveExceptionIntervals |= other.overlaps(intervals);
            }
          }
        }
      }
      if (hasDedicatedMoveExceptionRegister()) {
        int moveExceptionRegister = getMoveExceptionRegister();
        assert moveExceptionRegister == maxRegisterNumber + 1;
        increaseCapacity(moveExceptionRegister, true);
      }
      if (overlappingMoveExceptionIntervals) {
        for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals : moveExceptionIntervals) {
          if (intervals.getUses().size() > 1) {
            com.debughelper.tools.r8.ir.regalloc.LiveIntervals split =
                intervals.splitBefore(intervals.getFirstUse() + com.debughelper.tools.r8.ir.code.IRCode.INSTRUCTION_NUMBER_DELTA);
            unhandled.add(split);
          }
        }
      }
    }

    // Go through each unhandled live interval and find a register for it.
    while (!unhandled.isEmpty()) {
      assert invariantsHold(mode);

      com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval = unhandled.poll();

      setHintForDestRegOfCheckCast(unhandledInterval);
      setHintToPromote2AddrInstruction(unhandledInterval);

      // If this interval value is the src of an argument move. Fix the registers for the
      // consecutive arguments now and add hints to the move sources. This looks forward
      // and propagate hints backwards to avoid many moves in connection with ranged invokes.
      allocateArgumentIntervalsWithSrc(unhandledInterval, mode);
      if (unhandledInterval.getRegister() != NO_REGISTER) {
        // The value itself is in the chain that has now gotten registers allocated.
        continue;
      }

      int start = unhandledInterval.getStart();
      // Check for active intervals that expired or became inactive.
      Iterator<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> activeIterator = active.iterator();
      while (activeIterator.hasNext()) {
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals activeIntervals = activeIterator.next();
        if (start >= activeIntervals.getEnd()) {
          activeIterator.remove();
          freeOccupiedRegistersForIntervals(activeIntervals);
        } else if (!activeIntervals.overlapsPosition(start)) {
          activeIterator.remove();
          assert activeIntervals.getRegister() != NO_REGISTER;
          inactive.add(activeIntervals);
          freeOccupiedRegistersForIntervals(activeIntervals);
        }
      }

      // Check for inactive intervals that expired or became reactivated.
      Iterator<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> inactiveIterator = inactive.iterator();
      while (inactiveIterator.hasNext()) {
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals inactiveIntervals = inactiveIterator.next();
        if (start >= inactiveIntervals.getEnd()) {
          inactiveIterator.remove();
        } else if (inactiveIntervals.overlapsPosition(start)) {
          inactiveIterator.remove();
          assert inactiveIntervals.getRegister() != NO_REGISTER;
          active.add(inactiveIntervals);
          takeFreeRegistersForIntervals(inactiveIntervals);
        }
      }

      // Perform the actual allocation.
      if (unhandledInterval.isLinked() && !unhandledInterval.isArgumentInterval()) {
        allocateLinkedIntervals(unhandledInterval, false);
      } else {
        if (!allocateSingleInterval(unhandledInterval, mode)) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean invariantsHold(ArgumentReuseMode mode) {
    TreeSet<Integer> computedFreeRegisters = new TreeSet<>();
    for (int register = 0; register <= maxRegisterNumber; ++register) {
      computedFreeRegisters.add(register);
    }
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals activeIntervals : active) {
      assert registersForIntervalsAreTaken(activeIntervals);
      activeIntervals.forEachRegister(
          register -> {
            assert computedFreeRegisters.contains(register);
            computedFreeRegisters.remove(register);
          });
    }
    if (mode == ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE) {
      // Each time an argument interval is active, we currently require that it is present in its
      // original, incoming argument register.
      for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals activeIntervals : active) {
        if (activeIntervals.isArgumentInterval()
            && activeIntervals != activeIntervals.getSplitParent()) {
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals parent = activeIntervals.getSplitParent();
          if (parent.getRegister() != activeIntervals.getRegister()) {
            activeIntervals
                .getSplitParent()
                .forEachRegister(
                    register -> {
                      assert computedFreeRegisters.contains(register);
                      computedFreeRegisters.remove(register);
                    });
          }
        }
      }
    }
    if (hasDedicatedMoveExceptionRegister()) {
      // Relax the check, since it is not currently guaranteed that the move exception register is
      // occupied if-and-only-if there is an active live interval with the register.
      freeRegisters.remove(getMoveExceptionRegister());
      computedFreeRegisters.remove(getMoveExceptionRegister());
    }
    assert freeRegisters.equals(computedFreeRegisters);
    return true;
  }

  private boolean freePositionsAreConsistentWithFreeRegisters(
          com.debughelper.tools.r8.ir.regalloc.RegisterPositions freePositions, int registerConstraint) {
    int n = Math.min(maxRegisterNumber, registerConstraint);
    for (int register = 0; register <= n; ++register) {
      if (freePositions.get(register) > 0) {
        // If this register is free according to freePositions, then it should also be free
        // according to freeRegisters.
        boolean isMoveExceptionRegister =
            hasDedicatedMoveExceptionRegister() && register == getMoveExceptionRegister();
        if (!isMoveExceptionRegister) {
          assert freeRegisters.contains(register);
        }
      }
    }
    return true;
  }

  private boolean registerAssignmentNotConflictingWithArgument(com.debughelper.tools.r8.ir.regalloc.LiveIntervals interval) {
    assert interval.getRegister() != NO_REGISTER;
    for (com.debughelper.tools.r8.ir.code.Value argumentValue = firstArgumentValue;
         argumentValue != null;
         argumentValue = argumentValue.getNextConsecutive()) {
      assert !interval.hasConflictingRegisters(argumentValue.getLiveIntervals())
          || !argumentValue.getLiveIntervals().anySplitOverlaps(interval);
    }
    return true;
  }

  private void setHintForDestRegOfCheckCast(com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval) {
    if (unhandledInterval.getHint() == null &&
        unhandledInterval.getValue().definition instanceof com.debughelper.tools.r8.ir.code.CheckCast) {
      com.debughelper.tools.r8.ir.code.CheckCast checkcast = unhandledInterval.getValue().definition.asCheckCast();
      com.debughelper.tools.r8.ir.code.Value checkcastInput = checkcast.inValues().get(0);
      assert checkcastInput != null;
      if (checkcastInput.getLiveIntervals() != null &&
          !checkcastInput.getLiveIntervals().overlaps(unhandledInterval) &&
          checkcastInput.getLocalInfo() == unhandledInterval.getValue().definition.getLocalInfo()) {
        unhandledInterval.setHint(checkcastInput.getLiveIntervals());
      }
    }
  }

  /*
   * This method tries to promote arithmetic binary instruction to use the 2Addr form.
   * To achieve this goal the output interval of the binary instruction is set with an hint
   * that is the left interval or the right interval if possible when intervals do not overlap.
   */
  private void setHintToPromote2AddrInstruction(com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval) {
    if (unhandledInterval.getHint() == null &&
        unhandledInterval.getValue().definition instanceof com.debughelper.tools.r8.ir.code.ArithmeticBinop) {
      ArithmeticBinop binOp = unhandledInterval.getValue().definition.asArithmeticBinop();
      com.debughelper.tools.r8.ir.code.Value left = binOp.leftValue();
      assert left != null;
      if (left.getLiveIntervals() != null &&
          !left.getLiveIntervals().overlaps(unhandledInterval)) {
        unhandledInterval.setHint(left.getLiveIntervals());
      } else {
        com.debughelper.tools.r8.ir.code.Value right = binOp.rightValue();
        assert right != null;
        if (binOp.isCommutative() && right.getLiveIntervals() != null &&
            !right.getLiveIntervals().overlaps(unhandledInterval)) {
          unhandledInterval.setHint(right.getLiveIntervals());
        }
      }
    }
  }

  /**
   * Perform look-ahead and allocate registers for linked argument chains that have the argument
   * interval as an argument move source.
   *
   * <p>The end result of calling this method is that the argument intervals have registers
   * allocated and have been moved from unhandled to inactive. The move sources have their hints
   * updated. The rest of the register allocation state is unchanged.
   */
  private void allocateArgumentIntervalsWithSrc(com.debughelper.tools.r8.ir.regalloc.LiveIntervals srcInterval, ArgumentReuseMode mode) {
    com.debughelper.tools.r8.ir.code.Value value = srcInterval.getValue();
    for (com.debughelper.tools.r8.ir.code.Instruction instruction : value.uniqueUsers()) {
      // If there is a move user that is an argument move, we allocate the consecutive
      // registers for the argument intervals and propagate the selected registers back as
      // hints to the sources.
      if (instruction.isMove() && instruction.asMove().dest().isLinked()) {
        com.debughelper.tools.r8.ir.code.Move move = instruction.asMove();
        com.debughelper.tools.r8.ir.code.Value dest = move.dest();
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals destIntervals = dest.getLiveIntervals();
        if (destIntervals.getRegister() == NO_REGISTER) {
          // Save the current register allocation state so we can restore it at the end.
          TreeSet<Integer> savedFreeRegisters = new TreeSet<>(freeRegisters);
          int savedMaxRegisterNumber = maxRegisterNumber;
          List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> savedInactive = new LinkedList<>(inactive);

          // Add all the active intervals to the inactive set. When allocating linked intervals we
          // check all inactive intervals and exclude the registers for overlapping inactive
          // intervals.
          for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals active : active) {
            // TODO(ager): We could allow the use of all the currently active registers for the
            // ranged invoke (by adding the registers for all the active intervals to freeRegisters
            // here). That could lead to lower register pressure. However, it would also often mean
            // that we cannot allocate the right argument register to the current unhandled
            // interval. Size measurements on GMSCore indicate that blocking the current active
            // registers works the best for code size.
            if (active.isArgumentInterval()) {
              // Allow the ranged invoke to use argument registers if free. This improves register
              // allocation for bridge methods that forwards all of their arguments after check-cast
              // checks on their types.
              freeOccupiedRegistersForIntervals(active);
            }
            inactive.add(active);
          }

          // Allocate the argument intervals.
          unhandled.remove(destIntervals);
          boolean excludeUnhandledOverlappingArgumentIntervals = false;
          if (mode == ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE) {
            // Since we are going to do a look-ahead, there may be argument live interval splits,
            // which are currently unhandled, but would be inactive at the invoke-range instruction.
            // Thus, the implementation of allocateLinkedIntervals needs to exclude the argument
            // registers for which there exists a split that overlaps with one of the inputs to the
            // invoke-range instruction. We handle this situation by setting the following flag.
            excludeUnhandledOverlappingArgumentIntervals = true;
          }
          unhandled.add(srcInterval);
          allocateLinkedIntervals(destIntervals, excludeUnhandledOverlappingArgumentIntervals);
          active.remove(destIntervals);
          unhandled.remove(srcInterval);
          // Restore the register allocation state.
          freeRegisters = savedFreeRegisters;
          // In case maxRegisterNumber has changed, update freeRegisters.
          for (int i = savedMaxRegisterNumber + 1; i <= maxRegisterNumber; i++) {
            freeRegisters.add(i);
          }

          inactive = savedInactive;
          // Move all the argument intervals to the inactive set.
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals current = destIntervals.getStartOfConsecutive();
          while (current != null) {
            assert !inactive.contains(current);
            assert !active.contains(current);
            assert !unhandled.contains(current);
            inactive.add(current);
            current = current.getNextConsecutive();
          }
        }
      }
    }
  }

  private void allocateLinkedIntervals(
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, boolean excludeUnhandledOverlappingArgumentIntervals) {
    com.debughelper.tools.r8.ir.regalloc.LiveIntervals start = unhandledInterval.getStartOfConsecutive();

    // Exclude the registers that overlap the start of one of the live ranges we are
    // going to assign registers to now.
    IntSet excludedRegisters = new IntArraySet();
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals current = start; current != null; current = current.getNextConsecutive()) {
      for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals inactiveIntervals : inactive) {
        if (inactiveIntervals.overlaps(current)) {
          excludeRegistersForInterval(inactiveIntervals, excludedRegisters);
        }
      }
    }
    if (excludeUnhandledOverlappingArgumentIntervals && firstArgumentValue != null) {
      // Exclude the argument registers for which there exists a split that overlaps with one of
      // the inputs to the invoke-range instruction.
      for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals = firstArgumentValue.getLiveIntervals();
           intervals != null;
           intervals = intervals.getNextConsecutive()) {
        if (liveIntervalsHasUnhandledSplitOverlappingAnyOf(intervals, start)) {
          excludeRegistersForInterval(intervals, excludedRegisters);
        }
      }
    }
    // Exclude move exception register if the first interval overlaps a move exception interval.
    // It is not necessary to check the remaining consecutive intervals, since we always use
    // register 0 (after remapping) for the argument register.
    if (overlapsMoveExceptionInterval(start) && freeRegisters.remove(getMoveExceptionRegister())) {
      excludedRegisters.add(getMoveExceptionRegister());
    }
    // Select registers.
    int numberOfRegisters = start.numberOfConsecutiveRegisters();
    int nextRegister = getFreeConsecutiveRegisters(numberOfRegisters);
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals current = start; current != null; current = current.getNextConsecutive()) {
      current.setRegister(nextRegister);
      assert registerAssignmentNotConflictingWithArgument(current);
      // Propagate hints to the move sources.
      com.debughelper.tools.r8.ir.code.Value value = current.getValue();
      if (!value.isPhi() && value.definition.isMove()) {
        com.debughelper.tools.r8.ir.code.Move move = value.definition.asMove();
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals = move.src().getLiveIntervals();
        intervals.setHint(current);
      }
      if (current != unhandledInterval) {
        // Only the start of unhandledInterval has been reached at this point. All other live
        // intervals in the chain have been assigned registers but their start has not yet been
        // reached. Therefore, they belong in the inactive set.
        unhandled.remove(current);
        inactive.add(current);
      }
      nextRegister += current.requiredRegisters();
    }

    assert unhandledInterval.getRegister() != NO_REGISTER;
    takeFreeRegistersForIntervals(unhandledInterval);
    active.add(unhandledInterval);
    // Include the registers for inactive ranges that we had to exclude for this allocation.
    freeRegisters.addAll(excludedRegisters);
  }

  // Returns true if intervals has an unhandled split, which overlaps with chain or any of its
  // consecutives.
  private boolean liveIntervalsHasUnhandledSplitOverlappingAnyOf(
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals, com.debughelper.tools.r8.ir.regalloc.LiveIntervals chain) {
    assert intervals == intervals.getSplitParent();
    assert chain == chain.getStartOfConsecutive();
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals split : intervals.getSplitChildren()) {
      if (unhandled.contains(split)) {
        for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals current = chain;
             current != null;
             current = current.getNextConsecutive()) {
          if (split.overlaps(current)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private int getSpillRegister(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    if (intervals.isArgumentInterval()) {
      return intervals.getSplitParent().getRegister();
    }

    int register = maxRegisterNumber + 1;
    increaseCapacity(maxRegisterNumber + intervals.requiredRegisters());
    assert registersAreFree(register, intervals.getType().isWide());
    return register;
  }

  private int toInstructionPosition(int position) {
    return position % 2 == 0 ? position : position + 1;
  }

  private int toGapPosition(int position) {
    return position % 2 == 1 ? position : position - 1;
  }

  // Art had a bug (b/68761724) for debughelper N and O in the arm32 interpreter
  // where an aget-wide instruction using the same register for the array
  // and the first register of the result could lead to the wrong exception
  // being thrown on out of bounds.
  //
  // For instructions of the form 'aget-wide regA, regA, regB' where
  // regB is out of bounds of non-null array in regA, Art would throw a null
  // pointer exception instead of an ArrayIndexOutOfBounds exception.
  //
  // We work around that bug by disallowing aget-wide with the same array
  // and result register.
  private boolean needsArrayGetWideWorkaround(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    if (options.canUseSameArrayAndResultRegisterInArrayGetWide()) {
      return false;
    }
    if (intervals.requiredRegisters() == 1) {
      // Not the live range for a wide value and therefore not the output of aget-wide.
      return false;
    }
    if (intervals.getValue().isPhi()) {
      // If this writes a new register pair it will be via a move and not an aget-wide operation.
      return false;
    }
    if (intervals.getSplitParent() != intervals) {
      // This is a split of a parent interval and therefore if this leads to a write of a
      // register pair it will be via a move and not an aget-wide operation.
      return false;
    }
    com.debughelper.tools.r8.ir.code.Instruction definition = intervals.getValue().definition;
    return definition.isArrayGet() && definition.asArrayGet().outType().isWide();
  }

  // Is the array-get array register the same as the first register we are
  // allocating for the result?
  private boolean isArrayGetArrayRegister(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals, int register) {
    assert needsArrayGetWideWorkaround(intervals);
    com.debughelper.tools.r8.ir.code.Value array = intervals.getValue().definition.asArrayGet().array();
    int arrayReg =
        array.getLiveIntervals().getSplitCovering(intervals.getStart()).getRegister();
    assert arrayReg != NO_REGISTER;
    return arrayReg == register;
  }

  private boolean needsSingleResultOverlappingLongOperandsWorkaround(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    if (!options.canHaveCmpLongBug()) {
      return false;
    }
    if (intervals.requiredRegisters() == 2) {
      // Not the live range for a single value and therefore not the output of cmp-long.
      return false;
    }
    if (intervals.getValue().isPhi()) {
      // If this writes a new register pair it will be via a move and not an cmp-long operation.
      return false;
    }
    if (intervals.getSplitParent() != intervals) {
      // This is a split of a parent interval and therefore if this leads to a write of a
      // register it will be via a move and not an cmp-long operation.
      return false;
    }
    com.debughelper.tools.r8.ir.code.Instruction definition = intervals.getValue().definition;
    return definition.isCmp() && definition.asCmp().inValues().get(0).outType().isWide();
  }

  private boolean singleOverlappingLong(int register1, int register2) {
    return register1 == register2 || register1 == (register2 + 1);
  }

  // Is one of the cmp-long argument registers the same as the register we are
  // allocating for the result?
  private boolean isSingleResultOverlappingLongOperands(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals, int register) {
    assert needsSingleResultOverlappingLongOperandsWorkaround(intervals);
    com.debughelper.tools.r8.ir.code.Value left = intervals.getValue().definition.asCmp().leftValue();
    com.debughelper.tools.r8.ir.code.Value right = intervals.getValue().definition.asCmp().rightValue();
    int leftReg =
        left.getLiveIntervals().getSplitCovering(intervals.getStart()).getRegister();
    int rightReg =
        right.getLiveIntervals().getSplitCovering(intervals.getStart()).getRegister();
    assert leftReg != NO_REGISTER;
    assert rightReg != NO_REGISTER;
    return singleOverlappingLong(register, leftReg) || singleOverlappingLong(register, rightReg);
  }

  // The dalvik jit had a bug where the long operations add, sub, or, xor and and would write
  // the first part of the result long before reading the second part of the input longs.
  //
  // Therefore, on dalvik, we cannot generate code with overlapping long registers such as:
  //
  // add-long v3, v0, v2
  //
  // Dalvik would add v0 and v2 and write that to v3. It would then read v1 and v3 and produce
  // the wrong result.
  private boolean needsLongResultOverlappingLongOperandsWorkaround(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    if (!options.canHaveOverlappingLongRegisterBug()) {
      return false;
    }
    if (intervals.requiredRegisters() == 1) {
      // Not the live range for a wide value.
      return false;
    }
    if (intervals.getValue().isPhi()) {
      // If this writes a new register pair it will be via a move and not a long operation.
      return false;
    }
    if (intervals.getSplitParent() != intervals) {
      // This is a split of the parent interval and therefore if this leads to a write of a
      // register pair it will be via a move and not a long operation.
      return false;
    }
    com.debughelper.tools.r8.ir.code.Instruction definition = intervals.getValue().definition;
    if (definition.isArithmeticBinop() &&
        definition.asArithmeticBinop().getNumericType() == com.debughelper.tools.r8.ir.code.NumericType.LONG) {
      return definition instanceof Add || definition instanceof Sub;
    }
    if (definition.isLogicalBinop() &&
        definition.asLogicalBinop().getNumericType() == NumericType.LONG) {
      return definition instanceof Or || definition instanceof Xor || definition instanceof And;
    }
    return false;
  }

  // Check if the two longs are half-overlapping, that is first register of one is the second
  // register of the other.
  private boolean longHalfOverlappingLong(int register1, int register2) {
    return register1 == (register2 + 1) || (register1 + 1) == register2;
  }

  private boolean isLongResultOverlappingLongOperands(
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, int register) {
    assert needsLongResultOverlappingLongOperandsWorkaround(unhandledInterval);
    com.debughelper.tools.r8.ir.code.Value left = unhandledInterval.getValue().definition.asBinop().leftValue();
    com.debughelper.tools.r8.ir.code.Value right = unhandledInterval.getValue().definition.asBinop().rightValue();
    int leftReg =
        left.getLiveIntervals().getSplitCovering(unhandledInterval.getStart()).getRegister();
    int rightReg =
        right.getLiveIntervals().getSplitCovering(unhandledInterval.getStart()).getRegister();
    assert leftReg != NO_REGISTER && rightReg != NO_REGISTER;
    // The dalvik bug is actually only for overlap with the second operand, For now we
    // make sure that there is no overlap with either register of either operand. Some vendor
    // optimization have bees seen to need this more conservative check.
    return longHalfOverlappingLong(register, leftReg)
        || longHalfOverlappingLong(register, rightReg);
  }

  // Intervals overlap a move exception interval if one of the splits of the intervals does.
  // Since spill and restore moves are always put after the move exception we cannot give
  // a non-move exception interval the same register as a move exception instruction.
  //
  // For example:
  //
  // B0:
  //   const v0, 0
  //   invoke throwing_method v0 (catch handler B2)
  //   goto B1
  // B1:
  //   ...
  // B2:
  //   move-exception v1
  //   invoke method v0
  //   return
  //
  // During register allocation we could split the const number intervals into multiple
  // parts. We have to avoid assigning the same register to v1 and and v0 in B0 even
  // if v0 has a different register in B2. That is because the spill/restore move when
  // transitioning from B0 to B2 has to be after the move-exception instruction.
  //
  // Assuming that v0 has register 0 in B0 and register 4 in B2 and v1 has register 0 in B2
  // we would generate the following incorrect code:
  //
  // B0:
  //   const r0, 0
  //   invoke throwing_method r0 (catch handler B2)
  //   goto B1
  // B1:
  //   ...
  // B2:
  //   move-exception r0
  //   move r4, r0  // Whoops.
  //   invoke method r4
  //   return
  private boolean overlapsMoveExceptionInterval(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    if (!hasDedicatedMoveExceptionRegister()) {
      return false;
    }
    // If there are that many move exception intervals we don't spent the time
    // going through them all. In that case it is unlikely that we can reuse the move exception
    // register in any case.
    if (moveExceptionIntervals.size() > EXCEPTION_INTERVALS_OVERLAP_CUTOFF) {
      return true;
    }
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals moveExceptionInterval : moveExceptionIntervals) {
      if (intervals.anySplitOverlaps(moveExceptionInterval)) {
        return true;
      }
    }
    return false;
  }

  private boolean allocateSingleInterval(com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, ArgumentReuseMode mode) {
    int registerConstraint = unhandledInterval.getRegisterLimit();
    assert registerConstraint <= com.debughelper.tools.r8.dex.Constants.U16BIT_MAX;

    assert unhandledInterval.requiredRegisters() <= 2;
    boolean needsRegisterPair = unhandledInterval.requiredRegisters() == 2;

    // Just use the argument register if an argument split has no register constraint. That will
    // avoid move generation for the argument.
    if (registerConstraint == com.debughelper.tools.r8.dex.Constants.U16BIT_MAX && unhandledInterval.isArgumentInterval()) {
      int argumentRegister = unhandledInterval.getSplitParent().getRegister();
      assignFreeRegisterToUnhandledInterval(unhandledInterval, argumentRegister);
      return true;
    }

    if (registerConstraint < com.debughelper.tools.r8.dex.Constants.U16BIT_MAX) {
      if (mode == ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE) {
        // We know that none of the argument registers will be reused. Therefore, we allow the
        // use of number of arguments more registers.
        registerConstraint += numberOfArgumentRegisters;
      }
    }

    // Set all free positions for possible registers to max integer.
    com.debughelper.tools.r8.ir.regalloc.RegisterPositions freePositions = new com.debughelper.tools.r8.ir.regalloc.RegisterPositions(registerConstraint + 1);

    if (mode == ArgumentReuseMode.ALLOW_ARGUMENT_REUSE) {
      if (options.debug && !code.method.accessFlags.isStatic()) {
        // If we are generating debug information, we pin the this value register since the
        // debugger expects to always be able to find it in the input register.
        assert numberOfArgumentRegisters > 0;
        assert firstArgumentValue != null && firstArgumentValue.requiredRegisters() == 1;
        freePositions.set(0, 0);
      }
    } else {
      // Argument reuse is not allowed and we block all the argument registers so that
      // arguments are never free.
      for (int i = 0; i < numberOfArgumentRegisters && i <= registerConstraint; i++) {
        freePositions.set(i, 0);
      }
    }

    // If there is a move exception instruction we block register 0 as the move exception
    // register. If we cannot find a free valid register for the move exception value we have no
    // place to put a spill move (because the move exception instruction has to be the
    // first instruction in the handler block).
    if (overlapsMoveExceptionInterval(unhandledInterval)) {
      int moveExceptionRegister = getMoveExceptionRegister();
      if (moveExceptionRegister <= registerConstraint) {
        freePositions.set(moveExceptionRegister, 0);
      }
    }

    // All the active intervals are not free at this point.
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals : active) {
      int activeRegister = intervals.getRegister();
      if (activeRegister <= registerConstraint) {
        for (int i = 0; i < intervals.requiredRegisters(); i++) {
          if (activeRegister + i <= registerConstraint) {
            freePositions.set(activeRegister + i, 0);
          }
        }
      }
    }

    // The register for inactive intervals that overlap with this interval are free until
    // the next overlap.
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals : inactive) {
      int inactiveRegister = intervals.getRegister();
      if (inactiveRegister <= registerConstraint && unhandledInterval.overlaps(intervals)) {
        int nextOverlap = unhandledInterval.nextOverlap(intervals);
        for (int i = 0; i < intervals.requiredRegisters(); i++) {
          int register = inactiveRegister + i;
          if (register <= registerConstraint) {
            int unhandledStart = toInstructionPosition(unhandledInterval.getStart());
            if (nextOverlap == unhandledStart) {
              // Don't use the register for an inactive interval that is only free until the next
              // instruction. We can get into this situation when unhandledInterval starts at a
              // gap position.
              freePositions.set(register, 0);
            } else {
              if (nextOverlap < freePositions.get(register)) {
                freePositions.set(register, nextOverlap, intervals);
              }
            }
          }
        }
      }
    }

    assert freePositionsAreConsistentWithFreeRegisters(freePositions, registerConstraint);

    // Attempt to use register hints.
    if (useRegisterHint(unhandledInterval, registerConstraint, freePositions, needsRegisterPair)) {
      return true;
    }

    // Get the register (pair) that is free the longest. That is the register with the largest
    // free position.
    int candidate = getLargestValidCandidate(
        unhandledInterval, registerConstraint, needsRegisterPair, freePositions, Type.ANY);

    // It is not always possible to find a largest valid candidate. If none of the usable register
    // are free we typically get the last candidate. However, if that candidate has to be
    // discarded in order to workaround bugs we get REGISTER_CANDIDATE_NOT_FOUND. In both cases
    // we need to spill a valid candidate. That path is triggered when largestFreePosition is 0.
    int largestFreePosition = 0;
    if (candidate != REGISTER_CANDIDATE_NOT_FOUND) {
      largestFreePosition = freePositions.get(candidate);
      if (needsRegisterPair) {
        largestFreePosition = Math.min(largestFreePosition, freePositions.get(candidate + 1));
      }
    }

    // Determine what to do based on how long the selected candidate is free.
    if (largestFreePosition == 0) {
      // Not free. We need to spill.
      if (mode == ArgumentReuseMode.ALLOW_ARGUMENT_REUSE) {
        // No spilling is allowed when we allow argument reuse. Bailout and start over with
        // argument reuse disallowed.
        return false;
      }
      // If the first use for these intervals is unconstrained, just spill this interval instead
      // of finding another candidate to spill via allocateBlockedRegister.
      if (!unhandledInterval.getUses().first().hasConstraint()) {
        int nextConstrainedPosition = unhandledInterval.firstUseWithConstraint().getPosition();
        int register = getSpillRegister(unhandledInterval);
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals split = unhandledInterval.splitBefore(nextConstrainedPosition);
        assignFreeRegisterToUnhandledInterval(unhandledInterval, register);
        unhandled.add(split);
      } else {
        allocateBlockedRegister(unhandledInterval);
      }
    } else {
      // We will use the candidate register(s) for unhandledInterval, and therefore potentially
      // need to adjust maxRegisterNumber.
      int candidateEnd = candidate + unhandledInterval.requiredRegisters() - 1;
      if (candidateEnd > maxRegisterNumber) {
        increaseCapacity(candidateEnd);
      }

      if (largestFreePosition >= unhandledInterval.getEnd()) {
        // Free for the entire interval. Allocate the register.
        assignFreeRegisterToUnhandledInterval(unhandledInterval, candidate);
      } else {
        if (mode == ArgumentReuseMode.ALLOW_ARGUMENT_REUSE) {
          // No splitting is allowed when we allow argument reuse. Bailout and start over with
          // argument reuse disallowed.
          return false;
        }
        // The candidate is free for the beginning of an interval. We split the interval
        // and use the register for as long as we can.
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals split = unhandledInterval.splitBefore(largestFreePosition);
        assert split != unhandledInterval;
        assignFreeRegisterToUnhandledInterval(unhandledInterval, candidate);
        unhandled.add(split);
      }
    }
    return true;
  }

  // Attempt to use the register hint for the unhandled interval in order to avoid generating
  // moves.
  private boolean useRegisterHint(com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, int registerConstraint,
                                  com.debughelper.tools.r8.ir.regalloc.RegisterPositions freePositions, boolean needsRegisterPair) {
    // If the unhandled interval has a hint we give it that register if it is available without
    // spilling. For phis we also use the hint before looking at the operand registers. The
    // phi could have a hint from an argument moves which it seems more important to honor in
    // practice.
    com.debughelper.tools.r8.ir.regalloc.LiveIntervals hint = unhandledInterval.getHint();
    if (hint != null) {
      int register = hint.getRegister();
      if (tryHint(unhandledInterval, registerConstraint, freePositions, needsRegisterPair,
          register)) {
        return true;
      }
    }

    // If there is no hint or it cannot be applied we search for a good register for phis using
    // the registers assigned to the operand intervals. We determine all the registers used
    // for operands and try them one by one based on frequency.
    com.debughelper.tools.r8.ir.code.Value value = unhandledInterval.getValue();
    if (value.isPhi()) {
      com.debughelper.tools.r8.ir.code.Phi phi = value.asPhi();
      Multiset<Integer> map = HashMultiset.create();
      List<com.debughelper.tools.r8.ir.code.Value> operands = phi.getOperands();
      for (int i = 0; i < operands.size(); i++) {
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals = operands.get(i).getLiveIntervals();
        if (intervals.hasSplits()) {
          com.debughelper.tools.r8.ir.code.BasicBlock pred = phi.getBlock().getPredecessors().get(i);
          intervals = intervals.getSplitCovering(pred.exit().getNumber());
        }
        int operandRegister = intervals.getRegister();
        if (operandRegister != NO_REGISTER) {
          map.add(operandRegister);
        }
      }
      for (Multiset.Entry<Integer> entry : Multisets.copyHighestCountFirst(map).entrySet()) {
        int register = entry.getElement();
        if (tryHint(unhandledInterval, registerConstraint, freePositions, needsRegisterPair,
            register)) {
          return true;
        }
      }
    }

    return false;
  }

  // Attempt to allocate the hint register to the unhandled intervals.
  private boolean tryHint(com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, int registerConstraint,
                          com.debughelper.tools.r8.ir.regalloc.RegisterPositions freePositions, boolean needsRegisterPair, int register) {
    // At some point after the hint has been added, the register allocator can
    // decide to redo allocation for the hint interval. In that case, the hint will be
    // reset to NO_REGISTER and provides no hinting info.
    if (register == NO_REGISTER) {
      return false;
    }
    if (register + (needsRegisterPair ? 1 : 0) <= registerConstraint) {
      int freePosition = freePositions.get(register);
      if (needsRegisterPair) {
        freePosition = Math.min(freePosition, freePositions.get(register + 1));
      }
      if (freePosition >= unhandledInterval.getEnd()) {
        // Check for overlapping long registers issue.
        if (needsLongResultOverlappingLongOperandsWorkaround(unhandledInterval) &&
            isLongResultOverlappingLongOperands(unhandledInterval, register)) {
          return false;
        }
        // Check for aget-wide bug in recent Art VMs.
        if (needsArrayGetWideWorkaround(unhandledInterval) &&
            isArrayGetArrayRegister(unhandledInterval, register)) {
          return false;
        }
        assignFreeRegisterToUnhandledInterval(unhandledInterval, register);
        return true;
      }
    }
    return false;
  }

  private void assignRegister(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals, int register) {
    assert register + intervals.requiredRegisters() - 1 <= maxRegisterNumber;
    intervals.setRegister(register);
    updateRegisterHints(intervals);
  }

  private void updateRegisterHints(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    com.debughelper.tools.r8.ir.code.Value value = intervals.getValue();
    // If the value flows into a phi, set the hint for all the operand splits that flow into the
    // phi and do not have hints yet.
    for (com.debughelper.tools.r8.ir.code.Phi phi : value.uniquePhiUsers()) {
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals phiIntervals = phi.getLiveIntervals();
      if (phiIntervals.getHint() == null) {
        for (int i = 0; i < phi.getOperands().size(); i++) {
          com.debughelper.tools.r8.ir.code.Value operand = phi.getOperand(i);
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals operandIntervals = operand.getLiveIntervals();
          com.debughelper.tools.r8.ir.code.BasicBlock pred = phi.getBlock().getPredecessors().get(i);
          operandIntervals = operandIntervals.getSplitCovering(pred.exit().getNumber());
          if (operandIntervals.getHint() == null) {
            operandIntervals.setHint(intervals);
          }
        }
      }
    }
    // If the value is a phi and we are at the start of the interval, we set the register as
    // the hint for all of the operand splits flowing into the phi. We set the hint no matter
    // if there is already a hint. We know the register for the phi and want as many operands
    // as possible to be allocated the same register to avoid phi moves.
    if (value.isPhi() && intervals.getSplitParent() == intervals) {
      com.debughelper.tools.r8.ir.code.Phi phi = value.asPhi();
      com.debughelper.tools.r8.ir.code.BasicBlock block = phi.getBlock();
      for (int i = 0; i < phi.getOperands().size(); i++) {
        com.debughelper.tools.r8.ir.code.Value operand = phi.getOperand(i);
        com.debughelper.tools.r8.ir.code.BasicBlock pred = block.getPredecessors().get(i);
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals operandIntervals =
            operand.getLiveIntervals().getSplitCovering(pred.exit().getNumber());
        operandIntervals.setHint(intervals);
      }
    }
  }

  private void assignFreeRegisterToUnhandledInterval(
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, int register) {
    assignRegister(unhandledInterval, register);
    takeFreeRegistersForIntervals(unhandledInterval);
    active.add(unhandledInterval);
  }

  private int getLargestCandidate(
      int registerConstraint,
      com.debughelper.tools.r8.ir.regalloc.RegisterPositions freePositions,
      boolean needsRegisterPair,
      com.debughelper.tools.r8.ir.regalloc.RegisterPositions.Type type) {
    int candidate = REGISTER_CANDIDATE_NOT_FOUND;
    int largest = -1;

    for (int i = 0; i <= registerConstraint; i++) {
      if (!freePositions.hasType(i, type)) {
        continue;
      }
      int freePosition = freePositions.get(i);
      if (needsRegisterPair) {
        if (i == numberOfArgumentRegisters - 1) {
          // The last register of the method is |i|, so we cannot use the pair (|i|, |i+1|).
          continue;
        }
        if (i >= registerConstraint) {
          break;
        }
        freePosition = Math.min(freePosition, freePositions.get(i + 1));
      }
      if (freePosition > largest) {
        candidate = i;
        largest = freePosition;
        if (largest == Integer.MAX_VALUE) {
          break;
        }
      }
    }
    return candidate;
  }

  private int handleWorkaround(
          Predicate<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> workaroundNeeded,
          BiPredicate<com.debughelper.tools.r8.ir.regalloc.LiveIntervals, Integer> workaroundNeededForCandidate,
          int candidate, com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, int registerConstraint,
          boolean needsRegisterPair, com.debughelper.tools.r8.ir.regalloc.RegisterPositions freePositions, com.debughelper.tools.r8.ir.regalloc.RegisterPositions.Type type) {
    if (workaroundNeeded.test(unhandledInterval)) {
      int lastCandidate = candidate;
      while (workaroundNeededForCandidate.test(unhandledInterval, candidate)) {
        // Make the unusable register unavailable for allocation and try again.
        freePositions.set(candidate, 0);
        candidate = getLargestCandidate(registerConstraint, freePositions, needsRegisterPair, type);
        // If there are only invalid candidates of the give type we will end up with the same
        // candidate returned again once we have tried them all. In that case we didn't find a
        // valid register candidate and we need to broaden the search to other types.
        if (lastCandidate == candidate) {
          return REGISTER_CANDIDATE_NOT_FOUND;
        }
        lastCandidate = candidate;
      }
    }
    return candidate;
  }

  private int getLargestValidCandidate(com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, int registerConstraint,
                                       boolean needsRegisterPair, com.debughelper.tools.r8.ir.regalloc.RegisterPositions freePositions, com.debughelper.tools.r8.ir.regalloc.RegisterPositions.Type type) {
    int candidate = getLargestCandidate(registerConstraint, freePositions, needsRegisterPair, type);
    if (candidate == REGISTER_CANDIDATE_NOT_FOUND) {
      return candidate;
    }
    candidate = handleWorkaround(
        this::needsLongResultOverlappingLongOperandsWorkaround,
        this::isLongResultOverlappingLongOperands,
        candidate, unhandledInterval, registerConstraint, needsRegisterPair, freePositions, type);
    candidate = handleWorkaround(
        this::needsSingleResultOverlappingLongOperandsWorkaround,
        this::isSingleResultOverlappingLongOperands,
        candidate, unhandledInterval, registerConstraint, needsRegisterPair, freePositions, type);
    candidate = handleWorkaround(
        this::needsArrayGetWideWorkaround,
        this::isArrayGetArrayRegister,
        candidate, unhandledInterval, registerConstraint, needsRegisterPair, freePositions, type);
    return candidate;
  }

  private void allocateBlockedRegister(com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval) {
    int registerConstraint = unhandledInterval.getRegisterLimit();
    if (registerConstraint < com.debughelper.tools.r8.dex.Constants.U16BIT_MAX) {
      // We never reuse argument registers and therefore allow the use of numberOfArgumentRegisters.
      registerConstraint += numberOfArgumentRegisters;
    }

    // Initialize all candidate registers to Integer.MAX_VALUE.
    com.debughelper.tools.r8.ir.regalloc.RegisterPositions usePositions = new com.debughelper.tools.r8.ir.regalloc.RegisterPositions(registerConstraint + 1);
    com.debughelper.tools.r8.ir.regalloc.RegisterPositions blockedPositions = new com.debughelper.tools.r8.ir.regalloc.RegisterPositions(registerConstraint + 1);

    // Compute next use location for all currently active registers.
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals : active) {
      int activeRegister = intervals.getRegister();
      if (activeRegister <= registerConstraint) {
        for (int i = 0; i < intervals.requiredRegisters(); i++) {
          if (activeRegister + i <= registerConstraint) {
            int unhandledStart = unhandledInterval.getStart();
            usePositions.set(
                activeRegister + i, intervals.firstUseAfter(unhandledStart), intervals);
          }
        }
      }
    }

    // Compute next use location for all inactive registers that overlaps the unhandled interval.
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals : inactive) {
      int inactiveRegister = intervals.getRegister();
      if (inactiveRegister <= registerConstraint && intervals.overlaps(unhandledInterval)) {
        for (int i = 0; i < intervals.requiredRegisters(); i++) {
          if (inactiveRegister + i <= registerConstraint) {
            int firstUse = intervals.firstUseAfter(unhandledInterval.getStart());
            if (firstUse < usePositions.get(inactiveRegister + i)) {
              usePositions.set(inactiveRegister + i, firstUse, intervals);
            }
          }
        }
      }
    }

    // Disallow the reuse of argument registers by always treating them as being used
    // at instruction number 0.
    for (int i = 0; i < numberOfArgumentRegisters; i++) {
      usePositions.set(i, 0);
    }

    // Disallow reuse of the move exception register if we have reserved one.
    if (overlapsMoveExceptionInterval(unhandledInterval)) {
      usePositions.set(getMoveExceptionRegister(), 0);
    }

    // Treat active and inactive linked argument intervals as pinned. They cannot be given another
    // register at their uses.
    blockLinkedRegisters(
        active, unhandledInterval, registerConstraint, usePositions, blockedPositions);
    blockLinkedRegisters(inactive, unhandledInterval, registerConstraint, usePositions,
        blockedPositions);

    // Get the register (pair) that has the highest use position.
    boolean needsRegisterPair = unhandledInterval.getType().isWide();

    // First look for a candidate that can be rematerialized.
    int candidate = getLargestValidCandidate(unhandledInterval, registerConstraint,
        needsRegisterPair, usePositions, Type.CONST_NUMBER);
    if (candidate != Integer.MAX_VALUE) {
      // Look for a non-const, non-monitor candidate.
      int otherCandidate = getLargestValidCandidate(
          unhandledInterval, registerConstraint, needsRegisterPair, usePositions, Type.OTHER);
      if (otherCandidate == Integer.MAX_VALUE || candidate == REGISTER_CANDIDATE_NOT_FOUND) {
        candidate = otherCandidate;
      } else {
        int largestConstUsePosition =
            getLargestPosition(usePositions, candidate, needsRegisterPair);
        if (largestConstUsePosition - MIN_CONSTANT_FREE_FOR_POSITIONS <
            unhandledInterval.getStart()) {
          // The candidate that can be rematerialized has a live range too short to use it.
          candidate = otherCandidate;
        }
      }
      // If looking at constants and non-monitor registers did not find a valid spill candidate
      // we allow ourselves to look at monitor spill candidates as well. Registers holding objects
      // used as monitors should not be spilled if we can avoid it. Spilling them can lead
      // to Art lock verification issues.
      // Also, at this point we still don't allow splitting any string new-instance instructions
      // that have been explicitly blocked. Doing so could lead to a behavioral bug on some ART
      // runtimes (b/80118070). To remove this restriction, we would need to know when the call to
      // <init> has definitely happened, and would be safe to split the value after that point.
      if (candidate == REGISTER_CANDIDATE_NOT_FOUND) {
        candidate = getLargestValidCandidate(
            unhandledInterval, registerConstraint, needsRegisterPair, usePositions, Type.MONITOR);
      }
    }

    int largestUsePosition = getLargestPosition(usePositions, candidate, needsRegisterPair);
    int blockedPosition = getLargestPosition(blockedPositions, candidate, needsRegisterPair);

    if (largestUsePosition < unhandledInterval.getFirstUse()) {
      // All active and inactive intervals are used before current. Therefore, it is best to spill
      // current itself.
      int splitPosition = unhandledInterval.getFirstUse();
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals split = unhandledInterval.splitBefore(splitPosition);
      assert split != unhandledInterval;
      int registerNumber = getSpillRegister(unhandledInterval);
      assignFreeRegisterToUnhandledInterval(unhandledInterval, registerNumber);
      unhandledInterval.setSpilled(true);
      unhandled.add(split);
    } else {
      // We will use the candidate register(s) for unhandledInterval, and therefore potentially
      // need to adjust maxRegisterNumber.
      int candidateEnd = candidate + unhandledInterval.requiredRegisters() - 1;
      if (candidateEnd > maxRegisterNumber) {
        increaseCapacity(candidateEnd);
      }

      if (blockedPosition > unhandledInterval.getEnd()) {
        // Spilling can make a register available for the entire interval.
        assignRegisterAndSpill(unhandledInterval, candidate, needsRegisterPair);
      } else {
        // Spilling only makes a register available for the first part of current.
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals splitChild = unhandledInterval.splitBefore(blockedPosition);
        unhandled.add(splitChild);
        assignRegisterAndSpill(unhandledInterval, candidate, needsRegisterPair);
      }
    }
  }

  private int getLargestPosition(
          com.debughelper.tools.r8.ir.regalloc.RegisterPositions positions, int register, boolean needsRegisterPair) {
    int position = positions.get(register);
    if (needsRegisterPair) {
      return Math.min(position, positions.get(register + 1));
    }
    return position;
  }

  private void assignRegisterAndSpill(
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, int candidate, boolean candidateIsWide) {
    // Split and spill intersecting active intervals for this register.
    spillOverlappingActiveIntervals(unhandledInterval, candidate, candidateIsWide);
    // Now that that active intervals have been spilled, we are free to take the candidate.
    assignRegister(unhandledInterval, candidate);
    takeFreeRegistersForIntervals(unhandledInterval);
    active.add(unhandledInterval);
    // Split all overlapping inactive intervals for this register. They need to have a new
    // register assigned at the next use.
    splitOverlappingInactiveIntervals(unhandledInterval, candidate, candidateIsWide);
  }

  protected void splitOverlappingInactiveIntervals(
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, int candidate, boolean candidateIsWide) {
    List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> newInactive = new ArrayList<>();
    Iterator<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> inactiveIterator = inactive.iterator();
    while (inactiveIterator.hasNext()) {
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals = inactiveIterator.next();
      if (intervals.usesRegister(candidate, candidateIsWide)
          && intervals.overlaps(unhandledInterval)) {
        if (intervals.isLinked() && !intervals.isArgumentInterval()) {
          // If the inactive register is linked but not an argument, it needs to get the
          // same register again at the next use after the start of the unhandled interval.
          // If there are no such uses, we can use a different register for the remainder
          // of the inactive interval and therefore do not have to split here.
          int nextUsePosition = intervals.firstUseAfter(unhandledInterval.getStart());
          if (nextUsePosition != Integer.MAX_VALUE) {
            com.debughelper.tools.r8.ir.regalloc.LiveIntervals split = intervals.splitBefore(nextUsePosition);
            split.setRegister(intervals.getRegister());
            newInactive.add(split);
          }
        }
        if (intervals.getStart() > unhandledInterval.getStart()) {
          // The inactive live intervals hasn't started yet. Clear the temporary register
          // assignment and move back to unhandled for register reassignment.
          intervals.clearRegisterAssignment();
          inactiveIterator.remove();
          unhandled.add(intervals);
        } else {
          // The inactive live intervals is in a live range hole. Split the interval and
          // put the ranges after the hole into the unhandled set for register reassignment.
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals split = intervals.splitBefore(unhandledInterval.getStart());
          unhandled.add(split);
        }
      }
    }
    inactive.addAll(newInactive);
  }

  private void spillOverlappingActiveIntervals(
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals unhandledInterval, int candidate, boolean candidateIsWide) {
    assert unhandledInterval.getRegister() == NO_REGISTER;
    assert atLeastOneOfRegistersAreTaken(candidate, candidateIsWide);
    // Spill overlapping active intervals.
    List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> newActive = new ArrayList<>();
    Iterator<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> activeIterator = active.iterator();
    while (activeIterator.hasNext()) {
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals = activeIterator.next();
      assert registersForIntervalsAreTaken(intervals);
      if (intervals.usesRegister(candidate, candidateIsWide)) {
        activeIterator.remove();
        int registerNumber = getSpillRegister(intervals);
        // Important not to free the registers for intervals before finding a spill register,
        // because we might otherwise end up spilling to the current registers of intervals,
        // depending on getSpillRegister.
        freeOccupiedRegistersForIntervals(intervals);
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals splitChild = intervals.splitBefore(unhandledInterval.getStart());
        assignRegister(splitChild, registerNumber);
        splitChild.setSpilled(true);
        takeFreeRegistersForIntervals(splitChild);
        assert splitChild.getRegister() != NO_REGISTER;
        assert intervals.getRegister() != NO_REGISTER;
        newActive.add(splitChild);
        // If the constant is split before its first actual use, mark the constant as being
        // spilled. That will allows us to remove it afterwards if it is rematerializable.
        if (intervals.getValue().isConstNumber()
            && intervals.getStart() == intervals.getValue().definition.getNumber()
            && intervals.getUses().size() == 1) {
          intervals.setSpilled(true);
        }
        if (splitChild.getUses().size() > 0) {
          if (splitChild.isLinked() && !splitChild.isArgumentInterval()) {
            // Spilling a value with a pinned register. We need to move back at the next use.
            com.debughelper.tools.r8.ir.regalloc.LiveIntervals splitOfSplit = splitChild.splitBefore(splitChild.getFirstUse());
            splitOfSplit.setRegister(intervals.getRegister());
            inactive.add(splitOfSplit);
          } else if (intervals.getValue().isConstNumber()) {
            // TODO(ager): Do this for all constants. Currently we only rematerialize const
            // number and therefore we only do it for numbers at this point.
            splitRangesForSpilledConstant(splitChild, registerNumber);
          } else if (intervals.isArgumentInterval()) {
            splitRangesForSpilledArgument(splitChild);
          } else {
            splitRangesForSpilledInterval(splitChild, registerNumber);
          }
        }
      }
    }
    active.addAll(newActive);
    assert registersAreFree(candidate, candidateIsWide);
  }

  private void splitRangesForSpilledArgument(com.debughelper.tools.r8.ir.regalloc.LiveIntervals spilled) {
    assert spilled.isSpilled();
    assert spilled.isArgumentInterval();
    // Argument intervals are spilled to the original argument register. We don't know what
    // that is yet, and therefore we split before the next use to make sure we get a usable
    // register at the next use.
    if (!spilled.getUses().isEmpty()) {
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals split = spilled.splitBefore(spilled.getUses().first().getPosition());
      unhandled.add(split);
    }
  }

  private void splitRangesForSpilledInterval(com.debughelper.tools.r8.ir.regalloc.LiveIntervals spilled, int registerNumber) {
    // Spilling a non-pinned, non-rematerializable value. We use the value in the spill
    // register for as long as possible to avoid further moves.
    assert spilled.isSpilled();
    assert !spilled.getValue().isConstNumber();
    assert !spilled.isLinked() || spilled.isArgumentInterval();
    boolean isSpillingToArgumentRegister =
        (spilled.isArgumentInterval() || registerNumber < numberOfArgumentRegisters);
    if (isSpillingToArgumentRegister) {
      registerNumber = com.debughelper.tools.r8.dex.Constants.U16BIT_MAX;
    }
    com.debughelper.tools.r8.ir.regalloc.LiveIntervalsUse firstUseWithLowerLimit = null;
    boolean hasUsesBeforeFirstUseWithLowerLimit = false;
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervalsUse use : spilled.getUses()) {
      if (registerNumber > use.getLimit()) {
        firstUseWithLowerLimit = use;
        break;
      } else {
        hasUsesBeforeFirstUseWithLowerLimit = true;
      }
    }
    if (hasUsesBeforeFirstUseWithLowerLimit) {
      spilled.setSpilled(false);
    }
    if (firstUseWithLowerLimit != null) {
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals splitOfSplit = spilled.splitBefore(firstUseWithLowerLimit.getPosition());
      unhandled.add(splitOfSplit);
    }
  }

  private void splitRangesForSpilledConstant(com.debughelper.tools.r8.ir.regalloc.LiveIntervals spilled, int spillRegister) {
    // When spilling a constant we should not keep it alive in the spill register, instead
    // we should use rematerialization. We aggressively spill the constant in all gaps
    // between uses that span more than a certain number of instructions. If we needed to
    // spill we are running low on registers and this constant should get out of the way
    // as much as possible.
    assert spilled.isSpilled();
    assert spilled.getValue().isConstNumber();
    assert !spilled.isLinked() || spilled.isArgumentInterval();
    // Do not split range if constant is reused by one of the eleven following instruction.
    int maxGapSize = 11 * com.debughelper.tools.r8.ir.code.IRCode.INSTRUCTION_NUMBER_DELTA;
    if (!spilled.getUses().isEmpty()) {
      // Split at first use after the spill position and add to unhandled to get a register
      // assigned for rematerialization.
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals split = spilled.splitBefore(spilled.getFirstUse());
      unhandled.add(split);
      // Now repeatedly split for each use that is more than maxGapSize away from the previous use.
      boolean changed = true;
      while (changed) {
        changed = false;
        int previousUse = split.getStart();
        for (com.debughelper.tools.r8.ir.regalloc.LiveIntervalsUse use : split.getUses()) {
          if (use.getPosition() - previousUse > maxGapSize) {
            // Found a use that is more than gap size away from the previous use. Split after
            // the previous use.
            split = split.splitBefore(previousUse + com.debughelper.tools.r8.ir.code.IRCode.INSTRUCTION_NUMBER_DELTA);
            // If the next use is not at the start of the new split, we split again at the next use
            // and spill the gap.
            if (toGapPosition(use.getPosition()) > split.getStart()) {
              assignRegister(split, spillRegister);
              split.setSpilled(true);
              inactive.add(split);
              split = split.splitBefore(use.getPosition());
            }
            // |split| now starts at the next use - add it to unhandled to get a register
            // assigned for rematerialization.
            unhandled.add(split);
            // Break out of the loop to start iterating the new split uses.
            changed = true;
            break;
          }
          previousUse = use.getPosition();
        }
      }
    }
  }

  private void blockLinkedRegisters(
          List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> intervalsList, com.debughelper.tools.r8.ir.regalloc.LiveIntervals interval, int registerConstraint,
          com.debughelper.tools.r8.ir.regalloc.RegisterPositions usePositions, RegisterPositions blockedPositions) {
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals other : intervalsList) {
      if (other.isLinked()) {
        int register = other.getRegister();
        if (register <= registerConstraint && other.overlaps(interval)) {
          for (int i = 0; i < other.requiredRegisters(); i++) {
            if (register + i <= registerConstraint) {
              int firstUse = other.firstUseAfter(interval.getStart());
              if (firstUse < blockedPositions.get(register + i)) {
                blockedPositions.set(register + i, firstUse, other);
                // If we start blocking registers other than linked arguments, we might need to
                // explicitly update the use positions as well as blocked positions.
                assert usePositions.get(register + i) <= blockedPositions.get(register + i);
              }
            }
          }
        }
      }
    }
  }

  private void insertMoves() {
    computeRematerializableBits();

    com.debughelper.tools.r8.ir.regalloc.SpillMoveSet spillMoves = new com.debughelper.tools.r8.ir.regalloc.SpillMoveSet(this, code);
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals : liveIntervals) {
      if (intervals.hasSplits()) {
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals current = intervals;
        PriorityQueue<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> sortedChildren = new PriorityQueue<>();
        sortedChildren.addAll(current.getSplitChildren());
        for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals split = sortedChildren.poll();
             split != null;
             split = sortedChildren.poll()) {
          int position = split.getStart();
          spillMoves.addSpillOrRestoreMove(toGapPosition(position), split, current);
          current = split;
        }
      }
    }

    resolveControlFlow(spillMoves);
    firstParallelMoveTemporary = maxRegisterNumber + 1;
    maxRegisterNumber += spillMoves.scheduleAndInsertMoves(maxRegisterNumber + 1);
  }

  private void computeRematerializableBits() {
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals liveInterval : liveIntervals) {
      liveInterval.computeRematerializable(this);
    }
  }

  // Resolve control flow by inserting phi moves and by inserting moves when the live intervals
  // change for a value across block boundaries.
  private void resolveControlFlow(com.debughelper.tools.r8.ir.regalloc.SpillMoveSet spillMoves) {
    // For a control-flow graph like the following where a value v is split at an instruction in
    // block C a spill move is inserted in block C to transfer the value from register r0 to
    // register r1. However, that move is not executed when taking the control-flow edge from
    // B to D and therefore resolution will insert a move from r0 to r1 on that edge.
    //
    //             r0            r1
    //   v: |----------------|--------|
    //
    //       A ----> B ----> C ----> D
    //               |               ^
    //               +---------------+
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      for (com.debughelper.tools.r8.ir.code.BasicBlock successor : block.getSuccessors()) {
        // If we are processing an exception edge, we need to use the throwing instruction
        // as the instruction we are coming from.
        int fromInstruction = block.exit().getNumber();
        boolean isCatch = block.hasCatchSuccessor(successor);
        if (isCatch) {
          for (com.debughelper.tools.r8.ir.code.Instruction instruction : block.getInstructions()) {
            if (instruction.instructionTypeCanThrow()) {
              fromInstruction = instruction.getNumber();
              break;
            }
          }
        }
        int toInstruction = successor.entry().getNumber();

        // Insert spill/restore moves when a value changes across a block boundary.
        Set<com.debughelper.tools.r8.ir.code.Value> liveAtEntry = liveAtEntrySets.get(successor);
        for (com.debughelper.tools.r8.ir.code.Value value : liveAtEntry) {
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals parentInterval = value.getLiveIntervals();
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals fromIntervals = parentInterval.getSplitCovering(fromInstruction);
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals toIntervals = parentInterval.getSplitCovering(toInstruction);
          if (fromIntervals != toIntervals) {
            if (block.exit().isGoto() && !isCatch) {
              spillMoves.addOutResolutionMove(fromInstruction - 1, toIntervals, fromIntervals);
            } else if (successor.entry().isMoveException()) {
              spillMoves.addInResolutionMove(toInstruction + 1, toIntervals, fromIntervals);
            } else {
              spillMoves.addInResolutionMove(toInstruction - 1, toIntervals, fromIntervals);
            }
          }
        }

        // Insert phi moves.
        int predIndex = successor.getPredecessors().indexOf(block);
        for (com.debughelper.tools.r8.ir.code.Phi phi : successor.getPhis()) {
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals toIntervals = phi.getLiveIntervals().getSplitCovering(toInstruction);
          com.debughelper.tools.r8.ir.code.Value operand = phi.getOperand(predIndex);
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals fromIntervals =
              operand.getLiveIntervals().getSplitCovering(fromInstruction);
          if (fromIntervals != toIntervals && !toIntervals.isArgumentInterval()) {
            assert block.getSuccessors().size() == 1;
            spillMoves.addPhiMove(fromInstruction - 1, toIntervals, fromIntervals);
          }
        }
      }
    }
  }

  private static void addLiveRange(
      com.debughelper.tools.r8.ir.code.Value value,
      com.debughelper.tools.r8.ir.code.BasicBlock block,
      int end,
      List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> liveIntervals,
      com.debughelper.tools.r8.utils.InternalOptions options) {
    int firstInstructionInBlock = block.entry().getNumber();
    int instructionsSize = block.getInstructions().size() * com.debughelper.tools.r8.ir.code.IRCode.INSTRUCTION_NUMBER_DELTA;
    int lastInstructionInBlock =
        firstInstructionInBlock + instructionsSize - com.debughelper.tools.r8.ir.code.IRCode.INSTRUCTION_NUMBER_DELTA;
    int instructionNumber;
    if (value.isPhi()) {
      instructionNumber = firstInstructionInBlock;
    } else {
      com.debughelper.tools.r8.ir.code.Instruction instruction = value.definition;
      instructionNumber = instruction.getNumber();
    }
    if (value.getLiveIntervals() == null) {
      com.debughelper.tools.r8.ir.code.Value current = value.getStartOfConsecutive();
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals = new com.debughelper.tools.r8.ir.regalloc.LiveIntervals(current);
      while (true) {
        liveIntervals.add(intervals);
        com.debughelper.tools.r8.ir.code.Value next = current.getNextConsecutive();
        if (next == null) {
          break;
        }
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals nextIntervals = new com.debughelper.tools.r8.ir.regalloc.LiveIntervals(next);
        intervals.link(nextIntervals);
        current = next;
        intervals = nextIntervals;
      }
    }
    com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals = value.getLiveIntervals();
    if (firstInstructionInBlock <= instructionNumber &&
        instructionNumber <= lastInstructionInBlock) {
      if (value.isPhi()) {
        // Phis need to interfere with spill restore moves inserted before the instruction because
        // the phi value is defined on the inflowing edge.
        instructionNumber--;
      }
      intervals.addRange(new com.debughelper.tools.r8.ir.regalloc.LiveRange(instructionNumber, end));
      assert unconstrainedForCf(intervals.getRegisterLimit(), options);
      if (options.isGeneratingDex() && !value.isPhi()) {
        int constraint = value.definition.maxOutValueRegister();
        intervals.addUse(new com.debughelper.tools.r8.ir.regalloc.LiveIntervalsUse(instructionNumber, constraint));
      }
    } else {
      intervals.addRange(new com.debughelper.tools.r8.ir.regalloc.LiveRange(firstInstructionInBlock - 1, end));
    }
  }

  private void computeLiveRanges() {
    computeLiveRanges(options, code, liveAtEntrySets, liveIntervals);
    // Art VMs before debughelper M assume that the register for the receiver never changes its value.
    // This assumption is used during verification. Allowing the receiver register to be
    // overwritten can therefore lead to verification errors. If we could be targeting one of these
    // VMs we block the receiver register throughout the method.
    if ((options.canHaveThisTypeVerifierBug() || options.canHaveThisJitCodeDebuggingBug())
        && !code.method.accessFlags.isStatic()) {
      for (com.debughelper.tools.r8.ir.code.Instruction instruction : code.blocks.get(0).getInstructions()) {
        if (instruction.isArgument() && instruction.outValue().isThis()) {
          com.debughelper.tools.r8.ir.code.Value thisValue = instruction.outValue();
          com.debughelper.tools.r8.ir.regalloc.LiveIntervals thisIntervals = thisValue.getLiveIntervals();
          thisIntervals.getRanges().clear();
          thisIntervals.addRange(new com.debughelper.tools.r8.ir.regalloc.LiveRange(0, code.getNextInstructionNumber()));
          for (Set<com.debughelper.tools.r8.ir.code.Value> values : liveAtEntrySets.values()) {
            values.add(thisValue);
          }
          return;
        }
      }
    }
  }

  /**
   * Compute live ranges based on liveAtEntry sets for all basic blocks.
   */
  public static void computeLiveRanges(
      com.debughelper.tools.r8.utils.InternalOptions options,
      com.debughelper.tools.r8.ir.code.IRCode code,
      Map<com.debughelper.tools.r8.ir.code.BasicBlock, Set<com.debughelper.tools.r8.ir.code.Value>> liveAtEntrySets,
      List<com.debughelper.tools.r8.ir.regalloc.LiveIntervals> liveIntervals) {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.topologicallySortedBlocks()) {
      Set<com.debughelper.tools.r8.ir.code.Value> live = new HashSet<>();
      List<com.debughelper.tools.r8.ir.code.BasicBlock> successors = block.getSuccessors();
      Set<com.debughelper.tools.r8.ir.code.Value> phiOperands = new HashSet<>();
      for (com.debughelper.tools.r8.ir.code.BasicBlock successor : successors) {
        live.addAll(liveAtEntrySets.get(successor));
        for (com.debughelper.tools.r8.ir.code.Phi phi : successor.getPhis()) {
          live.remove(phi);
          phiOperands.add(phi.getOperand(successor.getPredecessors().indexOf(block)));
          assert phi.getDebugValues().stream().allMatch(com.debughelper.tools.r8.ir.code.Value::needsRegister);
          phiOperands.addAll(phi.getDebugValues());
        }
      }
      live.addAll(phiOperands);
      List<com.debughelper.tools.r8.ir.code.Instruction> instructions = block.getInstructions();
      for (com.debughelper.tools.r8.ir.code.Value value : live) {
        int end = block.entry().getNumber() + instructions.size() * com.debughelper.tools.r8.ir.code.IRCode.INSTRUCTION_NUMBER_DELTA;
        // Make sure that phi operands do not overlap the phi live range. The phi operand is
        // not live until the next instruction, but only until the gap before the next instruction
        // where the phi value takes over.
        if (phiOperands.contains(value)) {
          end--;
        }
        addLiveRange(value, block, end, liveIntervals, options);
      }
      ListIterator<com.debughelper.tools.r8.ir.code.Instruction> iterator =
          block.getInstructions().listIterator(block.getInstructions().size());
      while (iterator.hasPrevious()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = iterator.previous();
        com.debughelper.tools.r8.ir.code.Value definition = instruction.outValue();
        if (definition != null) {
          // For instructions that define values which have no use create a live range covering
          // the instruction. This will typically be instructions that can have side effects even
          // if their output is not used.
          if (!definition.isUsed()) {
            addLiveRange(
                definition,
                block,
                instruction.getNumber() + com.debughelper.tools.r8.ir.code.IRCode.INSTRUCTION_NUMBER_DELTA,
                liveIntervals,
                options);
            assert !options.isGeneratingClassFiles() || instruction.isArgument()
                : "Arguments should be the only potentially unused local in CF";
          }
          live.remove(definition);
        }
        for (com.debughelper.tools.r8.ir.code.Value use : instruction.inValues()) {
          if (use.needsRegister()) {
            assert unconstrainedForCf(instruction.maxInValueRegister(), options);
            if (!live.contains(use)) {
              live.add(use);
              addLiveRange(use, block, instruction.getNumber(), liveIntervals, options);
            }
            if (options.isGeneratingDex()) {
              int inConstraint = instruction.maxInValueRegister();
              com.debughelper.tools.r8.ir.regalloc.LiveIntervals useIntervals = use.getLiveIntervals();
              // Arguments are always kept in their original, incoming register. For every
              // unconstrained use of an argument we therefore use its incoming register.
              // As a result, we do not need to record that the argument is being used at the
              // current instruction.
              //
              // For ranged invoke instructions that use a subset of the arguments in the current
              // order, registering a use for the arguments at the invoke can cause us to run out of
              // registers. That is because all arguments are forced back into a chosen register at
              // all uses. Therefore, if we register a use of an argument where we can actually use
              // it in the argument register, the register allocator would use two registers for the
              // argument but in reality only use one.
              boolean isUnconstrainedArgumentUse =
                  use.isArgument() && inConstraint == com.debughelper.tools.r8.dex.Constants.U16BIT_MAX;
              if (!isUnconstrainedArgumentUse) {
                useIntervals.addUse(new LiveIntervalsUse(instruction.getNumber(), inConstraint));
              }
            }
          }
        }
        if (options.debug) {
          int number = instruction.getNumber();
          for (com.debughelper.tools.r8.ir.code.Value use : instruction.getDebugValues()) {
            assert use.needsRegister();
            if (!live.contains(use)) {
              live.add(use);
              addLiveRange(use, block, number, liveIntervals, options);
            }
          }
        }
      }
    }
  }

  private static boolean unconstrainedForCf(int constraint, InternalOptions options) {
    return !options.isGeneratingClassFiles() || constraint == com.debughelper.tools.r8.dex.Constants.U16BIT_MAX;
  }

  private void clearUserInfo() {
    code.blocks.forEach(com.debughelper.tools.r8.ir.code.BasicBlock::clearUserInfo);
  }

  // Rewrites casts on the form "lhs = (T) rhs" into "(T) rhs" and replaces the uses of lhs by rhs.
  // This transformation helps to ensure that we do not insert unnecessary moves in bridge methods
  // with an invoke-range instruction, since all the arguments to the invoke-range instruction will
  // be original, consecutive arguments of the enclosing method (and importantly, not values that
  // have been defined by a check-cast instruction).
  private void transformBridgeMethod() {
    assert implementationIsBridge(this.code);
    com.debughelper.tools.r8.ir.code.BasicBlock entry = this.code.blocks.getFirst();
    com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = entry.listIterator();
    // Create a mapping from argument values to their index, while scanning over the arguments.
    Reference2IntMap<com.debughelper.tools.r8.ir.code.Value> argumentIndices = new Reference2IntArrayMap<>();
    while (iterator.peekNext().isArgument()) {
      com.debughelper.tools.r8.ir.code.Value argument = iterator.next().asArgument().outValue();
      argumentIndices.put(argument, argumentIndices.size());
    }
    // Move forward until the invocation.
    while (!iterator.peekNext().isInvoke()) {
      iterator.next();
    }
    com.debughelper.tools.r8.ir.code.Invoke invokeInstruction = iterator.peekNext().asInvoke();
    // Determine if all of the arguments can be cast without having to move them into lower
    // registers.
    int numberOfRequiredRegisters = numberOfArgumentRegisters;
    if (invokeInstruction.outValue() != null) {
      numberOfRequiredRegisters += invokeInstruction.outValue().requiredRegisters();
    }
    if (numberOfRequiredRegisters - 1 > Constants.U8BIT_MAX) {
      return;
    }
    // Determine if the arguments are consecutive input arguments.
    List<com.debughelper.tools.r8.ir.code.Value> arguments = invokeInstruction.arguments();
    if (arguments.size() >= 1) {
      int previousArgumentIndex = -1;
      for (int i = 0; i < arguments.size(); ++i) {
        com.debughelper.tools.r8.ir.code.Value current = arguments.get(i);
        if (!current.isArgument()) {
          current = current.definition.asCheckCast().object();
        }
        assert current.isArgument();
        int currentArgumentIndex = argumentIndices.getInt(current);
        if (previousArgumentIndex >= 0 && currentArgumentIndex != previousArgumentIndex + 1) {
          return;
        }
        previousArgumentIndex = currentArgumentIndex;
      }
    } else {
      return;
    }

    // Rewrite all casts before the invocation on the form "lhs = (T) rhs" into "(T) rhs", and
    // replace the uses of lhs by rhs.
    while (iterator.peekPrevious().isCheckCast()) {
      CheckCast castInstruction = iterator.previous().asCheckCast();
      castInstruction.outValue().replaceUsers(castInstruction.object());
      castInstruction.setOutValue(null);
    }
  }

  // Returns true if the IR for this method consists of zero or more arguments, zero or more casts
  // of the arguments, a single invocation, an optional cast of the result, and a return (in this
  // particular order).
  private static boolean implementationIsBridge(com.debughelper.tools.r8.ir.code.IRCode code) {
    if (code.blocks.size() > 1) {
      return false;
    }
    com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = code.blocks.getFirst().listIterator();
    // Move forward to the first instruction after the definition of the arguments.
    while (iterator.hasNext() && iterator.peekNext().isArgument()) {
      iterator.next();
    }
    // Move forward to the first instruction after the casts.
    while (iterator.hasNext()
        && iterator.peekNext().isCheckCast()
        && iterator.peekNext().asCheckCast().object().isArgument()) {
      iterator.next();
    }
    // Check if there is an invoke instruction followed by an optional cast of the result,
    // and a return.
    if (!iterator.hasNext() || !iterator.next().isInvoke()) {
      return false;
    }
    if (iterator.hasNext() && iterator.peekNext().isCheckCast()) {
      iterator.next();
    }
    if (!iterator.hasNext() || !iterator.next().isReturn()) {
      return false;
    }
    return true;
  }

  private com.debughelper.tools.r8.ir.code.Value createValue(ValueType type) {
    com.debughelper.tools.r8.ir.code.Value value = code.createValue(type, null);
    value.setNeedsRegister(true);
    return value;
  }

  private void replaceArgument(com.debughelper.tools.r8.ir.code.Invoke invoke, int index, com.debughelper.tools.r8.ir.code.Value newArgument) {
    com.debughelper.tools.r8.ir.code.Value argument = invoke.arguments().get(index);
    invoke.arguments().set(index, newArgument);
    argument.removeUser(invoke);
    newArgument.addUser(invoke);
  }

  private void generateArgumentMoves(com.debughelper.tools.r8.ir.code.Invoke invoke, com.debughelper.tools.r8.ir.code.InstructionListIterator insertAt) {
    // If the invoke instruction require more than 5 registers we link the inputs because they
    // need to be in consecutive registers.
    if (invoke.requiredArgumentRegisters() > 5 && !argumentsAreAlreadyLinked(invoke)) {
      List<com.debughelper.tools.r8.ir.code.Value> arguments = invoke.arguments();
      com.debughelper.tools.r8.ir.code.Value previous = null;

      PriorityQueue<com.debughelper.tools.r8.ir.code.Move> insertAtDefinition = null;
      if (invoke.requiredArgumentRegisters() > 16) {
        insertAtDefinition =
            new PriorityQueue<>(
                (x, y) -> x.src().definition.getNumber() - y.src().definition.getNumber());

        // Number the instructions in this basic block such that we can order the moves according
        // to the positions of the instructions that define the srcs of the moves. Note that this
        // is a local numbering of the instructions. These instruction numbers will be recomputed
        // just before the liveness analysis.
        com.debughelper.tools.r8.ir.code.BasicBlock block = invoke.getBlock();
        if (block.entry().getNumber() == -1) {
          block.numberInstructions(0);
        }
      }

      for (int i = 0; i < arguments.size(); i++) {
        com.debughelper.tools.r8.ir.code.Value argument = arguments.get(i);
        com.debughelper.tools.r8.ir.code.Value newArgument = argument;
        // In debug mode, we have debug instructions that are also moves. Do not generate another
        // move if there already is a move instruction that we can use. We generate moves if:
        //
        // 1. the argument is not defined by a move,
        //
        // 2. the argument is already linked or would cause a cycle if linked, or
        //
        // 3. the argument has a register constraint (the argument moves are there to make the
        //    input value to a ranged invoke unconstrained.)
        if (argument.definition == null ||
            !argument.definition.isMove() ||
            argument.isLinked() ||
            argument == previous ||
            argument.hasRegisterConstraint()) {
          newArgument = createValue(argument.outType());
          com.debughelper.tools.r8.ir.code.Move move = new com.debughelper.tools.r8.ir.code.Move(newArgument, argument);
          move.setBlock(invoke.getBlock());
          replaceArgument(invoke, i, newArgument);

          boolean argumentIsDefinedInSameBlock =
              argument.definition != null && argument.definition.getBlock() == invoke.getBlock();
          if (invoke.requiredArgumentRegisters() > 16 && argumentIsDefinedInSameBlock) {
            // Heuristic: Insert the move immediately after the argument. This increases the
            // likelyhood that we will be able to move the argument directly into the register it
            // needs to be in for the ranged invoke.
            //
            // If we instead were to insert the moves immediately before the ranged invoke when
            // there are many arguments, then there is a high risk that we will need to spill the
            // arguments before they get moved to the correct register right before the invoke.
            assert move.src().definition.getNumber() >= 0;
            insertAtDefinition.add(move);
            move.setPosition(argument.definition.getPosition());
          } else {
            insertAt.add(move);
            move.setPosition(invoke.getPosition());
          }
        }
        if (previous != null) {
          previous.linkTo(newArgument);
        }
        previous = newArgument;
      }

      if (insertAtDefinition != null && !insertAtDefinition.isEmpty()) {
        generateArgumentMovesAtDefinitions(invoke, insertAtDefinition, insertAt);
      }
    }
  }

  private void generateArgumentMovesAtDefinitions(
          com.debughelper.tools.r8.ir.code.Invoke invoke, PriorityQueue<com.debughelper.tools.r8.ir.code.Move> insertAtDefinition, com.debughelper.tools.r8.ir.code.InstructionListIterator insertAt) {
    Move move = insertAtDefinition.poll();
    // Rewind instruction iterator to the position where the first move needs to be inserted.
    com.debughelper.tools.r8.ir.code.Instruction previousDefinition =
        move.src().isArgument() ? lastArgumentValue.definition : move.src().definition;
    while (insertAt.peekPrevious() != previousDefinition) {
      insertAt.previous();
    }
    // Insert the instructions one by one after their definition.
    insertAt.add(move);
    while (!insertAtDefinition.isEmpty()) {
      move = insertAtDefinition.poll();
      com.debughelper.tools.r8.ir.code.Instruction currentDefinition =
          move.src().isArgument() ? lastArgumentValue.definition : move.src().definition;
      assert currentDefinition.getNumber() >= previousDefinition.getNumber();
      if (currentDefinition.getNumber() > previousDefinition.getNumber()) {
        // Move the instruction iterator forward to where this move needs to be inserted.
        while (insertAt.peekPrevious() != currentDefinition) {
          insertAt.next();
        }
      }
      insertAt.add(move);
      // Update state.
      previousDefinition = currentDefinition;
    }
    // Move the instruction iterator forward to its old position.
    while (insertAt.peekNext() != invoke) {
      insertAt.next();
    }
  }

  private boolean argumentsAreAlreadyLinked(Invoke invoke) {
    Iterator<com.debughelper.tools.r8.ir.code.Value> it = invoke.arguments().iterator();
    com.debughelper.tools.r8.ir.code.Value current = it.next();
    while (it.hasNext()) {
      com.debughelper.tools.r8.ir.code.Value next = it.next();
      if (!current.isLinked() || current.getNextConsecutive() != next) {
        return false;
      }
      current = next;
    }
    return true;
  }

  private void createArgumentLiveIntervals(List<com.debughelper.tools.r8.ir.code.Value> arguments) {
    int index = 0;
    for (com.debughelper.tools.r8.ir.code.Value argument : arguments) {
      // Add a live range to this value from the beginning of the block up to the argument
      // instruction to avoid dead arguments without a range. This may create an actually empty
      // range like [0,0[ but that works, too.
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals argumentInterval = new com.debughelper.tools.r8.ir.regalloc.LiveIntervals(argument);
      argumentInterval.addRange(new LiveRange(0, index));
      liveIntervals.add(argumentInterval);
      index += com.debughelper.tools.r8.ir.code.IRCode.INSTRUCTION_NUMBER_DELTA;
    }
  }

  private void linkArgumentValuesAndIntervals(List<com.debughelper.tools.r8.ir.code.Value> arguments) {
    if (!arguments.isEmpty()) {
      com.debughelper.tools.r8.ir.code.Value last = firstArgumentValue = arguments.get(0);
      for (int i = 1; i < arguments.size(); ++i) {
        com.debughelper.tools.r8.ir.code.Value next = arguments.get(i);
        last.linkTo(next);
        last.getLiveIntervals().link(next.getLiveIntervals());
        last = next;
      }
      lastArgumentValue = last;
    }
  }

  private void insertArgumentMoves() {
    // Record the constraint that incoming arguments are in consecutive registers.
    List<com.debughelper.tools.r8.ir.code.Value> arguments = code.collectArguments();
    createArgumentLiveIntervals(arguments);
    linkArgumentValuesAndIntervals(arguments);
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
        if (instruction.isInvoke()) {
          // Rewind so moves are inserted before the invoke.
          it.previous();
          // Generate the argument moves.
          generateArgumentMoves(instruction.asInvoke(), it);
          // Move past the move again.
          it.next();
        }
      }
    }
  }

  private void computeNeedsRegister() {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.topologicallySortedBlocks()) {
      for (com.debughelper.tools.r8.ir.code.Instruction instruction : block.getInstructions()) {
        if (instruction.outValue() != null) {
          instruction.outValue().computeNeedsRegister();
        }
      }
    }
  }

  private void pinArgumentRegisters() {
    // Special handling for arguments. Pin their register.
    if (firstArgumentValue != null) {
      increaseCapacity(numberOfArgumentRegisters - 1, true);
      int register = 0;
      for (com.debughelper.tools.r8.ir.code.Value current = firstArgumentValue;
           current != null;
           current = current.getNextConsecutive()) {
        com.debughelper.tools.r8.ir.regalloc.LiveIntervals argumentLiveInterval = current.getLiveIntervals();
        assignRegister(argumentLiveInterval, register);
        register += current.requiredRegisters();
      }
    }
  }

  private void increaseCapacity(int newMaxRegisterNumber) {
    increaseCapacity(newMaxRegisterNumber, false);
  }

  private void increaseCapacity(int newMaxRegisterNumber, boolean takeRegisters) {
    if (!takeRegisters) {
      for (int register = maxRegisterNumber + 1; register <= newMaxRegisterNumber; ++register) {
        freeRegisters.add(register);
      }
    }
    maxRegisterNumber = newMaxRegisterNumber;
  }

  private int getFreeConsecutiveRegisters(int numberOfRegisters) {
    int oldMaxRegisterNumber = maxRegisterNumber;
    Iterator<Integer> freeRegistersIterator = freeRegisters.iterator();
    int first = getNextFreeRegister(freeRegistersIterator);
    int current = first;
    while (current - first + 1 != numberOfRegisters) {
      for (int i = 0; i < numberOfRegisters - 1; i++) {
        int next = getNextFreeRegister(freeRegistersIterator);
        // We cannot allow that some are argument registers and some or not, because they will no
        // longer be consecutive if we later decide to increment maxRegisterNumber.
        if (next != current + 1 || next == numberOfArgumentRegisters) {
          first = next;
          current = first;
          break;
        }
        current++;
      }
    }
    for (int register = oldMaxRegisterNumber + 1; register <= maxRegisterNumber; ++register) {
      boolean wasAdded = freeRegisters.add(register);
      assert wasAdded;
    }
    // Either all the consecutive registers are from the argument registers, or all are from the
    // non-argument registers.
    assert (first < numberOfArgumentRegisters
            && first + numberOfRegisters - 1 < numberOfArgumentRegisters)
        || (first >= numberOfArgumentRegisters
            && first + numberOfRegisters - 1 >= numberOfArgumentRegisters);
    return first;
  }

  private int getNextFreeRegister(Iterator<Integer> freeRegistersIterator) {
    if (freeRegistersIterator.hasNext()) {
      return freeRegistersIterator.next();
    }
    return ++maxRegisterNumber;
  }

  private void excludeRegistersForInterval(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals, IntSet excluded) {
    int register = intervals.getRegister();
    assert register != NO_REGISTER;

    for (int i = 0; i < intervals.requiredRegisters(); i++) {
      if (freeRegisters.remove(register + i)) {
        excluded.add(register + i);
      }
    }

    if (intervals.isArgumentInterval() && intervals != intervals.getSplitParent()) {
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals parent = intervals.getSplitParent();
      if (parent.getRegister() != register) {
        excludeRegistersForInterval(parent, excluded);
      }
    }
  }

  private void freeOccupiedRegistersForIntervals(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    assert registersForIntervalsAreTaken(intervals);
    int register = intervals.getRegister();
    assert register + intervals.requiredRegisters() - 1 <= maxRegisterNumber;
    freeRegisters.add(register);
    if (intervals.getType().isWide()) {
      freeRegisters.add(register + 1);
    }

    if (intervals.isArgumentInterval() && intervals != intervals.getSplitParent()) {
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals parent = intervals.getSplitParent();
      if (parent.getRegister() != intervals.getRegister()) {
        freeOccupiedRegistersForIntervals(intervals.getSplitParent());
      }
    }
  }

  private void takeFreeRegisters(int register, boolean isWide) {
    assert registersAreFree(register, isWide);
    freeRegisters.remove(register);
    if (isWide) {
      freeRegisters.remove(register + 1);
    }
  }

  private void takeFreeRegistersForIntervals(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    takeFreeRegisters(intervals.getRegister(), intervals.getType().isWide());

    if (intervals.isArgumentInterval() && intervals != intervals.getSplitParent()) {
      com.debughelper.tools.r8.ir.regalloc.LiveIntervals parent = intervals.getSplitParent();
      if (parent.getRegister() != intervals.getRegister()) {
        takeFreeRegistersForIntervals(parent);
      }
    }
  }

  private boolean registerIsFree(int register) {
    return freeRegisters.contains(register)
        || (hasDedicatedMoveExceptionRegister() && register == getMoveExceptionRegister());
  }

  // Note: treats a register as free if it is in the set of free registers, or it is the dedicated
  // move exception register.
  private boolean registersAreFree(int register, boolean isWide) {
    return registerIsFree(register) && (!isWide || registerIsFree(register + 1));
  }

  private boolean registersAreTaken(int register, boolean isWide) {
    return !freeRegisters.contains(register) && (!isWide || !freeRegisters.contains(register + 1));
  }

  private boolean registersForIntervalsAreTaken(com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals) {
    assert intervals.getRegister() != NO_REGISTER;
    return registersAreTaken(intervals.getRegister(), intervals.getType().isWide());
  }

  private boolean atLeastOneOfRegistersAreTaken(int register, boolean isWide) {
    return !freeRegisters.contains(register) || (isWide && !freeRegisters.contains(register + 1));
  }

  private boolean noLinkedValues() {
    for (BasicBlock block : code.blocks) {
      for (Phi phi : block.getPhis()) {
        assert phi.getNextConsecutive() == null;
      }
      for (Instruction instruction : block.getInstructions()) {
        for (com.debughelper.tools.r8.ir.code.Value value : instruction.inValues()) {
          assert value.getNextConsecutive() == null;
        }
        assert instruction.outValue() == null ||
            instruction.outValue().getNextConsecutive() == null;
      }
    }
    return true;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("Live ranges:\n");
    for (com.debughelper.tools.r8.ir.regalloc.LiveIntervals intervals : liveIntervals) {
      com.debughelper.tools.r8.ir.code.Value value = intervals.getValue();
      builder.append(value);
      builder.append(" ");
      builder.append(intervals);
    }
    builder.append("\nLive range ascii art: \n");
    for (LiveIntervals intervals : liveIntervals) {
      Value value = intervals.getValue();
      if (intervals.getRegister() == NO_REGISTER) {
        com.debughelper.tools.r8.utils.StringUtils.appendRightPadded(builder, value + " (no reg): ", 20);
      } else {
        StringUtils.appendRightPadded(builder, value + " r" + intervals.getRegister() + ": ", 20);
      }
      builder.append("|");
      builder.append(intervals.toAscciArtString());
      builder.append("\n");
    }
    return builder.toString();
  }
}
