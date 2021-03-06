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
 * 当没有可消费的时间时，根据特定的实现进行等待，有可消费事件时返回可消费事件的序号；有新事件发布时通知等待的
 * SequenceBarrier。它决定了一个消费者如何等待生产者将event置入disruptor
 */
public interface WaitStrategy {

    //获取可消费的最大的序列
    long waitFor(long sequence, Sequence cursor, Sequence dependentSequence, SequenceBarrier barrier)
            throws AlertException, InterruptedException, TimeoutException;

    /**
     * 当阻塞的时候通知所有的生产者进行阻塞
     */
    void signalAllWhenBlocking();
}
