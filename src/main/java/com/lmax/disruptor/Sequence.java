/*
 * Copyright 2012 LMAX Ltd.
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

import com.lmax.disruptor.util.Util;

/**
 * java 不能直接访问操作系统的底层，而是通过本地方法来访问。Unsafe类提供了硬件级别的原子操作
 * 1。可以分配内存和释放内存
 * public native long allocateMemory(long var1);
 * public native long reallocateMemory(long var1, long var3);
 * public native void freeMemory(long var1);
 * 2.可以定位对象某字段的内存位置，也可以修改对象的字段值，即使它是私有的，
 * 可以通过staticFieldOffSet方法实现，该方法返回给点的field的内存地址偏移量，这个值对于给定的field是唯一的且固定不变的
 * getIntVolatile方法获取对象中offset偏移地址对应的整形field的值，支持volatile load语义
 * getLongVolatile方法获取对象中offset偏移地址对应的long型field值
 * <p>
 * <p>
 * ArrayBaseOffSet，可以获取数组元素第一个元素的偏移地址
 * ArrayIndexScale, 可以获取数组中元素的增量地址
 * ArrayBaseOffSet，ArrayIndexScale配合使用可以定位数组中每个元素在内存中的地址
 */

//左填充位一共是56位
class LhsPadding {
    protected long p1, p2, p3, p4, p5, p6, p7;
}

//此时一共是64位 正好占据一个64的缓冲行，避免了伪同步的问题
class Value extends LhsPadding {
    protected volatile long value;
}

//右填充了56位 一共是56+64位
class RhsPadding extends Value {
    protected long p9, p10, p11, p12, p13, p14, p15;
}

//主要是序列号的作用 就是一个long型的值
public class Sequence extends RhsPadding {
    static final long INITIAL_VALUE = -1L;
    //这里应该是cas的内容把 还不是很了解
    private static final Unsafe UNSAFE;
    //偏移量
    private static final long VALUE_OFFSET;

    static {
        UNSAFE = Util.getUnsafe();
        try {
            //这里是取得Value类中 变量 value的偏移量
            //获取在一个缓冲行具体的地址位置
            VALUE_OFFSET = UNSAFE.objectFieldOffset(Value.class.getDeclaredField("value"));
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Sequence() {
        this(INITIAL_VALUE);
    }

    //填充value一个默认值 -1
    public Sequence(final long initialValue) {
        UNSAFE.putOrderedLong(this, VALUE_OFFSET, initialValue);
    }


    public long get() {
        return value;
    }

    /**
     * 更新这个value值
     *
     * @param value
     */
    public void set(final long value) {
        UNSAFE.putOrderedLong(this, VALUE_OFFSET, value);
    }

    /**
     * 设置value的值
     *
     * @param value
     */
    public void setVolatile(final long value) {
        UNSAFE.putLongVolatile(this, VALUE_OFFSET, value);
    }

    /**
     * 更新value处的值
     *
     * @param expectedValue
     * @param newValue
     * @return
     */
    public boolean compareAndSet(final long expectedValue, final long newValue) {
        return UNSAFE.compareAndSwapLong(this, VALUE_OFFSET, expectedValue, newValue);
    }


    public long incrementAndGet() {
        return addAndGet(1L);
    }

    /**
     * 返回增加后的新值
     *
     * @param increment
     * @return
     */
    public long addAndGet(final long increment) {
        long currentValue;
        long newValue;

        //cas更新
        do {
            currentValue = get();
            newValue = currentValue + increment;
        }
        while (!compareAndSet(currentValue, newValue));

        return newValue;
    }

    @Override
    public String toString() {
        return Long.toString(get());
    }
}
