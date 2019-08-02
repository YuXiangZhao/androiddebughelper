// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.regalloc;

import com.debughelper.tools.r8.code.MoveType;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.Value;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A set of spill moves and functionality to schedule and insert them in the code.
 */
class SpillMoveSet {
  // Spill and restore moves on entry.
  private final Map<Integer, Set<SpillMove>> instructionToInMoves = new HashMap<>();
  // Spill and restore moves on exit.
  private final Map<Integer, Set<SpillMove>> instructionToOutMoves = new HashMap<>();
  // Phi moves.
  private final Map<Integer, Set<SpillMove>> instructionToPhiMoves = new HashMap<>();
  // The code into which to insert the moves.
  private final com.debughelper.tools.r8.ir.code.IRCode code;
  // The register allocator generating moves.
  private final LinearScanRegisterAllocator allocator;
  // Mapping from instruction numbers to the block that start with that instruction if any.
  private final Map<Integer, com.debughelper.tools.r8.ir.code.BasicBlock> blockStartMap = new HashMap<>();
  // The number of temporary registers used for parallel moves when scheduling the moves.
  private int usedTempRegisters = 0;

  public SpillMoveSet(LinearScanRegisterAllocator allocator, IRCode code) {
    this.allocator = allocator;
    this.code = code;
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      blockStartMap.put(block.entry().getNumber(), block);
    }
  }

  /**
   * Add a spill or restore move.
   *
   * <p>This is used between all interval splits. The move is only inserted if it is restoring
   * from a spill slot at a position that is not at the start of a block. All block start
   * moves are handled by resolution.
   *
   * @param i instruction number (gap number) for which to insert the move
   * @param to interval representing the destination for the move
   * @param from interval representating the source for the move
   */
  public void addSpillOrRestoreMove(int i, LiveIntervals to, LiveIntervals from) {
    assert i % 2 == 1;
    assert to.getSplitParent() == from.getSplitParent();
    com.debughelper.tools.r8.ir.code.BasicBlock atEntryToBlock = blockStartMap.get(i + 1);
    if (atEntryToBlock == null) {
      addInMove(i, to, from);
    }
  }

  /**
   * Add a resolution move. This deals with moves in order to transfer an SSA value to another
   * register across basic block boundaries.
   *
   * @param i instruction number (gap number) for which to insert the move
   * @param to interval representing the destination for the move
   * @param from interval representing the source for the move
   */
  public void addInResolutionMove(int i, LiveIntervals to, LiveIntervals from) {
    assert to.getSplitParent() == from.getSplitParent();
    addInMove(i, to, from);
  }

  public void addOutResolutionMove(int i, LiveIntervals to, LiveIntervals from) {
    assert to.getSplitParent() == from.getSplitParent();
    addOutMove(i, to, from);
  }

  /**
   * Add a phi move to transfer an incoming SSA value to the SSA value in the destination block.
   *
   * @param i instruction number (gap number) for which to insert the move
   * @param to interval representing the destination for the move
   * @param from interval representing the source for the move
   */
  public void addPhiMove(int i, LiveIntervals to, LiveIntervals from) {
    assert i % 2 == 1;
    SpillMove move = new SpillMove(moveTypeForIntervals(to, from), to, from);
    move.updateMaxNonSpilled();
    instructionToPhiMoves.computeIfAbsent(i, (k) -> new LinkedHashSet<>()).add(move);
  }

  private void addInMove(int i, LiveIntervals to, LiveIntervals from) {
    assert i % 2 == 1;
    instructionToInMoves.computeIfAbsent(i, (k) -> new LinkedHashSet<>()).add(
        new SpillMove(moveTypeForIntervals(to, from), to, from));
  }

  private void addOutMove(int i, LiveIntervals to, LiveIntervals from) {
    assert i % 2 == 1;
    instructionToOutMoves.computeIfAbsent(i, (k) -> new LinkedHashSet<>()).add(
        new SpillMove(moveTypeForIntervals(to, from), to, from));
  }

  /**
   * Schedule the moves added to this SpillMoveSet and insert them into the code.
   *
   * <p>Scheduling requires parallel move semantics for some of the moves. That can require
   * the use of temporary registers to break cycles.
   *
   * @param tempRegister the first temporary register to use
   * @return the number of temporary registers used
   */
  public int scheduleAndInsertMoves(int tempRegister) {
    for (BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator insertAt = block.listIterator();
      if (block == code.blocks.getFirst()) {
        // Move insertAt iterator to the first non-argument, such that moves for the arguments will
        // be inserted after the last argument.
        while (insertAt.hasNext() && insertAt.peekNext().isArgument()) {
          insertAt.next();
        }
        // Generate moves for each argument.
        for (Value argumentValue = allocator.firstArgumentValue;
             argumentValue != null;
             argumentValue = argumentValue.getNextConsecutive()) {
          com.debughelper.tools.r8.ir.code.Instruction instruction = argumentValue.definition;
          int number = instruction.getNumber();
          if (needsMovesBeforeInstruction(number)) {
            scheduleMovesBeforeInstruction(tempRegister, instruction, insertAt);
          }
        }
      }

      while (insertAt.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = insertAt.peekNext();
        assert !instruction.isArgument();
        int number = instruction.getNumber();
        if (needsMovesBeforeInstruction(number)) {
          scheduleMovesBeforeInstruction(tempRegister, instruction, insertAt);
        }
        insertAt.next();
      }
    }
    return usedTempRegisters;
  }

  private com.debughelper.tools.r8.code.MoveType moveTypeForIntervals(LiveIntervals to, LiveIntervals from) {
    com.debughelper.tools.r8.code.MoveType toType = to.getMoveType();
    com.debughelper.tools.r8.code.MoveType fromType = from.getMoveType();
    if (toType == com.debughelper.tools.r8.code.MoveType.OBJECT || fromType == com.debughelper.tools.r8.code.MoveType.OBJECT) {
      assert fromType == com.debughelper.tools.r8.code.MoveType.OBJECT || fromType == com.debughelper.tools.r8.code.MoveType.SINGLE;
      assert toType == com.debughelper.tools.r8.code.MoveType.OBJECT || toType == com.debughelper.tools.r8.code.MoveType.SINGLE;
      return com.debughelper.tools.r8.code.MoveType.OBJECT;
    }
    assert toType == fromType;
    return toType;
  }

  private boolean needsMovesBeforeInstruction(int i) {
    return instructionToOutMoves.containsKey(i - 1)
        || instructionToInMoves.containsKey(i - 1)
        || instructionToPhiMoves.containsKey(i - 1);
  }

  private SpillMove getMoveWithSource(LiveIntervals src, Collection<SpillMove> moves) {
    for (SpillMove move : moves) {
      if (move.from == src) {
        return move;
      }
    }
    return null;
  }

  private SpillMove getMoveWritingSourceRegister(SpillMove inMove, Collection<SpillMove> moves) {
    int srcRegister = inMove.from.getRegister();
    int srcRegisters = inMove.type.requiredRegisters();
    for (SpillMove move : moves) {
      int dstRegister = move.to.getRegister();
      int dstRegisters = move.type.requiredRegisters();
      for (int s = 0; s < srcRegisters; s++) {
        for (int d = 0; d < dstRegisters; d++) {
          if ((dstRegister + d) == (srcRegister + s)) {
            return move;
          }
        }
      }
    }
    return null;
  }

  // Shortcut move chains where we have a move in the in move set that moves to a
  // location that is then moved in the out move set to its final destination.
  //
  // r1 <- r0 (in move set)
  //
  // r2 <- r1 (out move set)
  //
  // is replaced with
  //
  // r2 <- r0 (out move set)
  //
  // Care must be taken when there are other moves in the in move set that can interfere
  // with the value. For example:
  //
  // r1 <- r0 (in move set)
  // r0 <- r1 (in move set)
  //
  // r2 <- r1 (out move set)
  //
  // Additionally, if a phi move uses the destination of the in move it needs to stay.
  //
  // If such interference exists we don't rewrite the moves and parallel moves are generated
  // to swap r1 and r0 on entry via a temporary register.
  private void pruneParallelMoveSets(
      Set<SpillMove> inMoves, Set<SpillMove> outMoves, Set<SpillMove> phiMoves) {
    Iterator<SpillMove> it = inMoves.iterator();
    while (it.hasNext()) {
      SpillMove inMove = it.next();
      SpillMove outMove = getMoveWithSource(inMove.to, outMoves);
      SpillMove blockingInMove = getMoveWritingSourceRegister(inMove, inMoves);
      SpillMove blockingPhiMove = getMoveWithSource(inMove.to, phiMoves);
      if (outMove != null && blockingInMove == null && blockingPhiMove == null) {
        it.remove();
        outMove.from = inMove.from;
      }
    }
  }

  private void scheduleMovesBeforeInstruction(
          int tempRegister, com.debughelper.tools.r8.ir.code.Instruction instruction, com.debughelper.tools.r8.ir.code.InstructionListIterator insertAt) {
    int number = instruction.getNumber();

    com.debughelper.tools.r8.ir.code.Position position;
    if (insertAt.hasPrevious() && insertAt.peekPrevious().isMoveException()) {
      position = insertAt.peekPrevious().getPosition();
    } else {
      Instruction next = insertAt.peekNext();
      assert next.getNumber() == number || instruction.isArgument();
      position = next.getPosition();
      if (position.isNone() && next.isGoto()) {
        position = next.asGoto().getTarget().getPosition();
      }
    }

    // Spill and restore moves for the incoming edge.
    Set<SpillMove> inMoves =
        instructionToInMoves.computeIfAbsent(number - 1, (k) -> new LinkedHashSet<>());
    removeArgumentRestores(inMoves);

    // Spill and restore moves for the outgoing edge.
    Set<SpillMove> outMoves =
        instructionToOutMoves.computeIfAbsent(number - 1, (k) -> new LinkedHashSet<>());
    removeArgumentRestores(outMoves);

    // Get the phi moves for this instruction and schedule them with the out going spill moves.
    Set<SpillMove> phiMoves =
        instructionToPhiMoves.computeIfAbsent(number - 1, (k) -> new LinkedHashSet<>());

    // Remove/rewrite moves that we can guarantee will not be needed.
    pruneParallelMoveSets(inMoves, outMoves, phiMoves);

    // Schedule out and phi moves together.
    outMoves.addAll(phiMoves);

    // Perform parallel move scheduling independently for the in and out moves.
    scheduleMoves(tempRegister, inMoves, insertAt, position);
    scheduleMoves(tempRegister, outMoves, insertAt, position);
  }

  // Remove restore moves that restore arguments. Since argument register reuse is
  // disallowed at this point we know that argument registers do not change value and
  // therefore we don't have to perform spill moves. Performing spill moves will also
  // make art reject the code because it loses type information for the argument.
  //
  // TODO(ager): We are dealing with some of these moves as rematerialization. However,
  // we are still generating actual moves back to the original argument register.
  // We should get rid of this method and avoid generating the moves in the first place.
  private void removeArgumentRestores(Set<SpillMove> moves) {
    Iterator<SpillMove> moveIterator = moves.iterator();
    while (moveIterator.hasNext()) {
      SpillMove move = moveIterator.next();
      // The argument registers can be used for other values than the arguments in intervals where
      // the arguments are not live, so it is insufficient to check that the destination register
      // is in the argument register range.
      if (move.to.getRegister() < allocator.numberOfArgumentRegisters
          && move.to.isArgumentInterval()) {
        moveIterator.remove();
      }
    }
  }

  private void scheduleMoves(
          int tempRegister, Set<SpillMove> moves, InstructionListIterator insertAt, Position position) {
    RegisterMoveScheduler scheduler = new RegisterMoveScheduler(insertAt, tempRegister, position);
    for (SpillMove move : moves) {
      // Do not generate moves to spill a value that can be rematerialized.
      if (move.to.isSpilledAndRematerializable()) {
        continue;
      }
      // Use rematerialization when possible and otherwise generate moves.
      if (move.from.isSpilledAndRematerializable()) {
        assert allocator.unadjustedRealRegisterFromAllocated(move.to.getRegister()) < 256;
        scheduler.addMove(
            new RegisterMove(move.to.getRegister(), move.type, move.from.getValue().definition));
      } else if (move.to.getRegister() != move.from.getRegister()) {
        // In case the runtime might have a bound-check elimination bug we make sure to define all
        // indexing constants with an actual const instruction rather than a move. This appears to
        // avoid a bug where the index variable could end up being uninitialized.
        if (code.options.canHaveBoundsCheckEliminationBug()
            && move.from.getValue().isConstNumber()
            && move.type == MoveType.SINGLE
            && allocator.unadjustedRealRegisterFromAllocated(move.to.getRegister()) < 256) {
          scheduler.addMove(
              new RegisterMove(move.to.getRegister(), move.type, move.from.getValue().definition));
        } else {
          scheduler.addMove(
              new RegisterMove(move.to.getRegister(), move.from.getRegister(), move.type));
        }
      }
    }
    scheduler.schedule();
    usedTempRegisters = Math.max(usedTempRegisters, scheduler.getUsedTempRegisters());
  }
}
