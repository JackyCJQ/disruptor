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

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventProcessor;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.EventTranslatorThreeArg;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.WorkHandler;
import com.lmax.disruptor.WorkerPool;
import com.lmax.disruptor.util.Util;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Disruptor为什么这么快？
 * 1。不使用锁，通过内存屏障和cas操作代替锁
 * 2。缓存基于数组而不是链表，用位运算代替求模。缓存的总长度总是2的n次方，这样可以用位运算i&(length-1)替代i%length
 * 3.去除伪共享。cpu的缓存一般是以缓存行为最小的单位，对应主存的一块相应大小的单元；当前缓存行大小一般是64字节，每个缓存行一次只能被一个
 * cpu核访问，如果一个缓存行被多个cpu访问，就会造成竞争，导致某个核必须等待其他核处理完了才能继续处理。去除伪共享就是确保cpu核访问某个缓存行时
 * 不会出现竞争
 * 4。预分配缓存对象，通过更新缓存里对象的属性而不是删除对象来减少垃圾回收。
 *
 * @param <T>
 */

public class Disruptor<T> {
    //对RingBuffer的引用
    private final RingBuffer<T> ringBuffer;
    //消费者事件处理器
    private final Executor executor;
    //消费者集合
    private final ConsumerRepository<T> consumerRepository = new ConsumerRepository<>();
    //Disruptor是否启动标示，只能启动一次
    private final AtomicBoolean started = new AtomicBoolean(false);
    //消费者异常处理器
    private ExceptionHandler<? super T> exceptionHandler = new ExceptionHandlerWrapper<>();

    /**
     * @param eventFactory   事件生产工厂
     * @param ringBufferSize 环的大小
     * @param executor       线程池
     */
    @Deprecated
    public Disruptor(final EventFactory<T> eventFactory, final int ringBufferSize, final Executor executor) {
        this(RingBuffer.createMultiProducer(eventFactory, ringBufferSize), executor);
    }

    /**
     * @param eventFactory
     * @param ringBufferSize
     * @param executor
     * @param producerType   生产者类型
     * @param waitStrategy   环满的时候 等待策略
     */
    @Deprecated
    public Disruptor(
            final EventFactory<T> eventFactory,
            final int ringBufferSize,
            final Executor executor,
            final ProducerType producerType,
            final WaitStrategy waitStrategy) {
        this(RingBuffer.create(producerType, eventFactory, ringBufferSize, waitStrategy), executor);
    }

    /**
     * @param eventFactory
     * @param ringBufferSize
     * @param threadFactory  线程创建工厂
     */
    public Disruptor(final EventFactory<T> eventFactory, final int ringBufferSize, final ThreadFactory threadFactory) {
        this(RingBuffer.createMultiProducer(eventFactory, ringBufferSize), new BasicExecutor(threadFactory));
    }

    /**
     * Create a new Disruptor.
     *
     * @param eventFactory   the factory to create events in the ring buffer.
     * @param ringBufferSize the size of the ring buffer, must be power of 2.
     * @param threadFactory  a {@link ThreadFactory} to create threads for processors.
     * @param producerType   the claim strategy to use for the ring buffer.
     * @param waitStrategy   the wait strategy to use for the ring buffer.
     */
    public Disruptor(
            final EventFactory<T> eventFactory,
            final int ringBufferSize,
            final ThreadFactory threadFactory,
            final ProducerType producerType,
            final WaitStrategy waitStrategy) {
        this(RingBuffer.create(producerType, eventFactory, ringBufferSize, waitStrategy),
                new BasicExecutor(threadFactory));
    }

    /**
     * 核心构造方法 ，这里就是一个ringbuffer和一个线程池就行了
     */
    private Disruptor(final RingBuffer<T> ringBuffer, final Executor executor) {
        this.ringBuffer = ringBuffer;
        this.executor = executor;
    }

    /**
     * 添加一个事件处理器
     *
     * @param handlers
     * @return
     */
    @SuppressWarnings("varargs")
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventHandler<? super T>... handlers) {
        //所有的事件处理器的初始化指针为 -1
        return createEventProcessors(new Sequence[0], handlers);
    }

    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventProcessorFactory<T>... eventProcessorFactories) {
        final Sequence[] barrierSequences = new Sequence[0];
        return createEventProcessors(barrierSequences, eventProcessorFactories);
    }

    public EventHandlerGroup<T> handleEventsWith(final EventProcessor... processors) {
        for (final EventProcessor processor : processors) {
            consumerRepository.add(processor);
        }

        final Sequence[] sequences = new Sequence[processors.length];
        for (int i = 0; i < processors.length; i++) {
            sequences[i] = processors[i].getSequence();
        }

        ringBuffer.addGatingSequences(sequences);

        return new EventHandlerGroup<>(this, consumerRepository, Util.getSequencesFor(processors));
    }


    @SafeVarargs
    @SuppressWarnings("varargs")
    public final EventHandlerGroup<T> handleEventsWithWorkerPool(final WorkHandler<T>... workHandlers) {
        return createWorkerPool(new Sequence[0], workHandlers);
    }

    public void handleExceptionsWith(final ExceptionHandler<? super T> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }


    @SuppressWarnings("unchecked")
    public void setDefaultExceptionHandler(final ExceptionHandler<? super T> exceptionHandler) {
        checkNotStarted();
        if (!(this.exceptionHandler instanceof ExceptionHandlerWrapper)) {
            throw new IllegalStateException("setDefaultExceptionHandler can not be used after handleExceptionsWith");
        }
        ((ExceptionHandlerWrapper<T>) this.exceptionHandler).switchTo(exceptionHandler);
    }


    public ExceptionHandlerSetting<T> handleExceptionsFor(final EventHandler<T> eventHandler) {
        return new ExceptionHandlerSetting<>(eventHandler, consumerRepository);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public final EventHandlerGroup<T> after(final EventHandler<T>... handlers) {
        final Sequence[] sequences = new Sequence[handlers.length];
        for (int i = 0, handlersLength = handlers.length; i < handlersLength; i++) {
            sequences[i] = consumerRepository.getSequenceFor(handlers[i]);
        }

        return new EventHandlerGroup<>(this, consumerRepository, sequences);
    }


    public EventHandlerGroup<T> after(final EventProcessor... processors) {
        for (final EventProcessor processor : processors) {
            consumerRepository.add(processor);
        }

        return new EventHandlerGroup<>(this, consumerRepository, Util.getSequencesFor(processors));
    }


    public void publishEvent(final EventTranslator<T> eventTranslator) {
        ringBuffer.publishEvent(eventTranslator);
    }


    public <A> void publishEvent(final EventTranslatorOneArg<T, A> eventTranslator, final A arg) {
        ringBuffer.publishEvent(eventTranslator, arg);
    }


    public <A> void publishEvents(final EventTranslatorOneArg<T, A> eventTranslator, final A[] arg) {
        ringBuffer.publishEvents(eventTranslator, arg);
    }

    public <A, B> void publishEvent(final EventTranslatorTwoArg<T, A, B> eventTranslator, final A arg0, final B arg1) {
        ringBuffer.publishEvent(eventTranslator, arg0, arg1);
    }

    public <A, B, C> void publishEvent(final EventTranslatorThreeArg<T, A, B, C> eventTranslator, final A arg0, final B arg1, final C arg2) {
        ringBuffer.publishEvent(eventTranslator, arg0, arg1, arg2);
    }

    //还是循环开启每一个线程
    public RingBuffer<T> start() {
        checkOnlyStartedOnce();
        for (final ConsumerInfo consumerInfo : consumerRepository) {
            consumerInfo.start(executor);
        }
        return ringBuffer;
    }

    //依次暂停
    public void halt() {
        for (final ConsumerInfo consumerInfo : consumerRepository) {
            consumerInfo.halt();
        }
    }

    //依次终止
    public void shutdown() {
        try {
            shutdown(-1, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            exceptionHandler.handleOnShutdownException(e);
        }
    }


    public void shutdown(final long timeout, final TimeUnit timeUnit) throws TimeoutException {
        final long timeOutAt = System.currentTimeMillis() + timeUnit.toMillis(timeout);
        while (hasBacklog()) {
            if (timeout >= 0 && System.currentTimeMillis() > timeOutAt) {
                throw TimeoutException.INSTANCE;
            }
            // Busy spin
        }
        halt();
    }


    public RingBuffer<T> getRingBuffer() {
        return ringBuffer;
    }

    public long getCursor() {
        return ringBuffer.getCursor();
    }

    public long getBufferSize() {
        return ringBuffer.getBufferSize();
    }


    public T get(final long sequence) {
        return ringBuffer.get(sequence);
    }


    public SequenceBarrier getBarrierFor(final EventHandler<T> handler) {
        return consumerRepository.getBarrierFor(handler);
    }

    public long getSequenceValueFor(final EventHandler<T> b1) {
        return consumerRepository.getSequenceFor(b1).get();
    }

    /**
     * 确认是否已经被全部消费
     */
    private boolean hasBacklog() {
        final long cursor = ringBuffer.getCursor();
        for (final Sequence consumer : consumerRepository.getLastSequenceInChain(false)) {
            if (cursor > consumer.get()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 给事件处理器添加对应的事件执行过程器
     *
     * @param barrierSequences
     * @param eventHandlers
     * @return
     */
    EventHandlerGroup<T> createEventProcessors(final Sequence[] barrierSequences, final EventHandler<? super T>[] eventHandlers) {
        //必须在disruptor启动之前添加
        checkNotStarted();
        //每个消费者都有自己要消费事件的序号Sequence
        final Sequence[] processorSequences = new Sequence[eventHandlers.length];
        //消费者通过SequenceBarrier等待可消费的事件,eventHandlers.length个处理器是并行结构，开始处理的序列的最小值是一样的
        final SequenceBarrier barrier = ringBuffer.newBarrier(barrierSequences);

        for (int i = 0, eventHandlersLength = eventHandlers.length; i < eventHandlersLength; i++) {
            final EventHandler<? super T> eventHandler = eventHandlers[i];
            //每个消费者都以BatchEventProcessor被调度
            final BatchEventProcessor<T> batchEventProcessor =
                    new BatchEventProcessor<>(ringBuffer, barrier, eventHandler);

            //在把错误处理器添加进去
            if (exceptionHandler != null) {
                batchEventProcessor.setExceptionHandler(exceptionHandler);
            }

            consumerRepository.add(batchEventProcessor, eventHandler, barrier);
            //初始化每个处理器的序列值
            processorSequences[i] = batchEventProcessor.getSequence();
        }
        //barrierSequences 都是相同的
        updateGatingSequencesForNextInChain(barrierSequences, processorSequences);

        return new EventHandlerGroup<>(this, consumerRepository, processorSequences);
    }

    private void updateGatingSequencesForNextInChain(final Sequence[] barrierSequences, final Sequence[] processorSequences) {
        if (processorSequences.length > 0) {
            //添加每个处理器的门槛序列值
            ringBuffer.addGatingSequences(processorSequences);
            for (final Sequence barrierSequence : barrierSequences) {
                ringBuffer.removeGatingSequence(barrierSequence);
            }
            consumerRepository.unMarkEventProcessorsAsEndOfChain(barrierSequences);
        }
    }

    EventHandlerGroup<T> createEventProcessors(
            final Sequence[] barrierSequences, final EventProcessorFactory<T>[] processorFactories) {
        final EventProcessor[] eventProcessors = new EventProcessor[processorFactories.length];
        for (int i = 0; i < processorFactories.length; i++) {
            eventProcessors[i] = processorFactories[i].createEventProcessor(ringBuffer, barrierSequences);
        }

        return handleEventsWith(eventProcessors);
    }

    EventHandlerGroup<T> createWorkerPool(
            final Sequence[] barrierSequences, final WorkHandler<? super T>[] workHandlers) {
        final SequenceBarrier sequenceBarrier = ringBuffer.newBarrier(barrierSequences);
        final WorkerPool<T> workerPool = new WorkerPool<>(ringBuffer, sequenceBarrier, exceptionHandler, workHandlers);


        consumerRepository.add(workerPool, sequenceBarrier);

        final Sequence[] workerSequences = workerPool.getWorkerSequences();

        updateGatingSequencesForNextInChain(barrierSequences, workerSequences);

        return new EventHandlerGroup<>(this, consumerRepository, workerSequences);
    }

    /**
     * 检查是否已经启动
     */
    private void checkNotStarted() {
        if (started.get()) {
            throw new IllegalStateException("All event handlers must be added before calling starts.");
        }
    }

    private void checkOnlyStartedOnce() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Disruptor.start() must only be called once.");
        }
    }

    @Override
    public String toString() {
        return "Disruptor{" +
                "ringBuffer=" + ringBuffer +
                ", started=" + started +
                ", executor=" + executor +
                '}';
    }
}
