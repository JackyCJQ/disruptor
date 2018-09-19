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
 *
 *
 * 事件转换器，由于disruptro只会覆盖缓存，需要通过此接口的实现来更新缓存里的事件来覆盖旧事件
 * <p>Implementations translate (write) data representations into events claimed from the {@link RingBuffer}.</p>
 *
 * <p>When publishing to the RingBuffer, provide an EventTranslator. The RingBuffer will select the next available
 * event by sequence and provide it to the EventTranslator (which should update the event), before publishing
 * the sequence update.</p>
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public interface EventTranslator<T>
{
    /**
     * Translate a data representation into fields set in given event
     *
     * @param event    into which the data should be translated.
     * @param sequence that is assigned to event.
     */
    void translateTo(T event, long sequence);
}
