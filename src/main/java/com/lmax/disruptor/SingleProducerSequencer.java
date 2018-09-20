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

import java.util.concurrent.locks.LockSupport;

import com.lmax.disruptor.util.Util;

//生产者序列填充
abstract class SingleProducerSequencerPad extends AbstractSequencer {
    //填充行 56位
    protected long p1, p2, p3, p4, p5, p6, p7;

    SingleProducerSequencerPad(int bufferSize, WaitStrategy waitStrategy) {
        super(bufferSize, waitStrategy);
    }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad {
    SingleProducerSequencerFields(int bufferSize, WaitStrategy waitStrategy) {
        super(bufferSize, waitStrategy);
    }

    /**
     * Set to -1 as sequence starting point
     */
    //下一个序列值 不断增加的
    long nextValue = Sequence.INITIAL_VALUE;
    //控制不能无限增加的一个最小值 当nextValue-buffersize>这个值的时候 就不能贼增加nextValue，发布的可获取的序列号最小的序列
    long cachedValue = Sequence.INITIAL_VALUE;
}

/**
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.</p>
 * <p>
 * <p>* Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.</p>
 */

public final class SingleProducerSequencer extends SingleProducerSequencerFields {

    protected long p1, p2, p3, p4, p5, p6, p7;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public SingleProducerSequencer(int bufferSize, WaitStrategy waitStrategy) {
        super(bufferSize, waitStrategy);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(int requiredCapacity) {
        return hasAvailableCapacity(requiredCapacity, false);
    }

    private boolean hasAvailableCapacity(int requiredCapacity, boolean doStore) {
        //初始的时候是-1
        long nextValue = this.nextValue;
        //验证是否可以获取申请的长度
        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        //已填充数据的最小的序列值
        long cachedGatingSequence = this.cachedValue;
        //如果
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue) {
            if (doStore) {
                //设置当前指针
                cursor.setVolatile(nextValue);  // StoreLoad fence
            }

            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            //设置最小的可消费的指针序列
            this.cachedValue = minSequence;
            //如果大于最小的序列 则不能进行申请
            if (wrapPoint > minSequence) {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next() {
        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be > 0");
        }
        //控制生产者的序列 没添加一个事件就增加1
        long nextValue = this.nextValue;
        //
        long nextSequence = nextValue + n;
        //nextSequence-ringbuffer长度 获取一个最小的序列值 通过这个来判断 是否还能继续获取下一个序列值
        long wrapPoint = nextSequence - bufferSize;
        //获取可消费的最小的一个序列值
        long cachedGatingSequence = this.cachedValue;

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue) {
            //可以通知消费者 进行消费了
            cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence;
            //生产者产生的最小的序列值  与消费者要进行消费的序列值 进行比较 如果大于  则通知消费者赶快进行消费
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue))) {
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }
            //重新设置可消费的最小的序列值
            this.cachedValue = minSequence;
        }

        this.nextValue = nextSequence;

        return nextSequence;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException {
        if (n < 1) {
            throw new IllegalArgumentException("n must be > 0");
        }

        if (!hasAvailableCapacity(n, true)) {
            throw InsufficientCapacityException.INSTANCE;
        }

        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity() {
        long nextValue = this.nextValue;

        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        long produced = nextValue;
        //生产者产生的最大的序列-消费者要消费的最小的序列 得到还有多少没有进行消费 用容量-这个值就是剩余的容量
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(long sequence) {
        this.nextValue = sequence;
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(long sequence) {
        cursor.set(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(long lo, long hi) {
        publish(hi);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence) {
        return sequence <= cursor.get();
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence) {
        return availableSequence;
    }
}
