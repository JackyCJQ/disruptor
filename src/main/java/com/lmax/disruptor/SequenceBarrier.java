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

/**
 * 消费者关卡。消费者用于访问缓存的控制器，每个访问控制器还持有前置访问控制器的引用，用于维持正确的事件处理顺序；通过wairStrategy获取可消费的事件序号
 * 。由Sequencer生成，并且包含了已经发布的Sequence的引用，这些的Sequence源于Sequencer和一些独立的消费者的Sequence。它包含了决定是否消费者
 * 来消费的event逻辑。
 */
public interface SequenceBarrier {
    /**
     * 获取可获取到的最大的序列
     * Wait for the given sequence to be available for consumption.
     */
    long waitFor(long sequence) throws AlertException, InterruptedException, TimeoutException;

    /**
     * 获取可读取的最小的序列
     * Get the current cursor value that can be read.
     */
    long getCursor();

    /**
     * 是否处于警告状态
     * The current alert status for the barrier.
     */
    boolean isAlerted();

    /**
     * 设置为警告状态
     * Alert the {@link EventProcessor}s of a status change and stay in this status until cleared.
     */
    void alert();

    /**
     * 清除警告状态
     * Clear the current alert status.
     */
    void clearAlert();

    /**
     * 检查警告状态会抛出异常
     * Check if an alert has been raised and throw an {@link AlertException} if it has.
     */
    void checkAlert() throws AlertException;
}
