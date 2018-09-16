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


/**
 * 消费者直接交互的对象
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a cursor sequence and optional dependent {@link EventProcessor}(s),
 * using the given WaitStrategy.
 */
final class ProcessingSequenceBarrier implements SequenceBarrier {
    //消费者等待策略
    private final WaitStrategy waitStrategy;
    //是否有依赖的序列
    private final Sequence dependentSequence;
    //默认为false 线程间变量可见的
    private volatile boolean alerted = false;
    //当前序列指针
    private final Sequence cursorSequence;
    //生产者直接交互的对象
    private final Sequencer sequencer;

    ProcessingSequenceBarrier(
            final Sequencer sequencer,
            final WaitStrategy waitStrategy,
            final Sequence cursorSequence,
            final Sequence[] dependentSequences) {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.cursorSequence = cursorSequence;
        //没有依赖的
        if (0 == dependentSequences.length) {
            dependentSequence = cursorSequence;
        } else {
            //如果有序列依赖关系 则生成一个关系组
            dependentSequence = new FixedSequenceGroup(dependentSequences);
        }
    }

    @Override
    /**
     * 等待下一个序列号
     */
    public long waitFor(final long sequence)
            throws AlertException, InterruptedException, TimeoutException {
        checkAlert();
        //通过等待策略里面来获取可得到的序列
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);
        //如果获取的序列小于当前序列
        if (availableSequence < sequence) {
            return availableSequence;
        }
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    @Override
    public long getCursor() {
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted() {
        return alerted;
    }

    @Override
    public void alert() {
        alerted = true;
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert() {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException {
        if (alerted) {
            throw AlertException.INSTANCE;
        }
    }
}