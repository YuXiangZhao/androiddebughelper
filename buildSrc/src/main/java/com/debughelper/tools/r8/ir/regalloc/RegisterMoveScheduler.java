// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.regalloc;

import com.debughelper.tools.r8.code.MoveType;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.code.Argument;
import com.debughelper.tools.r8.ir.code.ConstInstruction;
import com.debughelper.tools.r8.ir.code.ConstNumber;
import com.debughelper.tools.r8.ir.code.FixedRegisterValue;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Move;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.Value;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class RegisterMoveScheduler {
  // The set of moves to schedule.
  private final Set<RegisterMove> moveSet = new TreeSet<>();
  // Mapping to keep track of which values currently corresponds to each other.
  // This is initially an identity map but changes as we insert moves.
  private final Map<Integer, Integer> valueMap = new HashMap<>();
  // Number of temp registers used to schedule the moves.
  private int usedTempRegisters = 0;
  // Location at which to insert the scheduled moves.
  private final com.debughelper.tools.r8.ir.code.InstructionListIterator insertAt;
  // Debug position associated with insertion point.
  private final com.debughelper.tools.r8.ir.code.Position position;
  // The first available temporary register.
  private final int tempRegister;

  public RegisterMoveScheduler(
          com.debughelper.tools.r8.ir.code.InstructionListIterator insertAt, int tempRegister, com.debughelper.tools.r8.ir.code.Position position) {
    this.insertAt = insertAt;
    this.tempRegister = tempRegister;
    this.position = position;
  }

  public RegisterMoveScheduler(InstructionListIterator insertAt, int tempRegister) {
    this(insertAt, tempRegister, Position.none());
  }

  public void addMove(RegisterMove move) {
    moveSet.add(move);
    if (move.src != LiveIntervals.NO_REGISTER) {
      valueMap.put(move.src, move.src);
    }
    valueMap.put(move.dst, move.dst);
  }

  public void schedule() {
    // Worklist of moves that are ready to be inserted.
    Deque<RegisterMove> worklist = new LinkedList<>();

    // Initialize worklist with the moves that do not interfere with other moves.
    Iterator<RegisterMove> iterator = moveSet.iterator();
    while (iterator.hasNext()) {
      RegisterMove move = iterator.next();
      if (!move.isBlocked(moveSet, valueMap)) {
        worklist.addLast(move);
        iterator.remove();
      }
    }

    // Process the worklist generating moves. If the worklist becomes empty while the move set
    // still contains elements we need to use a temporary to break cycles.
    while (!worklist.isEmpty() || !moveSet.isEmpty()) {
      while (!worklist.isEmpty()) {
        RegisterMove move = worklist.removeFirst();
        assert !move.isBlocked(moveSet, valueMap);
        // Insert the move.
        Integer generatedDest = createMove(move);
        // Update the value map with the information that dest can be used instead of
        // src starting now.
        if (move.src != LiveIntervals.NO_REGISTER) {
          valueMap.put(move.src, generatedDest);
        }
        // Iterate and find the moves that were blocked because they need to write to
        // one of the move src. That is now valid because the move src is preserved in dest.
        iterator = moveSet.iterator();
        while (iterator.hasNext()) {
          RegisterMove other = iterator.next();
          if (!other.isBlocked(moveSet, valueMap)) {
            worklist.addLast(other);
            iterator.remove();
          }
        }
      }
      if (!moveSet.isEmpty()) {
        // The remaining moves are conflicting. Chose a move and unblock it by generating moves to
        // temporary registers for its destination value(s).
        RegisterMove move = pickMoveToUnblock();
        createMoveDestToTemp(move);
        worklist.addLast(move);
      }
    }
  }

  public int getUsedTempRegisters() {
    return usedTempRegisters;
  }

  private List<RegisterMove> findMovesWithSrc(int src, com.debughelper.tools.r8.code.MoveType type) {
    List<RegisterMove> result = new ArrayList<>();
    assert src != LiveIntervals.NO_REGISTER;
    for (RegisterMove move : moveSet) {
      if (move.src == LiveIntervals.NO_REGISTER) {
        continue;
      }
      int moveSrc = valueMap.get(move.src);
      if (moveSrc == src) {
        result.add(move);
      } else if (move.type == com.debughelper.tools.r8.code.MoveType.WIDE && (moveSrc + 1) == src) {
        result.add(move);
      } else if (type == com.debughelper.tools.r8.code.MoveType.WIDE && (moveSrc - 1) == src) {
        result.add(move);
      }
    }
    return result;
  }

  private Integer createMove(RegisterMove move) {
    Instruction instruction;
    if (move.definition != null) {
      if (move.definition.isArgument()) {
        Argument argument = move.definition.asArgument();
        int argumentRegister = argument.outValue().getLiveIntervals().getRegister();
        com.debughelper.tools.r8.ir.code.Value to = new com.debughelper.tools.r8.ir.code.FixedRegisterValue(argument.outType(), move.dst);
        com.debughelper.tools.r8.ir.code.Value from = new com.debughelper.tools.r8.ir.code.FixedRegisterValue(argument.outType(), argumentRegister);
        instruction = new com.debughelper.tools.r8.ir.code.Move(to, from);
      } else {
        assert move.definition.isOutConstant();
        ConstInstruction definition = move.definition.getOutConstantConstInstruction();
        if (definition.isConstNumber()) {
          com.debughelper.tools.r8.ir.code.Value to = new com.debughelper.tools.r8.ir.code.FixedRegisterValue(move.definition.outType(), move.dst);
          instruction = new ConstNumber(to, definition.asConstNumber().getRawValue());
        } else {
          throw new Unreachable("Unexpected definition");
        }
      }
    } else {
      com.debughelper.tools.r8.ir.code.Value to = new com.debughelper.tools.r8.ir.code.FixedRegisterValue(move.type.toValueType(), move.dst);
      com.debughelper.tools.r8.ir.code.Value from = new com.debughelper.tools.r8.ir.code.FixedRegisterValue(move.type.toValueType(), valueMap.get(move.src));
      instruction = new com.debughelper.tools.r8.ir.code.Move(to, from);
    }
    instruction.setPosition(position);
    insertAt.add(instruction);
    return move.dst;

  }

  private void createMoveDestToTemp(RegisterMove move) {
    // In order to unblock this move we might have to move more than one value to temporary
    // registers if we are unlucky with the overlap for values that use two registers.
    List<RegisterMove> movesWithSrc = findMovesWithSrc(move.dst, move.type);
    assert movesWithSrc.size() > 0;
    for (RegisterMove moveWithSrc : movesWithSrc) {
      // TODO(ager): For now we always use a new temporary register whenever we have to unblock
      // a move. The move scheduler can have multiple unblocking temps live at the same time
      // and therefore we cannot have just one tempRegister (pair). However, we could check here
      // if the previously used tempRegisters is still needed by any of the moves in the move set
      // (taking the value map into account). If not, we can reuse the temp register instead
      // of generating a new one.
      com.debughelper.tools.r8.ir.code.Value to = new com.debughelper.tools.r8.ir.code.FixedRegisterValue(
          moveWithSrc.type.toValueType(), tempRegister + usedTempRegisters);
      Value from = new FixedRegisterValue(
          moveWithSrc.type.toValueType(), valueMap.get(moveWithSrc.src));
      com.debughelper.tools.r8.ir.code.Move instruction = new Move(to, from);
      instruction.setPosition(position);
      insertAt.add(instruction);
      valueMap.put(moveWithSrc.src, tempRegister + usedTempRegisters);
      usedTempRegisters += moveWithSrc.type == com.debughelper.tools.r8.code.MoveType.WIDE ? 2 : 1;
    }
  }

  private RegisterMove pickMoveToUnblock() {
    Iterator<RegisterMove> iterator = moveSet.iterator();
    RegisterMove move = null;
    // Pick a non-wide move to unblock if possible.
    while (iterator.hasNext()) {
      move = iterator.next();
      if (move.type != MoveType.WIDE) {
        break;
      }
    }
    iterator.remove();
    return move;
  }
}
