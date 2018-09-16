package com.lmax.disruptor;

/**
 * 批量执行开始时触发
 */
public interface BatchStartAware
{
    void onBatchStart(long batchSize);
}
