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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每个消费者 对应一个 记录处理过程
 * <p>A {@link WorkProcessor} wraps a single {@link WorkHandler}, effectively consuming the sequence
 * and ensuring appropriate barriers.</p>
 * <p>
 * <p>Generally, this will be used as part of a {@link WorkerPool}.</p>
 *
 * @param <T> event implementation storing the details for the work to processed.
 */
public final class WorkProcessor<T> implements EventProcessor {
    //是否开始
    private final AtomicBoolean running = new AtomicBoolean(false);
    //当前执行器对应的序列
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    //对应的数据环
    private final RingBuffer<T> ringBuffer;
    //还有序列屏障
    private final SequenceBarrier sequenceBarrier;
    //真正消费者执行消费的操作
    private final WorkHandler<? super T> workHandler;
    //异常处理器
    private final ExceptionHandler<? super T> exceptionHandler;
    //开始工作的序列
    private final Sequence workSequence;

    //事件结束时 设置序列为一个最大值
    private final EventReleaser eventReleaser = new EventReleaser() {
        @Override
        public void release() {
            sequence.set(Long.MAX_VALUE);
        }
    };
    //超时处理器
    private final TimeoutHandler timeoutHandler;

    /**
     * Construct a {@link WorkProcessor}.
     *
     * @param ringBuffer       to which events are published.
     * @param sequenceBarrier  on which it is waiting.
     * @param workHandler      is the delegate to which events are dispatched.
     * @param exceptionHandler to be called back when an error occurs
     * @param workSequence     from which to claim the next event to be worked on.  It should always be initialised
     *                         as {@link Sequencer#INITIAL_CURSOR_VALUE}
     */
    public WorkProcessor(
            final RingBuffer<T> ringBuffer,
            final SequenceBarrier sequenceBarrier,
            final WorkHandler<? super T> workHandler,
            final ExceptionHandler<? super T> exceptionHandler,
            final Sequence workSequence) {
        this.ringBuffer = ringBuffer;
        this.sequenceBarrier = sequenceBarrier;
        this.workHandler = workHandler;
        this.exceptionHandler = exceptionHandler;
        this.workSequence = workSequence;

        //处理器是否还继承了其他事件
        if (this.workHandler instanceof EventReleaseAware) {
            ((EventReleaseAware) this.workHandler).setEventReleaser(eventReleaser);
        }
        timeoutHandler = (workHandler instanceof TimeoutHandler) ? (TimeoutHandler) workHandler : null;
    }


    @Override
    public Sequence getSequence() {
        return sequence;
    }

    @Override
    public void halt() {
        running.set(false);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * It is ok to have another thread re-run this method after a halt().
     *
     * @throws IllegalStateException if this processor is already running
     */
    @Override
    public void run() {
        //只能开启一次
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Thread is already running");
        }
        //开始执行的时候清除标识位
        sequenceBarrier.clearAlert();
        //通知开始执行
        notifyStart();
        //开始处理序列
        boolean processedSequence = true;
        //默认是一个最小的值
        long cachedAvailableSequence = Long.MIN_VALUE;
        //获取当前对应的序列值
        long nextSequence = sequence.get();

        T event = null;
        //单个消费者
        while (true) {
            try {
                // if previous sequence was processed - fetch the next sequence and set
                // that we have successfully processed the previous sequence
                // typically, this will be true
                // this prevents the sequence getting too far forward if an exception
                // is thrown from the WorkHandler
                //如果抛出异常了 说明这个事件没有处理 需要下次在次处理
                if (processedSequence) {
                    processedSequence = false;
                    //cas获取一个序列
                    do {
                        //序列值+1
                        nextSequence = workSequence.get() + 1L;
                        //当前序列值
                        sequence.set(nextSequence - 1L);
                    }
                    while (!workSequence.compareAndSet(nextSequence - 1L, nextSequence));
                }
                //如果下一个序列在可获取的序列之前 则可以获取
                if (cachedAvailableSequence >= nextSequence) {
                    //获取对应序列的事件
                    event = ringBuffer.get(nextSequence);
                    //事件处理器开始处理这个事件
                    workHandler.onEvent(event);
                    processedSequence = true;
                } else {
                    //看能否获取到这个序列的数据
                    cachedAvailableSequence = sequenceBarrier.waitFor(nextSequence);
                }
            } catch (final TimeoutException e) {
                //如果超时了就通知超时
                notifyTimeout(sequence.get());
            } catch (final AlertException ex) {
                if (!running.get()) {
                    break;
                }
            } catch (final Throwable ex) {
                // handle, mark as processed, unless the exception handler threw an exception
                //发生异常 就调用异常处理器
                exceptionHandler.handleEventException(ex, nextSequence, event);
                processedSequence = true;
            }
        }

        //通知结束
        notifyShutdown();

        running.set(false);
    }
    //超时处理
    private void notifyTimeout(final long availableSequence) {
        try {
            if (timeoutHandler != null) {
                timeoutHandler.onTimeout(availableSequence);
            }
        } catch (Throwable e) {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    //如果处理器继承了LifecycleAware接口 则通知开始执行了
    private void notifyStart() {
        if (workHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) workHandler).onStart();
            } catch (final Throwable ex) {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    //如果执行器继承了LifecycleAware 在结束的时候 则通知结束了
    private void notifyShutdown() {
        if (workHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) workHandler).onShutdown();
            } catch (final Throwable ex) {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}
