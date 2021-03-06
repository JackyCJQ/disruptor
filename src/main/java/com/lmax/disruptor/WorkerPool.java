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

import com.lmax.disruptor.util.Util;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作池
 * WorkerPool contains a pool of {@link WorkProcessor}s that will consume sequences so jobs can be farmed out across a pool of workers.
 * Each of the {@link WorkProcessor}s manage and calls a {@link WorkHandler} to process the events.
 *
 * @param <T> event to be processed by a pool of workers
 */
public final class WorkerPool<T> {
    //原子类，默认是关闭的状态
    private final AtomicBoolean started = new AtomicBoolean(false);
    //初始化序列 工作开始的序列
    private final Sequence workSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    //对应的数据中心
    private final RingBuffer<T> ringBuffer;
    //处理器
    // WorkProcessors are created to wrap each of the provided WorkHandlers
    private final WorkProcessor<?>[] workProcessors;

    /**
     * Create a worker pool to enable an array of {@link WorkHandler}s to consume published sequences.
     * <p>
     * This option requires a pre-configured {@link RingBuffer} which must have {@link RingBuffer#addGatingSequences(Sequence...)}
     * called before the work pool is started.
     *
     * @param ringBuffer       of events to be consumed.
     * @param sequenceBarrier  on which the workers will depend.
     * @param exceptionHandler to callback when an error occurs which is not handled by the {@link WorkHandler}s.
     * @param workHandlers     to distribute the work load across.
     */
    @SafeVarargs
    public WorkerPool(
            final RingBuffer<T> ringBuffer,
            final SequenceBarrier sequenceBarrier,
            final ExceptionHandler<? super T> exceptionHandler,
            final WorkHandler<? super T>... workHandlers) {

        this.ringBuffer = ringBuffer;
        final int numWorkers = workHandlers.length;
        //对于每个处理器创建对应的WorkProcessor
        workProcessors = new WorkProcessor[numWorkers];

        for (int i = 0; i < numWorkers; i++) {
            workProcessors[i] = new WorkProcessor<>(
                    ringBuffer,
                    sequenceBarrier,
                    workHandlers[i],
                    exceptionHandler,
                    //每个消费者都是从-1序列开始执行
                    workSequence);
        }
    }

    /**
     * Construct a work pool with an internal {@link RingBuffer} for convenience.
     * <p>
     * This option does not require {@link RingBuffer#addGatingSequences(Sequence...)} to be called before the work pool is started.
     *
     * @param eventFactory     for filling the {@link RingBuffer}
     * @param exceptionHandler to callback when an error occurs which is not handled by the {@link WorkHandler}s.
     * @param workHandlers     to distribute the work load across.
     */
    @SafeVarargs
    public WorkerPool(
            final EventFactory<T> eventFactory,
            final ExceptionHandler<? super T> exceptionHandler,
            final WorkHandler<? super T>... workHandlers) {
        //默认创建多生产者的数据环
        ringBuffer = RingBuffer.createMultiProducer(eventFactory, 1024, new BlockingWaitStrategy());
        //根据多消费者 创建对应的 SequenceBarrier
        final SequenceBarrier barrier = ringBuffer.newBarrier();
        final int numWorkers = workHandlers.length;

        workProcessors = new WorkProcessor[numWorkers];
        for (int i = 0; i < numWorkers; i++) {
            workProcessors[i] = new WorkProcessor<>(
                    ringBuffer,
                    barrier,
                    workHandlers[i],
                    exceptionHandler,
                    //初始的时候都是原始序列
                    workSequence);
        }
        //多生产者 需要设置这个GatingSequences
        ringBuffer.addGatingSequences(getWorkerSequences());
    }

    /**
     * Get an array of {@link Sequence}s representing the progress of the workers.
     *
     * @return an array of {@link Sequence}s representing the progress of the workers.
     */
    public Sequence[] getWorkerSequences() {
        final Sequence[] sequences = new Sequence[workProcessors.length + 1];
        for (int i = 0, size = workProcessors.length; i < size; i++) {
            //获取没一个处理器 已经处理到的序列
            sequences[i] = workProcessors[i].getSequence();
        }
        //和当前所有的序列 要处理的序列值
        sequences[sequences.length - 1] = workSequence;

        return sequences;
    }

    /**
     * Start the worker pool processing events in sequence.
     *
     * @param executor providing threads for running the workers.
     * @return the {@link RingBuffer} used for the work queue.
     * @throws IllegalStateException if the pool has already been started and not halted yet
     */
    public RingBuffer<T> start(final Executor executor) {
        //开启工作池
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("WorkerPool has already been started and cannot be restarted until halted.");
        }
        //获取当前可消费事件的最小序列
        final long cursor = ringBuffer.getCursor();
        //设置工作开始的序列
        workSequence.set(cursor);
        //开始执行
        for (WorkProcessor<?> processor : workProcessors) {
            //把每个生产者已经处理的序列 设置为开始工作的初值
            processor.getSequence().set(cursor);
            //开始执行
            executor.execute(processor);
        }
        return ringBuffer;
    }

    /**
     * Wait for the {@link RingBuffer} to drain of published events then halt the workers.
     */
    public void drainAndHalt() {
        Sequence[] workerSequences = getWorkerSequences();
        //直到 每个处理器 要处理的序列都不小于当前可获取的序列时 停止执行
        while (ringBuffer.getCursor() > Util.getMinimumSequence(workerSequences)) {
            Thread.yield();
        }

        for (WorkProcessor<?> processor : workProcessors) {
            processor.halt();
        }
        //停止执行
        started.set(false);
    }

    /**
     * Halt all workers immediately at the end of their current cycle.
     */
    public void halt() {
        for (WorkProcessor<?> processor : workProcessors) {
            processor.halt();
        }

        started.set(false);
    }

    public boolean isRunning() {
        return started.get();
    }
}
