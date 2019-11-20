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
        //dependentSequence.get()，获取的是依赖的最小的序列
        //如果要申请的序列大于所依赖的序列
        //这里是实现控制序列的先后执行顺序
        //在这里会始终等待其所依赖的序列完成
        while ((availableSequence = dependentSequence.get()) < sequence) {
            counter = applyWaitMethod(barrier, counter);
        }
        //这里返回的是依赖所完成的最小可获取的值
        //如果是batch处理，则事件发布到哪个，就获取哪个
        return availableSequence;
    }

    @Override
    //阻塞策略时用到的
    public void signalAllWhenBlocking() {
    }

    //线程 进行等待 而不是一直循环
    private int applyWaitMethod(final SequenceBarrier barrier, int counter)
            throws AlertException {
        //检查是否发出了警告
        barrier.checkAlert();
        //移交线程的执行权限
        if (0 == counter) {
            Thread.yield();
        } else {
            //否则返回一个--的序列
            --counter;
        }

        return counter;
    }
}
