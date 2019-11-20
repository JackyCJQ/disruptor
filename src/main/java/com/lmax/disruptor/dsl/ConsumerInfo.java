package com.lmax.disruptor.dsl;

import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;

import java.util.concurrent.Executor;

/**
 * 消费者信息
 */
interface ConsumerInfo {
    //如果是多个消费者 获取每个消费者执行的信息
    Sequence[] getSequences();

    //barrier 只有一份 控制消费者序列的屏障
    SequenceBarrier getBarrier();

    //是否处于链的结尾
    boolean isEndOfChain();

    //开始执行
    void start(Executor executor);

    //停止
    void halt();

    //标记一下
    void markAsUsedInBarrier();

    //是否在运行
    boolean isRunning();
}
