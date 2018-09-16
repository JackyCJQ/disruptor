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


import sun.misc.Unsafe;

import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.Util;

//填充 还是为了去掉伪共享问题
abstract class RingBufferPad {
    protected long p1, p2, p3, p4, p5, p6, p7;
}

abstract class RingBufferFields<E> extends RingBufferPad {
    //填充
    private static final int BUFFER_PAD;
    //数组的起始地址
    private static final long REF_ARRAY_BASE;
    //2的位数
    private static final int REF_ELEMENT_SHIFT;
    //通过反射获取Unsafe
    private static final Unsafe UNSAFE = Util.getUnsafe();

    static {
        //如果是数组类型的 则会取内增增量大小
        final int scale = UNSAFE.arrayIndexScale(Object[].class);
        //如果增量大小为4个字节(int)
        if (4 == scale) {
            REF_ELEMENT_SHIFT = 2;
        }
        //如果增量大小为8个字节 （引用类型或是long,double）
        else if (8 == scale) {
            REF_ELEMENT_SHIFT = 3;
        } else {
            throw new IllegalStateException("Unknown pointer size");
        }
        //一般是64字节的填充，有的是128字节的
        BUFFER_PAD = 128 / scale;
        // Including the buffer pad in the array base offset
        //数组的起始地址+128字节后的地址？？ 为什么要填充128个字节呢？
        REF_ARRAY_BASE = UNSAFE.arrayBaseOffset(Object[].class) + (BUFFER_PAD << REF_ELEMENT_SHIFT);
    }

    private final long indexMask;
    private final Object[] entries;
    //缓冲区大小
    protected final int bufferSize;
    //缓存管理的组件
    protected final Sequencer sequencer;

    RingBufferFields(EventFactory<E> eventFactory, Sequencer sequencer) {
        this.sequencer = sequencer;
        this.bufferSize = sequencer.getBufferSize();

        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must not be less than 1");
        }
        //必须是2的n次方
        if (Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }
        //最后一个位置
        this.indexMask = bufferSize - 1;
        //填充数据，
        this.entries = new Object[sequencer.getBufferSize() + 2 * BUFFER_PAD];
        fill(eventFactory);
    }

    /**
     * 填充数据
     *
     * @param eventFactory
     */
    private void fill(EventFactory<E> eventFactory) {
        //每一个环处 填充一个
        for (int i = 0; i < bufferSize; i++) {
            //从事件生产工厂获取一个实例
            entries[BUFFER_PAD + i] = eventFactory.newInstance();
        }
    }

    @SuppressWarnings("unchecked")
    protected final E elementAt(long sequence) {
        //这里就是ringbuffer取   2的n次方的原因  可以直接与操作获取对应的内存中的地址
        return (E) UNSAFE.getObject(entries, REF_ARRAY_BASE + ((sequence & indexMask) << REF_ELEMENT_SHIFT));
    }
}


public final class RingBuffer<E> extends RingBufferFields<E> implements Cursored, EventSequencer<E>, EventSink<E> {
    //默认就是 -1
    public static final long INITIAL_CURSOR_VALUE = Sequence.INITIAL_VALUE;
    protected long p1, p2, p3, p4, p5, p6, p7;


    RingBuffer(
            EventFactory<E> eventFactory,
            Sequencer sequencer) {
        super(eventFactory, sequencer);
    }

    /**
     * 创建对应的RingBuffer
     *
     * @param factory
     * @param bufferSize
     * @param waitStrategy
     * @param <E>
     * @return
     */
    public static <E> RingBuffer<E> createMultiProducer(
            EventFactory<E> factory,
            int bufferSize,
            WaitStrategy waitStrategy) {
        //多生产者序列
        MultiProducerSequencer sequencer = new MultiProducerSequencer(bufferSize, waitStrategy);

        return new RingBuffer<E>(factory, sequencer);
    }

    /**
     * @param factory    事件生产工厂
     * @param bufferSize 环的大小
     * @param <E>
     * @return
     */
    public static <E> RingBuffer<E> createMultiProducer(EventFactory<E> factory, int bufferSize) {
        //默认是阻塞的策略
        return createMultiProducer(factory, bufferSize, new BlockingWaitStrategy());
    }

    /**
     * 创建单个生产者
     *
     * @param factory
     * @param bufferSize
     * @param waitStrategy
     * @param <E>
     * @return
     */
    public static <E> RingBuffer<E> createSingleProducer(EventFactory<E> factory, int bufferSize,
                                                         WaitStrategy waitStrategy) {
        SingleProducerSequencer sequencer = new SingleProducerSequencer(bufferSize, waitStrategy);

        return new RingBuffer<E>(factory, sequencer);
    }


    public static <E> RingBuffer<E> createSingleProducer(EventFactory<E> factory, int bufferSize) {
        return createSingleProducer(factory, bufferSize, new BlockingWaitStrategy());
    }

    /**
     * @param producerType 生产者类型
     * @param factory      事件工厂
     * @param bufferSize   大小
     * @param waitStrategy 等待策略
     * @param <E>
     * @return
     */
    public static <E> RingBuffer<E> create(ProducerType producerType, EventFactory<E> factory,
                                           int bufferSize, WaitStrategy waitStrategy) {
        switch (producerType) {
            //对应单个生产者
            case SINGLE:
                return createSingleProducer(factory, bufferSize, waitStrategy);
            case MULTI:
                return createMultiProducer(factory, bufferSize, waitStrategy);
            default:
                throw new IllegalStateException(producerType.toString());
        }
    }


    @Override
    public E get(long sequence) {
        return elementAt(sequence);
    }

    @Override
    public long next() {
        return sequencer.next();
    }


    @Override
    public long next(int n) {
        return sequencer.next(n);
    }

    @Override
    public long tryNext() throws InsufficientCapacityException {
        return sequencer.tryNext();
    }

    @Override
    public long tryNext(int n) throws InsufficientCapacityException {
        return sequencer.tryNext(n);
    }


    @Deprecated
    public void resetTo(long sequence) {
        sequencer.claim(sequence);
        sequencer.publish(sequence);
    }


    public E claimAndGetPreallocated(long sequence) {
        sequencer.claim(sequence);
        return get(sequence);
    }

    @Deprecated
    public boolean isPublished(long sequence) {
        return sequencer.isAvailable(sequence);
    }


    public void addGatingSequences(Sequence... gatingSequences) {
        sequencer.addGatingSequences(gatingSequences);
    }


    public long getMinimumGatingSequence() {
        return sequencer.getMinimumSequence();
    }


    public boolean removeGatingSequence(Sequence sequence) {
        return sequencer.removeGatingSequence(sequence);
    }


    public SequenceBarrier newBarrier(Sequence... sequencesToTrack) {
        return sequencer.newBarrier(sequencesToTrack);
    }

    /**
     * Creates an event poller for this ring buffer gated on the supplied sequences.
     *
     * @param gatingSequences to be gated on.
     * @return A poller that will gate on this ring buffer and the supplied sequences.
     */
    public EventPoller<E> newPoller(Sequence... gatingSequences) {
        return sequencer.newPoller(this, gatingSequences);
    }

    /**
     * Get the current cursor value for the ring buffer.  The actual value received
     * will depend on the type of {@link Sequencer} that is being used.
     *
     * @see MultiProducerSequencer
     * @see SingleProducerSequencer
     */
    @Override
    public long getCursor() {
        return sequencer.getCursor();
    }

    /**
     * The size of the buffer.
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * Given specified <tt>requiredCapacity</tt> determines if that amount of space
     * is available.  Note, you can not assume that if this method returns <tt>true</tt>
     * that a call to {@link RingBuffer#next()} will not block.  Especially true if this
     * ring buffer is set up to handle multiple producers.
     *
     * @param requiredCapacity The capacity to check for.
     * @return <tt>true</tt> If the specified <tt>requiredCapacity</tt> is available
     * <tt>false</tt> if not.
     */
    public boolean hasAvailableCapacity(int requiredCapacity) {
        return sequencer.hasAvailableCapacity(requiredCapacity);
    }


    /**
     * @see com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslator)
     */
    @Override
    public void publishEvent(EventTranslator<E> translator) {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslator)
     */
    @Override
    public boolean tryPublishEvent(EventTranslator<E> translator) {
        try {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorOneArg, Object)
     * com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorOneArg, A)
     */
    @Override
    public <A> void publishEvent(EventTranslatorOneArg<E, A> translator, A arg0) {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorOneArg, Object)
     * com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorOneArg, A)
     */
    @Override
    public <A> boolean tryPublishEvent(EventTranslatorOneArg<E, A> translator, A arg0) {
        try {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorTwoArg, Object, Object)
     * com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorTwoArg, A, B)
     */
    @Override
    public <A, B> void publishEvent(EventTranslatorTwoArg<E, A, B> translator, A arg0, B arg1) {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0, arg1);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorTwoArg, Object, Object)
     * com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorTwoArg, A, B)
     */
    @Override
    public <A, B> boolean tryPublishEvent(EventTranslatorTwoArg<E, A, B> translator, A arg0, B arg1) {
        try {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0, arg1);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorThreeArg, Object, Object, Object)
     * com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorThreeArg, A, B, C)
     */
    @Override
    public <A, B, C> void publishEvent(EventTranslatorThreeArg<E, A, B, C> translator, A arg0, B arg1, C arg2) {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0, arg1, arg2);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorThreeArg, Object, Object, Object)
     * com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorThreeArg, A, B, C)
     */
    @Override
    public <A, B, C> boolean tryPublishEvent(EventTranslatorThreeArg<E, A, B, C> translator, A arg0, B arg1, C arg2) {
        try {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0, arg1, arg2);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvent(com.lmax.disruptor.EventTranslatorVararg, java.lang.Object...)
     */
    @Override
    public void publishEvent(EventTranslatorVararg<E> translator, Object... args) {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, args);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvent(com.lmax.disruptor.EventTranslatorVararg, java.lang.Object...)
     */
    @Override
    public boolean tryPublishEvent(EventTranslatorVararg<E> translator, Object... args) {
        try {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, args);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }


    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslator[])
     */
    @Override
    public void publishEvents(EventTranslator<E>[] translators) {
        publishEvents(translators, 0, translators.length);
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslator[], int, int)
     */
    @Override
    public void publishEvents(EventTranslator<E>[] translators, int batchStartsAt, int batchSize) {
        checkBounds(translators, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translators, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslator[])
     */
    @Override
    public boolean tryPublishEvents(EventTranslator<E>[] translators) {
        return tryPublishEvents(translators, 0, translators.length);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslator[], int, int)
     */
    @Override
    public boolean tryPublishEvents(EventTranslator<E>[] translators, int batchStartsAt, int batchSize) {
        checkBounds(translators, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translators, batchStartsAt, batchSize, finalSequence);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorOneArg, Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorOneArg, A[])
     */
    @Override
    public <A> void publishEvents(EventTranslatorOneArg<E, A> translator, A[] arg0) {
        publishEvents(translator, 0, arg0.length, arg0);
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorOneArg, int, int, Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorOneArg, int, int, A[])
     */
    @Override
    public <A> void publishEvents(EventTranslatorOneArg<E, A> translator, int batchStartsAt, int batchSize, A[] arg0) {
        checkBounds(arg0, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorOneArg, Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorOneArg, A[])
     */
    @Override
    public <A> boolean tryPublishEvents(EventTranslatorOneArg<E, A> translator, A[] arg0) {
        return tryPublishEvents(translator, 0, arg0.length, arg0);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorOneArg, int, int, Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorOneArg, int, int, A[])
     */
    @Override
    public <A> boolean tryPublishEvents(
            EventTranslatorOneArg<E, A> translator, int batchStartsAt, int batchSize, A[] arg0) {
        checkBounds(arg0, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, batchStartsAt, batchSize, finalSequence);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorTwoArg, Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorTwoArg, A[], B[])
     */
    @Override
    public <A, B> void publishEvents(EventTranslatorTwoArg<E, A, B> translator, A[] arg0, B[] arg1) {
        publishEvents(translator, 0, arg0.length, arg0, arg1);
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorTwoArg, int, int, Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorTwoArg, int, int, A[], B[])
     */
    @Override
    public <A, B> void publishEvents(
            EventTranslatorTwoArg<E, A, B> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1) {
        checkBounds(arg0, arg1, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, arg1, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorTwoArg, Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorTwoArg, A[], B[])
     */
    @Override
    public <A, B> boolean tryPublishEvents(EventTranslatorTwoArg<E, A, B> translator, A[] arg0, B[] arg1) {
        return tryPublishEvents(translator, 0, arg0.length, arg0, arg1);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorTwoArg, int, int, Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorTwoArg, int, int, A[], B[])
     */
    @Override
    public <A, B> boolean tryPublishEvents(
            EventTranslatorTwoArg<E, A, B> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1) {
        checkBounds(arg0, arg1, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, arg1, batchStartsAt, batchSize, finalSequence);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorThreeArg, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorThreeArg, A[], B[], C[])
     */
    @Override
    public <A, B, C> void publishEvents(EventTranslatorThreeArg<E, A, B, C> translator, A[] arg0, B[] arg1, C[] arg2) {
        publishEvents(translator, 0, arg0.length, arg0, arg1, arg2);
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorThreeArg, int, int, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorThreeArg, int, int, A[], B[], C[])
     */
    @Override
    public <A, B, C> void publishEvents(
            EventTranslatorThreeArg<E, A, B, C> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1, C[] arg2) {
        checkBounds(arg0, arg1, arg2, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, arg1, arg2, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorThreeArg, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorThreeArg, A[], B[], C[])
     */
    @Override
    public <A, B, C> boolean tryPublishEvents(
            EventTranslatorThreeArg<E, A, B, C> translator, A[] arg0, B[] arg1, C[] arg2) {
        return tryPublishEvents(translator, 0, arg0.length, arg0, arg1, arg2);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorThreeArg, int, int, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorThreeArg, int, int, A[], B[], C[])
     */
    @Override
    public <A, B, C> boolean tryPublishEvents(
            EventTranslatorThreeArg<E, A, B, C> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1, C[] arg2) {
        checkBounds(arg0, arg1, arg2, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, arg1, arg2, batchStartsAt, batchSize, finalSequence);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorVararg, java.lang.Object[][])
     */
    @Override
    public void publishEvents(EventTranslatorVararg<E> translator, Object[]... args) {
        publishEvents(translator, 0, args.length, args);
    }

    /**
     * @see com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorVararg, int, int, java.lang.Object[][])
     */
    @Override
    public void publishEvents(EventTranslatorVararg<E> translator, int batchStartsAt, int batchSize, Object[]... args) {
        checkBounds(batchStartsAt, batchSize, args);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, batchStartsAt, batchSize, finalSequence, args);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorVararg, java.lang.Object[][])
     */
    @Override
    public boolean tryPublishEvents(EventTranslatorVararg<E> translator, Object[]... args) {
        return tryPublishEvents(translator, 0, args.length, args);
    }

    /**
     * @see com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorVararg, int, int, java.lang.Object[][])
     */
    @Override
    public boolean tryPublishEvents(
            EventTranslatorVararg<E> translator, int batchStartsAt, int batchSize, Object[]... args) {
        checkBounds(args, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, batchStartsAt, batchSize, finalSequence, args);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * Publish the specified sequence.  This action marks this particular
     * message as being available to be read.
     *
     * @param sequence the sequence to publish.
     */
    @Override
    public void publish(long sequence) {
        sequencer.publish(sequence);
    }

    /**
     * Publish the specified sequences.  This action marks these particular
     * messages as being available to be read.
     *
     * @param lo the lowest sequence number to be published
     * @param hi the highest sequence number to be published
     * @see Sequencer#next(int)
     */
    @Override
    public void publish(long lo, long hi) {
        sequencer.publish(lo, hi);
    }

    /**
     * Get the remaining capacity for this ringBuffer.
     *
     * @return The number of slots remaining.
     */
    public long remainingCapacity() {
        return sequencer.remainingCapacity();
    }

    private void checkBounds(final EventTranslator<E>[] translators, final int batchStartsAt, final int batchSize) {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(translators, batchStartsAt, batchSize);
    }

    private void checkBatchSizing(int batchStartsAt, int batchSize) {
        if (batchStartsAt < 0 || batchSize < 0) {
            throw new IllegalArgumentException("Both batchStartsAt and batchSize must be positive but got: batchStartsAt " + batchStartsAt + " and batchSize " + batchSize);
        } else if (batchSize > bufferSize) {
            throw new IllegalArgumentException("The ring buffer cannot accommodate " + batchSize + " it only has space for " + bufferSize + " entities.");
        }
    }

    private <A> void checkBounds(final A[] arg0, final int batchStartsAt, final int batchSize) {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
    }

    private <A, B> void checkBounds(final A[] arg0, final B[] arg1, final int batchStartsAt, final int batchSize) {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
        batchOverRuns(arg1, batchStartsAt, batchSize);
    }

    private <A, B, C> void checkBounds(
            final A[] arg0, final B[] arg1, final C[] arg2, final int batchStartsAt, final int batchSize) {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
        batchOverRuns(arg1, batchStartsAt, batchSize);
        batchOverRuns(arg2, batchStartsAt, batchSize);
    }

    private void checkBounds(final int batchStartsAt, final int batchSize, final Object[][] args) {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(args, batchStartsAt, batchSize);
    }

    private <A> void batchOverRuns(final A[] arg0, final int batchStartsAt, final int batchSize) {
        if (batchStartsAt + batchSize > arg0.length) {
            throw new IllegalArgumentException(
                    "A batchSize of: " + batchSize +
                            " with batchStatsAt of: " + batchStartsAt +
                            " will overrun the available number of arguments: " + (arg0.length - batchStartsAt));
        }
    }

    private void translateAndPublish(EventTranslator<E> translator, long sequence) {
        try {
            translator.translateTo(get(sequence), sequence);
        } finally {
            sequencer.publish(sequence);
        }
    }

    private <A> void translateAndPublish(EventTranslatorOneArg<E, A> translator, long sequence, A arg0) {
        try {
            translator.translateTo(get(sequence), sequence, arg0);
        } finally {
            sequencer.publish(sequence);
        }
    }

    private <A, B> void translateAndPublish(EventTranslatorTwoArg<E, A, B> translator, long sequence, A arg0, B arg1) {
        try {
            translator.translateTo(get(sequence), sequence, arg0, arg1);
        } finally {
            sequencer.publish(sequence);
        }
    }

    private <A, B, C> void translateAndPublish(
            EventTranslatorThreeArg<E, A, B, C> translator, long sequence,
            A arg0, B arg1, C arg2) {
        try {
            translator.translateTo(get(sequence), sequence, arg0, arg1, arg2);
        } finally {
            sequencer.publish(sequence);
        }
    }

    private void translateAndPublish(EventTranslatorVararg<E> translator, long sequence, Object... args) {
        try {
            translator.translateTo(get(sequence), sequence, args);
        } finally {
            sequencer.publish(sequence);
        }
    }

    private void translateAndPublishBatch(
            final EventTranslator<E>[] translators, int batchStartsAt,
            final int batchSize, final long finalSequence) {
        final long initialSequence = finalSequence - (batchSize - 1);
        try {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++) {
                final EventTranslator<E> translator = translators[i];
                translator.translateTo(get(sequence), sequence++);
            }
        } finally {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A> void translateAndPublishBatch(
            final EventTranslatorOneArg<E, A> translator, final A[] arg0,
            int batchStartsAt, final int batchSize, final long finalSequence) {
        final long initialSequence = finalSequence - (batchSize - 1);
        try {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++) {
                translator.translateTo(get(sequence), sequence++, arg0[i]);
            }
        } finally {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A, B> void translateAndPublishBatch(
            final EventTranslatorTwoArg<E, A, B> translator, final A[] arg0,
            final B[] arg1, int batchStartsAt, int batchSize,
            final long finalSequence) {
        final long initialSequence = finalSequence - (batchSize - 1);
        try {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++) {
                translator.translateTo(get(sequence), sequence++, arg0[i], arg1[i]);
            }
        } finally {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A, B, C> void translateAndPublishBatch(
            final EventTranslatorThreeArg<E, A, B, C> translator,
            final A[] arg0, final B[] arg1, final C[] arg2, int batchStartsAt,
            final int batchSize, final long finalSequence) {
        final long initialSequence = finalSequence - (batchSize - 1);
        try {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++) {
                translator.translateTo(get(sequence), sequence++, arg0[i], arg1[i], arg2[i]);
            }
        } finally {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private void translateAndPublishBatch(
            final EventTranslatorVararg<E> translator, int batchStartsAt,
            final int batchSize, final long finalSequence, final Object[][] args) {
        final long initialSequence = finalSequence - (batchSize - 1);
        try {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++) {
                translator.translateTo(get(sequence), sequence++, args[i]);
            }
        } finally {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    @Override
    public String toString() {
        return "RingBuffer{" +
                "bufferSize=" + bufferSize +
                ", sequencer=" + sequencer +
                "}";
    }
}
