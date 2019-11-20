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

import java.util.concurrent.atomic.AtomicInteger;


/**
 * 消费者执行器
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T> implements EventProcessor {
    /**
     * 默认的状态初始值
     * 一共是三种状态 IDEL HALTED RUNNING
     */
    private static final int IDLE = 0;
    //  1
    private static final int HALTED = IDLE + 1;
    //  2
    private static final int RUNNING = HALTED + 1;
    // 默认为0
    private final AtomicInteger running = new AtomicInteger(IDLE);
    // 错误处理器
    private ExceptionHandler<? super T> exceptionHandler = new FatalExceptionHandler();

    //ringbuffer继承这个接口 在执行器执行过程中需要从ringbuffer中获取数据
    private final DataProvider<T> dataProvider;
    //消费者不直接和ringbuffer交互，而是和SequenceBarrier交互，减少对ringbuffer的冲击
    private final SequenceBarrier sequenceBarrier;
    //具体的事件处理
    private final EventHandler<? super T> eventHandler;
    //当前执行器执行到的序列，默认是-1 数据是从0开始的
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    //超时处理
    private final TimeoutHandler timeoutHandler;
    //批处理器开始时处理的个数
    private final BatchStartAware batchStartAware;

    /**
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param eventHandler    is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(final DataProvider<T> dataProvider, final SequenceBarrier sequenceBarrier,
                               final EventHandler<? super T> eventHandler) {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;
        //处理器对应处理的序列
        if (eventHandler instanceof SequenceReportingEventHandler) {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }
        //处理事件的类型
        batchStartAware = (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler = (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence() {
        return sequence;
    }

    @Override
    public void halt() {
        //设置执行器的状态
        running.set(HALTED);
        //设置屏障状态为警告，
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning() {
        return running.get() != IDLE;
    }

    /**
     * 设置自己定义的错误处理器
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler) {
        if (null == exceptionHandler) {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     * 通过线程的方式来调用执行
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run() {
        //cas操作 如果是空闲的状态，改变为运行状态
        if (running.compareAndSet(IDLE, RUNNING)) {
            //开始运行了 清除警告标志
            sequenceBarrier.clearAlert();
            //通知开始执行了
            notifyStart();
            try {
                if (running.get() == RUNNING) {
                    processEvents();
                }
            } finally {
                notifyShutdown();
                //执行完一次，设置为空闲状态，等待下一个线程来调用
                running.set(IDLE);
            }
        } else {
            // This is a little bit of guess work.  The running state could of changed to HALTED by
            // this point.  However, Java does not have compareAndExchange which is the only way
            // to get it exactly correct.
            if (running.get() == RUNNING) {
                throw new IllegalStateException("Thread is already running");
            } else {
                //直接结束
                earlyExit();
            }
        }
    }

    private void processEvents() {
        T event = null;
        //获取可执行的下一个序列，初始化为-1 开始执行的第一个序列为0
        long nextSequence = sequence.get() + 1L;

        while (true) {
            try {
                //可获取的最大序列
                final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                if (batchStartAware != null) {
                    //此次批处理的数量
                    batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                }
                //如果存在下一个序列
                while (nextSequence <= availableSequence) {
                    event = dataProvider.get(nextSequence);
                    //调用事件处理器，真实处理
                    eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                    nextSequence++;
                }
                //设置处理完的序列
                sequence.set(availableSequence);
            } catch (final TimeoutException e) {
                notifyTimeout(sequence.get());
            }
            //等待序列屏障结束时抛出的AlertException异常，通过在这里捕获来结束
            catch (final AlertException ex) {
                if (running.get() != RUNNING) {
                    break;
                }
            } catch (final Throwable ex) {
                exceptionHandler.handleEventException(ex, nextSequence, event);
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }

    /**
     * 提早结束
     */
    private void earlyExit() {
        //通知开始和结束的通知
        notifyStart();
        notifyShutdown();
    }

    /**
     * 通知超时处理
     *
     * @param availableSequence
     */
    private void notifyTimeout(final long availableSequence) {
        try {
            if (timeoutHandler != null) {
                //通知在哪个序列上超时了
                timeoutHandler.onTimeout(availableSequence);
            }
        } catch (Throwable e) {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    /**
     * 如果EventHandler继承了LifecycleAware，可以获取什么时候开始和结束
     * Notifies the EventHandler when this processor is starting up
     */
    private void notifyStart() {
        if (eventHandler instanceof LifecycleAware) {
            try {
                //通知执行器开始执行了
                ((LifecycleAware) eventHandler).onStart();
            } catch (final Throwable ex) {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    /**
     * 通知结束
     * Notifies the EventHandler immediately prior to this processor shutting down
     */
    private void notifyShutdown() {
        if (eventHandler instanceof LifecycleAware) {
            try {
                //通知执行器任务结束了
                ((LifecycleAware) eventHandler).onShutdown();
            } catch (final Throwable ex) {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}