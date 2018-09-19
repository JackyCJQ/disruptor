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

/**
 * 内存屏障的作用，1。保证执行顺序 2。强制刷新一次缓存
 * volatile 会在写操作之后 插入一个缓存屏障
 * <p>
 * 生产者用于访问缓存的控制器，它持有消费者序号的引用；新事件发布后通过waitStratery通知正在等待的SequenceBarrier.这是Disruptor真正的核心。
 * 实现了这个接口的两种生产者（单生产者和多生产者）均实现了所有的并发算法，为了在生产者和消费者之间进行准确快速的数据传递。
 * <p>
 * Coordinates claiming sequences for access to a data structure while tracking dependent {@link Sequence}s
 */
public interface Sequencer extends Cursored, Sequenced {
    /**
     * 初始化的指针，当前指向的位置
     */
    long INITIAL_CURSOR_VALUE = -1L;

    void claim(long sequence);

    boolean isAvailable(long sequence);

    void addGatingSequences(Sequence... gatingSequences);

    boolean removeGatingSequence(Sequence sequence);

    SequenceBarrier newBarrier(Sequence... sequencesToTrack);

    long getMinimumSequence();

    long getHighestPublishedSequence(long nextSequence, long availableSequence);

    <T> EventPoller<T> newPoller(DataProvider<T> provider, Sequence... gatingSequences);
}