package com.lmax.disruptor;

//序列相关的信息
public interface Sequenced {
    //获取大小
    int getBufferSize();

    //是否还剩下所要求的大小
    boolean hasAvailableCapacity(int requiredCapacity);

    //剩余的空间
    long remainingCapacity();

    //获取下一个序列
    long next();

    //获取下那个
    long next(int n);

    //尝试获取下一个
    long tryNext() throws InsufficientCapacityException;

    //尝试获取下n个
    long tryNext(int n) throws InsufficientCapacityException;

    //发布事件
    void publish(long sequence);

    //发布事件
    void publish(long lo, long hi);
}