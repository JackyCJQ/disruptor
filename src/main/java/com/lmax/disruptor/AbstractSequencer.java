/*
 * Copyright 2011 LMAX Ltd.
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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.lmax.disruptor.util.Util;

/**
 * Base class for the various sequencer types (single/multi).  Provides
 * common functionality like the management of gating sequences (add/remove) and
 * ownership of the current cursor.
 */
public abstract class AbstractSequencer implements Sequencer {
    //原子更新操作 消费者消费事件的时候 会更新gatingSequences 这个字段
    private static final AtomicReferenceFieldUpdater<AbstractSequencer, Sequence[]> SEQUENCE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(AbstractSequencer.class, Sequence[].class, "gatingSequences");
    //环的大小，也变相的设置sequencer可控制序列的大小
    protected final int bufferSize;
    //消费者等待序列策略
    protected final WaitStrategy waitStrategy;
    //一个Sequence类型的指针，当前可获取的最小的序列，也就是生产者可发布事件的序列
    protected final Sequence cursor = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    //消费者可获取的最小的序列 因为可能是多个消费者 所以需要volatile 确保多个消费者并发访问
    protected volatile Sequence[] gatingSequences = new Sequence[0];

    /**
     * Create with the specified buffer size and wait strategy.
     *
     * @param bufferSize   The total number of entries, must be a positive power of 2.
     * @param waitStrategy The wait strategy used by this sequencer
     */
    public AbstractSequencer(int bufferSize, WaitStrategy waitStrategy) {
        //在这里又加入了以下验证必须是2的n次方
        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must not be less than 1");
        }
        //环的大小必须是2的n次方
        if (Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }
        //在这里才进行赋值
        this.bufferSize = bufferSize;
        this.waitStrategy = waitStrategy;
    }

    /**
     * 返回对应的long值，获取当前发布指针的序列
     *
     * @see Sequencer#getCursor()
     */
    @Override
    public final long getCursor() {
        return cursor.get();
    }

    /**
     * 返回环的大小
     * @see Sequencer#getBufferSize()
     */
    @Override
    public final int getBufferSize() {
        return bufferSize;
    }

    /**
     * 原子的修改
     * 修改本类中的引用 gatingSequences
     *
     * @see Sequencer#addGatingSequences(Sequence...)
     */
    @Override
    public final void addGatingSequences(Sequence... gatingSequences) {
        SequenceGroups.addSequences(this, SEQUENCE_UPDATER, this, gatingSequences);
    }

    /**
     * 原子的修改
     * 修改本类中的引用 gatingSequences
     *
     * @see Sequencer#removeGatingSequence(Sequence)
     */
    @Override
    public boolean removeGatingSequence(Sequence sequence) {
        return SequenceGroups.removeSequence(this, SEQUENCE_UPDATER, sequence);
    }

    /**
     * 得到最小的序列号
     *
     * @see Sequencer#getMinimumSequence()
     */
    @Override
    public long getMinimumSequence() {
        return Util.getMinimumSequence(gatingSequences, cursor.get());
    }

    /**
     * 默认只有一种序列屏障实现 ProcessingSequenceBarrier
     *
     * @see Sequencer#newBarrier(Sequence...)
     */
    @Override
    public SequenceBarrier newBarrier(Sequence... sequencesToTrack) {
        return new ProcessingSequenceBarrier(this, waitStrategy, cursor, sequencesToTrack);
    }

    /**
     * Creates an event poller for this sequence that will use the supplied data provider and
     * gating sequences.
     *
     * @param dataProvider    The data source for users of this event poller
     * @param gatingSequences Sequence to be gated on.
     * @return A poller that will gate on this ring buffer and the supplied sequences.
     */
    @Override
    public <T> EventPoller<T> newPoller(DataProvider<T> dataProvider, Sequence... gatingSequences) {
        return EventPoller.newInstance(dataProvider, this, new Sequence(), cursor, gatingSequences);
    }

    @Override
    public String toString() {
        return "AbstractSequencer{" +
                "waitStrategy=" + waitStrategy +
                ", cursor=" + cursor +
                ", gatingSequences=" + Arrays.toString(gatingSequences) +
                '}';
    }
}