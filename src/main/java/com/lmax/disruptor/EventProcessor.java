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
 * 事件处理器，是可执行单元。运行在executor里；
 * 他会不断地通过SequencerBarrier获取可消费事件，当有可消费事件时，调用用户提供的Eventhandler实现处理事件，
 * <p>
 * <p>
 * An EventProcessor needs to be an implementation of a runnable that will poll for events from the {@link RingBuffer}
 * using the appropriate wait strategy.  It is unlikely that you will need to implement this interface yourself.
 * Look at using the {@link EventHandler} interface along with the pre-supplied BatchEventProcessor in the first
 * instance.
 * <p>
 * An EventProcessor will generally be associated with a Thread for execution.
 */
public interface EventProcessor extends Runnable {
    /**
     * Get a reference to the {@link Sequence} being used by this {@link EventProcessor}.
     * 获取当前序列
     *
     * @return reference to the {@link Sequence} for this {@link EventProcessor}
     */
    Sequence getSequence();

    /**
     * 终止当前执行过程
     * Signal that this EventProcessor should stop when it has finished consuming at the next clean break.
     * It will call {@link SequenceBarrier#alert()} to notify the thread to check status.
     */
    void halt();

    /**
     * 是否是在运行过程中
     *
     * @return
     */
    boolean isRunning();
}
