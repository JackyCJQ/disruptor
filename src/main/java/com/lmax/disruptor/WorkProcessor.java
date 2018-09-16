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
    //初始化的序列
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final RingBuffer<T> ringBuffer;
    private final SequenceBarrier sequenceBarrier;

    private final WorkHandler<? super T> workHandler;
    private final ExceptionHandler<? super T> exceptionHandler;
    //开始工作的序列
    private final Sequence workSequence;

    //设置sequence
    private final EventReleaser eventReleaser = new EventReleaser() {
        @Override
        public void release() {
            sequence.set(Long.MAX_VALUE);
        }
    };
    //超时处理
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
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Thread is already running");
        }
        sequenceBarrier.clearAlert();
        //通知开始执行
        notifyStart();
        //开始处理序列
        boolean processedSequence = true;
        //缓存的序号
        long cachedAvailableSequence = Long.MIN_VALUE;
        //获取这个序列中的序列号值
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
                if (processedSequence) {
                    processedSequence = false;
                    //cas并发获取一个序列
                    do {
                        //worksequence应该是共享的
                        nextSequence = workSequence.get() + 1L;
                        sequence.set(nextSequence - 1L);
                    }
                    while (!workSequence.compareAndSet(nextSequence - 1L, nextSequence));
                }
                //序列没有终止
                if (cachedAvailableSequence >= nextSequence) {
                    //获取对应序列的事件
                    event = ringBuffer.get(nextSequence);
                    workHandler.onEvent(event);
                    processedSequence = true;
                } else {
                    //如果没有缓存的 就需要进行等待
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

    private void notifyTimeout(final long availableSequence) {
        try {
            if (timeoutHandler != null) {
                timeoutHandler.onTimeout(availableSequence);
            }
        } catch (Throwable e) {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    private void notifyStart() {
        if (workHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) workHandler).onStart();
            } catch (final Throwable ex) {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

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
