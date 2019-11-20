package com.lmax.disruptor.dsl;

import com.lmax.disruptor.*;

import java.util.concurrent.Executor;

//多个消费者信息
class WorkerPoolInfo<T> implements ConsumerInfo {
    //工作池
    private final WorkerPool<T> workerPool;
    //与处理过程交互的序列屏障
    private final SequenceBarrier sequenceBarrier;
    //默认是处于环的末端
    private boolean endOfChain = true;

    //传入对应的信息
    WorkerPoolInfo(final WorkerPool<T> workerPool, final SequenceBarrier sequenceBarrier) {
        this.workerPool = workerPool;
        this.sequenceBarrier = sequenceBarrier;
    }

    //通过workerPool来获取各个消费者的消费序列情况
    @Override
    public Sequence[] getSequences() {
        return workerPool.getWorkerSequences();
    }

    @Override
    public SequenceBarrier getBarrier() {
        return sequenceBarrier;
    }

    @Override
    public boolean isEndOfChain() {
        return endOfChain;
    }

    @Override
    public void start(Executor executor) {
        workerPool.start(executor);
    }

    //通过线程池来终止
    @Override
    public void halt() {
        workerPool.halt();
    }

    //标记开始使用
    @Override
    public void markAsUsedInBarrier() {
        endOfChain = false;
    }

    //判断是否在继续使用
    @Override
    public boolean isRunning() {
        return workerPool.isRunning();
    }
}
