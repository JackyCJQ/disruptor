package com.lmax.disruptor.dsl;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

//对线程池封装一层 主要是获取每个线程执行的状态
public class BasicExecutor implements Executor {
    //线程创建工厂
    private final ThreadFactory factory;
    private final Queue<Thread> threads = new ConcurrentLinkedQueue<>();

    public BasicExecutor(ThreadFactory factory) {
        this.factory = factory;
    }

    @Override
    public void execute(Runnable command) {
        //创建一个线程去跑
        final Thread thread = factory.newThread(command);
        if (null == thread) {
            throw new RuntimeException("Failed to create thread to run: " + command);
        }
        thread.start();
        threads.add(thread);
    }

    //返回全部线程信息
    @Override
    public String toString() {
        return "BasicExecutor{" +
                "threads=" + dumpThreadInfo() +
                '}';
    }

    private String dumpThreadInfo() {
        final StringBuilder sb = new StringBuilder();

        final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        for (Thread t : threads) {
            //把ID传入来获取线程的相关信息
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(t.getId());
            sb.append("{");
            sb.append("name=").append(t.getName()).append(",");
            sb.append("id=").append(t.getId()).append(",");
            sb.append("state=").append(threadInfo.getThreadState()).append(",");
            sb.append("lockInfo=").append(threadInfo.getLockInfo());
            sb.append("}");
        }
        return sb.toString();
    }
}
