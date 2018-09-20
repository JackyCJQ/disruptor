/*
 * Copyright 2012 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import static java.util.Arrays.copyOf;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Provides static methods for managing a {@link SequenceGroup} object.
 */
class SequenceGroups {
    static <T> void addSequences(
            final T holder,
            final AtomicReferenceFieldUpdater<T, Sequence[]> updater,
            final Cursored cursor,
            final Sequence... sequencesToAdd) {

        long cursorSequence;
        Sequence[] updatedSequences;
        Sequence[] currentSequences;

        //cas更新gatingSequences
        do {
            //先获取原来的gatingSequences集合
            currentSequences = updater.get(holder);
            //扩充updatedSequences集合
            updatedSequences = copyOf(currentSequences, currentSequences.length + sequencesToAdd.length);
            //获取当前指针序列
            cursorSequence = cursor.getCursor();

            int index = currentSequences.length;
            for (Sequence sequence : sequencesToAdd) {
                //把后来添加的的序列的指针同一设置位当前指针
                sequence.set(cursorSequence);
                updatedSequences[index++] = sequence;
            }
        }
        while (!updater.compareAndSet(holder, currentSequences, updatedSequences));
        //再次设置指针  什么意思？
        cursorSequence = cursor.getCursor();
        for (Sequence sequence : sequencesToAdd) {
            sequence.set(cursorSequence);
        }
    }

    static <T> boolean removeSequence(
            final T holder,
            final AtomicReferenceFieldUpdater<T, Sequence[]> sequenceUpdater,
            final Sequence sequence) {
        int numToRemove;
        Sequence[] oldSequences;
        Sequence[] newSequences;

        do {
            //先获取原来的gatingSequences集合
            oldSequences = sequenceUpdater.get(holder);
            //移除所有相等的sequence
            numToRemove = countMatching(oldSequences, sequence);

            if (0 == numToRemove) {
                break;
            }

            final int oldSize = oldSequences.length;
            //新的序列的大小
            newSequences = new Sequence[oldSize - numToRemove];
            //重新循环，去掉相同的sequence
            for (int i = 0, pos = 0; i < oldSize; i++) {
                final Sequence testSequence = oldSequences[i];
                if (sequence != testSequence) {
                    newSequences[pos++] = testSequence;
                }
            }
        }
        while (!sequenceUpdater.compareAndSet(holder, oldSequences, newSequences));

        return numToRemove != 0;
    }

    private static <T> int countMatching(T[] values, final T toMatch) {
        int numToRemove = 0;
        for (T value : values) {
            if (value == toMatch) // Specifically uses identity
            {
                numToRemove++;
            }
        }
        return numToRemove;
    }
}
