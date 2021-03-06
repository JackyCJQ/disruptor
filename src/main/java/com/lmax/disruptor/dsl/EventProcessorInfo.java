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
package com.lmax.disruptor.dsl;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventProcessor;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;

import java.util.concurrent.Executor;

/**
 * <p>Tracks the event processor instance, the event handler instance, and sequence barrier which the stage is attached to.</p>
 *
 * @param <T> the type of the configured {@link EventHandler}
 */
class EventProcessorInfo<T> implements ConsumerInfo {
    //跟踪的事件处理过程器
    private final EventProcessor eventprocessor;
    //对应的事件处理器
    private final EventHandler<? super T> handler;
    //与事件处理过程器交互的序列屏障
    private final SequenceBarrier barrier;
    //是否是处于结束 默认是结束的
    private boolean endOfChain = true;

    //传入对应的数据
    EventProcessorInfo(final EventProcessor eventprocessor, final EventHandler<? super T> handler, final SequenceBarrier barrier) {
        this.eventprocessor = eventprocessor;
        this.handler = handler;
        this.barrier = barrier;
    }

    public EventProcessor getEventProcessor() {
        return eventprocessor;
    }

    //得到事件处理器处理过程中对应的序列
    @Override
    public Sequence[] getSequences() {
        return new Sequence[]{eventprocessor.getSequence()};
    }

    public EventHandler<? super T> getHandler() {
        return handler;
    }

    @Override
    public SequenceBarrier getBarrier() {
        return barrier;
    }

    @Override
    public boolean isEndOfChain() {
        return endOfChain;
    }

    @Override
    public void start(final Executor executor) {
        executor.execute(eventprocessor);
    }

    //通过事件处理过程器 来停止执行
    @Override
    public void halt() {
        eventprocessor.halt();
    }

    /**
     * 标记开始使用
     */
    @Override
    public void markAsUsedInBarrier() {
        endOfChain = false;
    }

    //通过事件处理过程器 来判断是否还在继续执行
    @Override
    public boolean isRunning() {
        return eventprocessor.isRunning();
    }
}
