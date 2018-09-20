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
 * Yielding strategy that uses a Thread.yield() for {@link com.lmax.disruptor.EventProcessor}s waiting on a barrier
 * after an initially spinning.
 * <p>
 * This strategy will use 100% CPU, but will more readily give up the CPU than a busy spin strategy if other threads
 * require CPU resource.
 */
public final class YieldingWaitStrategy implements WaitStrategy {
    private static final int SPIN_TRIES = 100;

    @Override
    public long waitFor(
            final long sequence, Sequence cursor, final Sequence dependentSequence, final SequenceBarrier barrier)
            throws AlertException, InterruptedException {
        long availableSequence;
        int counter = SPIN_TRIES;
        //如果依赖的序列小于要等待的序列 要等依赖的序列先处理完
        while ((availableSequence = dependentSequence.get()) < sequence) {
            counter = applyWaitMethod(barrier, counter);
        }

        return availableSequence;
    }

    @Override
    //阻塞策略时用到的
    public void signalAllWhenBlocking() {
    }

    //线程 进行等待 而不是一直循环
    private int applyWaitMethod(final SequenceBarrier barrier, int counter)
            throws AlertException {
        barrier.checkAlert();
        if (0 == counter) {
            Thread.yield();
        } else {
            //否则返回一个--的序列
            --counter;
        }

        return counter;
    }
}
