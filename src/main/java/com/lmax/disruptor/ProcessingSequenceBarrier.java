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
 * 全局只有这一个实现
 * 消费者处理过程中的序列屏障
 * <p>
 * 最简单的就是生产者和消费者分别在不同的线程中进行 通过cursorSequence 联系两者之间的关系
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
    //当前可获取事件的最小的序列值 这个是与生产者序列共用的 只要生产者发布事件 这里就会感知到
    private final Sequence cursorSequence;
    //生产者直接交互的对象
    private final Sequencer sequencer;

    //从外面获取到对应的数据
    ProcessingSequenceBarrier(
            final Sequencer sequencer,
            final WaitStrategy waitStrategy,
            final Sequence cursorSequence,
            final Sequence[] dependentSequences) {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        //与生产者共用的
        this.cursorSequence = cursorSequence;
        //多个处理器之间没有先后关系 所以依赖的序列设置为其本身 每次自身执行完就可以了
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
    public long waitFor(final long sequence) throws AlertException, InterruptedException, TimeoutException {
        //如果停止的话 这里会抛出一个异常 消费者获取到这个异常 就可以终止等待
        checkAlert();
        //通过等待策略里面来获取可得到的序列
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);
        //对于有些waitStrategy 可能会产生这种情况
        if (availableSequence < sequence) {
            return availableSequence;
        }
        //就是返回可获取到的最大的序列  默认就是返回availableSequence
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    //获取可以消费的序列
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
        //waitStrategy如果是阻塞的策略则进行通知
        waitStrategy.signalAllWhenBlocking();
    }

    //清除警告
    @Override
    public void clearAlert() {
        alerted = false;
    }

    //抛出异常供消费者感知
    @Override
    public void checkAlert() throws AlertException {
        if (alerted) {
            throw AlertException.INSTANCE;
        }
    }
}