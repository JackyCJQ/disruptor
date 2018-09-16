package com.lmax.disruptor;
//序列相关的信息
public interface Sequenced {

    int getBufferSize();

    boolean hasAvailableCapacity(int requiredCapacity);

    long remainingCapacity();

    long next();

    long next(int n);

    long tryNext() throws InsufficientCapacityException;

    long tryNext(int n) throws InsufficientCapacityException;
    //发布事件
    void publish(long sequence);

    void publish(long lo, long hi);
}