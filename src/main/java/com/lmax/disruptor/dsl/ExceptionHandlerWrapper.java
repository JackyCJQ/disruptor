package com.lmax.disruptor.dsl;

import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.FatalExceptionHandler;

//典型的代理
public class ExceptionHandlerWrapper<T> implements ExceptionHandler<T> {
    //默认是FatalExceptionHandler 出错会抛出异常
    private ExceptionHandler<? super T> delegate = new FatalExceptionHandler();

    public void switchTo(final ExceptionHandler<? super T> exceptionHandler) {
        this.delegate = exceptionHandler;
    }

    @Override
    public void handleEventException(final Throwable ex, final long sequence, final T event) {
        delegate.handleEventException(ex, sequence, event);
    }

    @Override
    public void handleOnStartException(final Throwable ex) {
        delegate.handleOnStartException(ex);
    }

    @Override
    public void handleOnShutdownException(final Throwable ex) {
        delegate.handleOnShutdownException(ex);
    }
}
